package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func site(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	must(t, os.WriteFile(filepath.Join(dir, "index.html"), []byte("<html>spa</html>"), 0o644))
	must(t, os.MkdirAll(filepath.Join(dir, "assets"), 0o755))
	must(t, os.WriteFile(filepath.Join(dir, "assets", "app-1a2b.js"), []byte("console.log(1)"), 0o644))
	must(t, os.WriteFile(filepath.Join(dir, "favicon.svg"), []byte("<svg/>"), 0o644))
	return dir
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func get(h http.Handler, method, target string) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(method, target, nil))
	return rec
}

func TestClientRouteFallsBackToIndex(t *testing.T) {
	h := newHandler(site(t))
	rec := get(h, http.MethodGet, "/agent/queue/42")
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), "spa") {
		t.Fatalf("ожидался index.html, получено %d %q", rec.Code, rec.Body.String())
	}
	if rec.Header().Get("Cache-Control") != "no-cache" {
		t.Fatalf("index.html не должен кэшироваться: %q", rec.Header().Get("Cache-Control"))
	}
	if rec.Header().Get("Content-Security-Policy") == "" || rec.Header().Get("X-Frame-Options") != "DENY" {
		t.Fatal("нет заголовков безопасности")
	}
}

func TestAssetsAreImmutable(t *testing.T) {
	h := newHandler(site(t))
	rec := get(h, http.MethodGet, "/assets/app-1a2b.js")
	if rec.Code != http.StatusOK || !strings.Contains(rec.Header().Get("Cache-Control"), "immutable") {
		t.Fatalf("ожидался кэшируемый ресурс, получено %d %q", rec.Code, rec.Header().Get("Cache-Control"))
	}
}

func TestMissingAssetIs404NotIndex(t *testing.T) {
	h := newHandler(site(t))
	if rec := get(h, http.MethodGet, "/assets/old-0000.js"); rec.Code != http.StatusNotFound {
		t.Fatalf("старый ресурс должен давать 404, получено %d", rec.Code)
	}
}

func TestPathTraversalStaysInRoot(t *testing.T) {
	h := newHandler(site(t))
	rec := get(h, http.MethodGet, "/../../etc/passwd")
	if strings.Contains(rec.Body.String(), "root:") {
		t.Fatal("выход за пределы каталога")
	}
}

func TestOnlyGetAndHead(t *testing.T) {
	h := newHandler(site(t))
	if rec := get(h, http.MethodPost, "/"); rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST должен быть запрещён, получено %d", rec.Code)
	}
}

func TestHealth(t *testing.T) {
	h := newHandler(site(t))
	if rec := get(h, http.MethodGet, "/healthz"); rec.Code != http.StatusOK {
		t.Fatalf("healthz: %d", rec.Code)
	}
}
