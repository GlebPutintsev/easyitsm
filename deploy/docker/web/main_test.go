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
	h := newHandler(site(t), "")
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
	h := newHandler(site(t), "")
	rec := get(h, http.MethodGet, "/assets/app-1a2b.js")
	if rec.Code != http.StatusOK || !strings.Contains(rec.Header().Get("Cache-Control"), "immutable") {
		t.Fatalf("ожидался кэшируемый ресурс, получено %d %q", rec.Code, rec.Header().Get("Cache-Control"))
	}
}

func TestMissingAssetIs404NotIndex(t *testing.T) {
	h := newHandler(site(t), "")
	if rec := get(h, http.MethodGet, "/assets/old-0000.js"); rec.Code != http.StatusNotFound {
		t.Fatalf("старый ресурс должен давать 404, получено %d", rec.Code)
	}
}

func TestPathTraversalStaysInRoot(t *testing.T) {
	h := newHandler(site(t), "")
	rec := get(h, http.MethodGet, "/../../etc/passwd")
	if strings.Contains(rec.Body.String(), "root:") {
		t.Fatal("выход за пределы каталога")
	}
}

func TestOnlyGetAndHead(t *testing.T) {
	h := newHandler(site(t), "")
	if rec := get(h, http.MethodPost, "/"); rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST должен быть запрещён, получено %d", rec.Code)
	}
}

func TestHealth(t *testing.T) {
	h := newHandler(site(t), "")
	if rec := get(h, http.MethodGet, "/healthz"); rec.Code != http.StatusOK {
		t.Fatalf("healthz: %d", rec.Code)
	}
}

func TestFilesOriginInPolicy(t *testing.T) {
	h := newHandler(site(t), "https://files.example.ru")
	policy := get(h, http.MethodGet, "/").Header().Get("Content-Security-Policy")
	for _, want := range []string{"connect-src 'self' https://files.example.ru;", "img-src 'self' data: blob: https://files.example.ru;"} {
		if !strings.Contains(policy, want) {
			t.Fatalf("в политике нет %q: %s", want, policy)
		}
	}
	if p := get(newHandler(site(t), ""), http.MethodGet, "/").Header().Get("Content-Security-Policy"); !strings.Contains(p, "connect-src 'self';") {
		t.Fatalf("без хранилища только 'self': %s", p)
	}
}

func TestFilesOriginValidated(t *testing.T) {
	for _, ok := range []string{"", "https://files.example.ru", "http://localhost:9000"} {
		if err := validOrigin(ok); err != nil {
			t.Fatalf("%q должен проходить: %v", ok, err)
		}
	}
	for _, bad := range []string{"files.example.ru", "https://files.example.ru/bucket", "https://a.ru; script-src *", "javascript:x", "https://*.ru"} {
		if validOrigin(bad) == nil {
			t.Fatalf("%q должен отклоняться", bad)
		}
	}
}
