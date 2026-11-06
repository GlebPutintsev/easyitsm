package cmdb

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeServer — сервер CMDB: запоминает пакеты, как настоящий возвращает тот же результат на повтор batchId.
type fakeServer struct {
	mu       sync.Mutex
	token    string
	failures map[string][]int // batchId → коды ответов до успешного
	batches  []Batch
	results  map[string]BatchResult
	maxItems int
}

func (f *fakeServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if r.Header.Get("Authorization") != "Bearer "+f.token {
		problem(w, 401, "invalid_token")
		return
	}
	if r.URL.Path == sourcePath && r.Method == http.MethodGet {
		_ = json.NewEncoder(w).Encode(Source{Code: "agent", Name: "Агент", Kind: "discovery", Priority: 20, Active: true})
		return
	}
	if r.URL.Path != batchesPath || r.Method != http.MethodPost || r.Header.Get("Content-Type") != "application/json" {
		problem(w, 404, "not_found")
		return
	}
	var b Batch
	body, _ := io.ReadAll(r.Body)
	if err := json.Unmarshal(body, &b); err != nil {
		problem(w, 400, "invalid_batch")
		return
	}
	if codes := f.failures[b.BatchID]; len(codes) > 0 {
		f.failures[b.BatchID] = codes[1:]
		if codes[0] == 429 {
			w.Header().Set("Retry-After", "1")
		}
		problem(w, codes[0], "boom")
		return
	}
	if f.maxItems > 0 && len(b.Items) > f.maxItems {
		problem(w, 413, "batch_too_large")
		return
	}
	if res, ok := f.results[b.BatchID]; ok {
		_ = json.NewEncoder(w).Encode(res)
		return
	}
	f.batches = append(f.batches, b)
	res := BatchResult{BatchID: b.BatchID, RunID: b.RunID, Final: b.Final, Status: "done", Received: len(b.Items)}
	for _, it := range b.Items {
		outcome := "created"
		code := ""
		if strings.HasPrefix(it.ExternalID, "bad") {
			outcome, code = "error", "secret_not_allowed"
			res.Errors++
		} else {
			res.Created++
		}
		res.Results = append(res.Results, ItemResult{ExternalID: it.ExternalID, Outcome: outcome, Code: code})
	}
	f.results[b.BatchID] = res
	_ = json.NewEncoder(w).Encode(res)
}

func problem(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"code": code, "detail": "ошибка " + code})
}

func newFake(t *testing.T) (*fakeServer, *Client, *[]time.Duration) {
	t.Helper()
	f := &fakeServer{token: "eitsm_cmdb_test", failures: map[string][]int{}, results: map[string]BatchResult{}}
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	c, err := New(srv.URL, "eitsm_cmdb_test\n", Options{Attempts: 4})
	if err != nil {
		t.Fatal(err)
	}
	var sleeps []time.Duration
	c.backoff = func(attempt int) time.Duration { return time.Duration(attempt) * time.Millisecond }
	c.sleep = func(_ context.Context, d time.Duration) error { sleeps = append(sleeps, d); return nil }
	return f, c, &sleeps
}

func items(prefix string, n int) []Item {
	out := make([]Item, n)
	for i := range out {
		out[i] = Item{ExternalID: prefix + string(rune('a'+i%26)) + strings.Repeat("x", i/26), ClassCode: "server", Name: "s"}
	}
	return out
}

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

func TestSourceChecksToken(t *testing.T) {
	_, c, _ := newFake(t)
	s, err := c.Source(context.Background())
	if err != nil || s.Code != "agent" || !s.Active {
		t.Fatalf("источник %+v, ошибка %v", s, err)
	}
	c.token = "eitsm_cmdb_wrong"
	_, err = c.Source(context.Background())
	var apiErr *APIError
	if !errors.As(err, &apiErr) || apiErr.Status != 401 || apiErr.Code != "invalid_token" {
		t.Fatalf("ждали 401 invalid_token, получили %v", err)
	}
}

func TestSendRetriesTemporaryFailuresWithSameBatchID(t *testing.T) {
	f, c, sleeps := newFake(t)
	f.failures["r-0001"] = []int{503, 429}
	res, err := c.Send(context.Background(), Batch{BatchID: "r-0001", RunID: "r", Items: items("h", 2)})
	if err != nil {
		t.Fatal(err)
	}
	if res.Created != 2 || len(f.batches) != 1 {
		t.Fatalf("пакет применён %d раз, создано %d", len(f.batches), res.Created)
	}
	// 429 с Retry-After: 1 — ждём не меньше секунды.
	if len(*sleeps) != 2 || (*sleeps)[1] != time.Second {
		t.Fatalf("паузы %v", *sleeps)
	}
	// Повтор уже принятого пакета возвращает тот же результат.
	again, err := c.Send(context.Background(), Batch{BatchID: "r-0001", RunID: "r", Items: items("h", 2)})
	if err != nil || again.Created != 2 || len(f.batches) != 1 {
		t.Fatalf("повтор: %+v %v", again, err)
	}
}

func TestSendDoesNotRetryClientErrors(t *testing.T) {
	f, c, sleeps := newFake(t)
	f.failures["r-0001"] = []int{409}
	_, err := c.Send(context.Background(), Batch{BatchID: "r-0001", Items: items("h", 1)})
	var apiErr *APIError
	if !errors.As(err, &apiErr) || apiErr.Status != 409 || len(*sleeps) != 0 {
		t.Fatalf("ошибка %v, паузы %v", err, *sleeps)
	}
	if !strings.Contains(err.Error(), "409 boom") {
		t.Fatalf("текст ошибки: %v", err)
	}
}

func TestSendGivesUpAfterAttempts(t *testing.T) {
	f, c, sleeps := newFake(t)
	f.failures["r-0001"] = []int{500, 502, 503, 504, 500}
	_, err := c.Send(context.Background(), Batch{BatchID: "r-0001", Items: items("h", 1)})
	if err == nil || len(*sleeps) != 3 {
		t.Fatalf("ошибка %v, паузы %v", err, *sleeps)
	}
}

func TestUploadSplitsBatchesAndClosesFullSweep(t *testing.T) {
	f, c, _ := newFake(t)
	all := append(items("h", 5), Item{ExternalID: "bad-1", ClassCode: "server", Name: "bad"})
	chunks, skipped := split(all, 4, MaxBytes)
	if len(chunks) != 2 || len(chunks[0]) != 4 || len(skipped) != 0 {
		t.Fatalf("пакеты %d, пропущено %v", len(chunks), skipped)
	}
	sum, err := Upload(context.Background(), c, "agent-1", all, true, quiet)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Batches != 2 || sum.Received != 6 || sum.Created != 5 || sum.Errors != 1 || !sum.Final {
		t.Fatalf("итог %+v", sum)
	}
	if len(sum.Failed) != 1 || sum.Failed[0].Code != "secret_not_allowed" {
		t.Fatalf("ошибки %+v", sum.Failed)
	}
	last := f.batches[len(f.batches)-1]
	if !last.Final || last.BatchID != "agent-1-final" || last.RunID != "agent-1" || len(last.Items) != 0 {
		t.Fatalf("завершающий пакет %+v", last)
	}
	for _, b := range f.batches {
		if b.RunID != "agent-1" {
			t.Fatalf("пакет без обхода: %+v", b)
		}
	}
}

func TestUploadWithoutFinalWhenBatchRejected(t *testing.T) {
	f, c, _ := newFake(t)
	f.failures["agent-1-0001"] = []int{400}
	_, err := Upload(context.Background(), c, "agent-1", items("h", 3), true, quiet)
	if err == nil {
		t.Fatal("ждали ошибку")
	}
	for _, b := range f.batches {
		if b.Final {
			t.Fatal("после непринятого пакета нельзя завершать полный обход")
		}
	}
}

func TestUploadSplitsOn413(t *testing.T) {
	f, c, _ := newFake(t)
	f.maxItems = 2
	sum, err := Upload(context.Background(), c, "r", items("h", 5), false, quiet)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Created != 5 || sum.Final {
		t.Fatalf("итог %+v", sum)
	}
	var ids []string
	for _, b := range f.batches {
		ids = append(ids, b.BatchID)
	}
	if strings.Join(ids, ",") != "r-0001a,r-0001ba,r-0001bb" {
		t.Fatalf("номера пакетов %v", ids)
	}
}

func TestSplitBySize(t *testing.T) {
	big := Item{ExternalID: "big", ClassCode: "server", Name: strings.Repeat("я", 600)}
	chunks, skipped := split([]Item{big, big, big, {ExternalID: "huge", Name: strings.Repeat("x", 5000)}}, 1000, 2500)
	if len(chunks) != 3 || len(skipped) != 1 || skipped[0] != "huge" {
		t.Fatalf("пакеты %d, пропущено %v", len(chunks), skipped)
	}
}

func TestNewRejectsPlainHTTPAndBadAddress(t *testing.T) {
	if _, err := New("http://itsm.example.ru", "t", Options{}); err == nil {
		t.Fatal("токен по http на внешний адрес")
	}
	if _, err := New("http://itsm.example.ru", "t", Options{AllowHTTP: true}); err != nil {
		t.Fatal(err)
	}
	if _, err := New("http://127.0.0.1:8081", "t", Options{}); err != nil {
		t.Fatal(err)
	}
	if _, err := New("itsm.example.ru", "t", Options{}); err == nil {
		t.Fatal("адрес без схемы")
	}
	if _, err := New("https://itsm.example.ru", " ", Options{}); err == nil {
		t.Fatal("пустой токен")
	}
	c, err := New("https://itsm.example.ru/itsm/", "t", Options{})
	if err != nil || c.Server().Path != "/itsm" {
		t.Fatalf("путь %v %v", c.Server(), err)
	}
}

func TestSafeID(t *testing.T) {
	if got := SafeID("сервер app 01", 50); got != "app-01" {
		t.Fatalf("SafeID: %q", got)
	}
	if got := SafeID("abcdef", 3); got != "abc" {
		t.Fatalf("SafeID: %q", got)
	}
}
