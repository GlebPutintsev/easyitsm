package cmdb

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"regexp"
	"strings"
)

// Ограничения сервера: до 1000 объектов и 5 МБ в пакете. Агент оставляет запас на обёртку пакета.
const (
	MaxItems = 1000
	MaxBytes = 4 << 20
)

// Sender отправляет один пакет (Client.Send).
type Sender interface {
	Send(ctx context.Context, b Batch) (BatchResult, error)
}

// Summary — итог обхода.
type Summary struct {
	RunID     string
	Batches   int
	Received  int
	Created   int
	Updated   int
	Unchanged int
	Conflicts int
	Errors    int
	Final     bool
	// Failed — объекты с исходом error (код и поле), не больше 50 для журнала.
	Failed []ItemResult
	// Skipped — объекты, которые агент не отправил: один объект больше лимита пакета.
	Skipped []string
}

// Upload отправляет объекты обхода runID пакетами. Номера пакетов детерминированы (runID-0001…), поэтому
// повтор после обрыва связи безопасен. fullSweep — обход полный: после всех пакетов агент отправляет
// завершающий пакет final, и объекты источника, которых не было в обходе, CMDB отметит неактуальными.
// Если хоть один пакет не принят, завершающий пакет не отправляется: иначе непринятые объекты
// ошибочно стали бы неактуальными.
func Upload(ctx context.Context, s Sender, runID string, items []Item, fullSweep bool, log *slog.Logger) (Summary, error) {
	sum := Summary{RunID: runID}
	chunks, skipped := split(items, MaxItems, MaxBytes)
	sum.Skipped = skipped
	for _, id := range skipped {
		log.Error("объект больше лимита пакета, пропущен", "externalId", id)
	}
	for i, chunk := range chunks {
		id := fmt.Sprintf("%s-%04d", runID, i+1)
		if err := send(ctx, s, Batch{BatchID: id, RunID: runID, Items: chunk}, &sum, log); err != nil {
			return sum, err
		}
	}
	if fullSweep {
		if len(skipped) > 0 {
			log.Warn("полный обход не завершён: часть объектов не отправлена", "пропущено", len(skipped))
			return sum, nil
		}
		if err := send(ctx, s, Batch{BatchID: runID + "-final", RunID: runID, Final: true, Items: []Item{}}, &sum, log); err != nil {
			return sum, err
		}
		sum.Final = true
	}
	return sum, nil
}

func send(ctx context.Context, s Sender, b Batch, sum *Summary, log *slog.Logger) error {
	res, err := s.Send(ctx, b)
	var apiErr *APIError
	if errors.As(err, &apiErr) && apiErr.Status == http.StatusRequestEntityTooLarge && len(b.Items) > 1 {
		// Сервер посчитал пакет большим: делим пополам с новыми номерами.
		half := len(b.Items) / 2
		log.Warn("пакет больше лимита сервера, делю пополам", "пакет", b.BatchID, "объектов", len(b.Items))
		first := Batch{BatchID: b.BatchID + "a", RunID: b.RunID, Items: b.Items[:half]}
		second := Batch{BatchID: b.BatchID + "b", RunID: b.RunID, Items: b.Items[half:]}
		if err := send(ctx, s, first, sum, log); err != nil {
			return err
		}
		return send(ctx, s, second, sum, log)
	}
	if err != nil {
		return fmt.Errorf("пакет %s: %w", b.BatchID, err)
	}
	sum.Batches++
	sum.Received += res.Received
	sum.Created += res.Created
	sum.Updated += res.Updated
	sum.Unchanged += res.Unchanged
	sum.Conflicts += res.Conflicts
	sum.Errors += res.Errors
	for _, r := range res.Results {
		if r.Outcome == "error" && len(sum.Failed) < 50 {
			sum.Failed = append(sum.Failed, r)
		}
		for _, w := range r.Warnings {
			log.Debug("предупреждение по объекту", "externalId", r.ExternalID, "код", w.Code, "поле", w.Field)
		}
	}
	log.Info("пакет принят", "пакет", b.BatchID, "получено", res.Received, "создано", res.Created,
		"изменено", res.Updated, "без изменений", res.Unchanged, "конфликтов", res.Conflicts, "ошибок", res.Errors)
	return nil
}

// split делит объекты на пакеты по числу и размеру JSON.
func split(items []Item, maxItems, maxBytes int) (chunks [][]Item, skipped []string) {
	var cur []Item
	size := 0
	for _, it := range items {
		data, err := json.Marshal(it)
		if err != nil || len(data) > maxBytes {
			skipped = append(skipped, it.ExternalID)
			continue
		}
		if len(cur) > 0 && (len(cur) >= maxItems || size+len(data)+1 > maxBytes) {
			chunks = append(chunks, cur)
			cur, size = nil, 0
		}
		cur = append(cur, it)
		size += len(data) + 1
	}
	if len(cur) > 0 {
		chunks = append(chunks, cur)
	}
	return chunks, skipped
}

var unsafeID = regexp.MustCompile(`[^A-Za-z0-9._:-]+`)

// SafeID приводит строку к допустимому в batchId и runId виду: латиница, цифры, «.», «_», «:», «-».
func SafeID(s string, max int) string {
	s = strings.Trim(unsafeID.ReplaceAllString(s, "-"), "-")
	if len(s) > max {
		s = s[:max]
	}
	return s
}
