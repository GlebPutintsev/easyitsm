// Статический сервер веб-клиента Easy ITSM.
//
// Отдаёт собранный SPA из каталога -root: файлы из /assets/ кэшируются навсегда (в имени есть хеш),
// остальные пути возвращают index.html без кэша, чтобы работала маршрутизация на клиенте.
// Без зависимостей, чтобы образ оставался distroless без оболочки (SEC-34).
package main

import (
	"context"
	"errors"
	"flag"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

// Политика безопасности контента для SPA. Ответы API ядро отдаёт со своей, более строгой политикой.
const csp = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; " +
	"font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'"

func main() {
	addr := flag.String("addr", ":8080", "адрес HTTP")
	root := flag.String("root", "/srv", "каталог собранного SPA")
	flag.Parse()

	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	srv := &http.Server{
		Addr:              *addr,
		Handler:           newHandler(*root),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdown)
	}()

	logger.Info("web-server started", "addr", *addr, "root", *root)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		logger.Error("web-server failed", "error", err)
		os.Exit(1)
	}
}

func newHandler(root string) http.Handler {
	files := http.FileServer(http.Dir(root))
	index := filepath.Join(root, "index.html")

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Security-Policy", csp)
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
		h.Set("Cross-Origin-Opener-Policy", "same-origin")

		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			h.Set("Allow", "GET, HEAD")
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		if r.URL.Path == "/healthz" {
			h.Set("Cache-Control", "no-store")
			_, _ = w.Write([]byte("ok"))
			return
		}

		clean := path.Clean("/" + r.URL.Path)
		if strings.HasPrefix(clean, "/assets/") {
			if _, err := os.Stat(filepath.Join(root, filepath.FromSlash(clean))); err != nil {
				http.NotFound(w, r)
				return
			}
			h.Set("Cache-Control", "public, max-age=31536000, immutable")
			files.ServeHTTP(w, r)
			return
		}

		if clean != "/" {
			if info, err := os.Stat(filepath.Join(root, filepath.FromSlash(clean))); err == nil && !info.IsDir() {
				h.Set("Cache-Control", "no-cache")
				files.ServeHTTP(w, r)
				return
			}
		}
		// Маршрут клиента: всегда index.html, без кэша, чтобы новая версия подхватывалась сразу.
		h.Set("Cache-Control", "no-cache")
		http.ServeFile(w, r, index)
	})
}
