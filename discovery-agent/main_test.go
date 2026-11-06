package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"easyitsm.ru/discovery-agent/internal/cmdb"
	"easyitsm.ru/discovery-agent/internal/config"
)

func TestOnceSendsHostToCMDB(t *testing.T) {
	var (
		mu      sync.Mutex
		batches []cmdb.Batch
	)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer eitsm_cmdb_main" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if r.Method == http.MethodGet && r.URL.Path == "/api/v1/cmdb/ingest/source" {
			_, _ = w.Write([]byte(`{"code":"disc","name":"Агент","kind":"discovery","priority":20,"active":true}`))
			return
		}
		var b cmdb.Batch
		if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		mu.Lock()
		batches = append(batches, b)
		mu.Unlock()
		_ = json.NewEncoder(w).Encode(cmdb.BatchResult{BatchID: b.BatchID, Received: len(b.Items), Created: len(b.Items)})
	}))
	defer srv.Close()

	t.Setenv(config.EnvToken, "eitsm_cmdb_main")
	t.Setenv(config.EnvServer, "")
	cfg := filepath.Join(t.TempDir(), "discovery.json")
	body := `{"server":"` + srv.URL + `","agentId":"тест agent","environment":"test","host":{"databases":false}}`
	if err := os.WriteFile(cfg, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}

	var out, errOut bytes.Buffer
	if code := run([]string{"-config", cfg, "check"}, &out, &errOut); code != exitOK {
		t.Fatalf("check: код %d, %s", code, errOut.String())
	}
	if !strings.Contains(out.String(), "Источник disc «Агент»") {
		t.Fatalf("check: %s", out.String())
	}

	if code := run([]string{"-config", cfg, "once"}, &out, &errOut); code != exitOK {
		t.Fatalf("once: код %d, %s", code, errOut.String())
	}
	if len(batches) != 1 || len(batches[0].Items) != 1 || batches[0].Final {
		t.Fatalf("пакеты %+v", batches)
	}
	b := batches[0]
	if !strings.HasPrefix(b.RunID, "agent-") || b.BatchID != b.RunID+"-0001" {
		t.Fatalf("номера: %s %s", b.RunID, b.BatchID)
	}
	host := b.Items[0]
	if host.Environment != "test" || !strings.HasPrefix(host.ExternalID, "host:") || host.Name == "" {
		t.Fatalf("сервер %+v", host)
	}

	t.Setenv(config.EnvToken, "eitsm_cmdb_wrong")
	if code := run([]string{"-config", cfg, "once"}, &out, &errOut); code != exitFailed {
		t.Fatalf("неверный токен: код %d", code)
	}
}

func TestPrintWorksWithoutServer(t *testing.T) {
	t.Setenv(config.EnvToken, "")
	t.Setenv(config.EnvServer, "")
	cfg := filepath.Join(t.TempDir(), "discovery.json")
	if err := os.WriteFile(cfg, []byte(`{}`), 0o600); err != nil {
		t.Fatal(err)
	}
	var out, errOut bytes.Buffer
	if code := run([]string{"-config", cfg, "print"}, &out, &errOut); code != exitOK {
		t.Fatalf("код %d, %s", code, errOut.String())
	}
	var items []cmdb.Item
	if err := json.Unmarshal(out.Bytes(), &items); err != nil || len(items) == 0 {
		t.Fatalf("вывод %s: %v", out.String(), err)
	}
}

func TestUsageAndConfigErrors(t *testing.T) {
	var out, errOut bytes.Buffer
	if code := run([]string{"version"}, &out, &errOut); code != exitOK || strings.TrimSpace(out.String()) != version {
		t.Fatalf("version: %d %q", code, out.String())
	}
	if code := run([]string{"start"}, &out, &errOut); code != exitConfig {
		t.Fatalf("неизвестная команда: %d", code)
	}
	if code := run([]string{"-config", "/нет/файла.json", "once"}, &out, &errOut); code != exitConfig {
		t.Fatalf("нет файла: %d", code)
	}
}

func TestServerAddr(t *testing.T) {
	cases := map[string]string{
		"https://itsm.example.ru":      "itsm.example.ru:443",
		"http://localhost:8081/":       "localhost:8081",
		"https://itsm.example.ru/itsm": "itsm.example.ru:443",
		"http://[::1]":                 "[::1]:80",
		"itsm.example.ru":              "",
	}
	for in, want := range cases {
		if got := serverAddr(in); got != want {
			t.Errorf("serverAddr(%q) = %q, ждали %q", in, got, want)
		}
	}
}
