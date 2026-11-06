// Package cmdb отправляет объекты в CMDB Easy ITSM через API загрузки источников
// (POST /api/v1/cmdb/ingest/batches, контракт docs/api/cmdb-contract.md).
package cmdb

// Target — цель связи: объект того же источника (ExternalID) или КЕ (CiID).
type Target struct {
	ExternalID string `json:"externalId,omitempty"`
	CiID       string `json:"ciId,omitempty"`
}

// Relation — связь от объекта. Direction: outgoing или incoming.
type Relation struct {
	TypeCode  string `json:"typeCode"`
	Direction string `json:"direction"`
	Target    Target `json:"target"`
}

// Item — объект источника. Relations == nil — связи объекта на сервере не трогаются.
type Item struct {
	ExternalID  string         `json:"externalId"`
	ClassCode   string         `json:"classCode"`
	Name        string         `json:"name"`
	Status      string         `json:"status,omitempty"`
	Environment string         `json:"environment,omitempty"`
	Attributes  map[string]any `json:"attributes,omitempty"`
	Relations   []Relation     `json:"relations,omitempty"`
}

// Batch — пакет объектов. Повтор пакета с тем же BatchID безопасен: сервер вернёт сохранённый результат.
type Batch struct {
	BatchID string   `json:"batchId"`
	RunID   string   `json:"runId,omitempty"`
	Final   bool     `json:"final,omitempty"`
	Items   []Item   `json:"items"`
	Removed []string `json:"removed,omitempty"`
}

// Warning — проблема со связью объекта, которая не помешала его применить.
type Warning struct {
	Code  string `json:"code"`
	Field string `json:"field"`
}

// ItemResult — исход одного объекта: created, updated, unchanged, conflict, error, removed, not_found.
type ItemResult struct {
	ExternalID string    `json:"externalId"`
	CiID       string    `json:"ciId"`
	Outcome    string    `json:"outcome"`
	Code       string    `json:"code"`
	Field      string    `json:"field"`
	Conflicts  int       `json:"conflicts"`
	Warnings   []Warning `json:"warnings"`
}

// BatchResult — ответ сервера на пакет.
type BatchResult struct {
	BatchID   string       `json:"batchId"`
	RunID     string       `json:"runId"`
	Final     bool         `json:"final"`
	Status    string       `json:"status"`
	Received  int          `json:"received"`
	Created   int          `json:"created"`
	Updated   int          `json:"updated"`
	Unchanged int          `json:"unchanged"`
	Conflicts int          `json:"conflicts"`
	Errors    int          `json:"errors"`
	Removed   int          `json:"removed"`
	Results   []ItemResult `json:"results"`
}

// Source — источник, которому принадлежит токен агента.
type Source struct {
	Code     string `json:"code"`
	Name     string `json:"name"`
	Kind     string `json:"kind"`
	Priority int    `json:"priority"`
	Active   bool   `json:"active"`
}
