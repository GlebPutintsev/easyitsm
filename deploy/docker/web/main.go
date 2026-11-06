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
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

// Политика безопасности контента для SPA. Ответы API ядро отдаёт со своей, более строгой политикой.
//
// filesOrigin — адрес хранилища файлов (S3), куда браузер сам кладёт вложения по подписанной ссылке и откуда
// показывает картинки: без него в connect-src и img-src загрузка и предпросмотр не работают. Пустой — только 'self'
// (хранилище за тем же доменом, например через /files на входном шлюзе).
func csp(filesOrigin string) string {
	files := ""
	if filesOrigin != "" {
		files = " " + filesOrigin
	}
	return "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:" + files + "; " +
		"font-src 'self' data:; connect-src 'self'" + files + "; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; " +
		"object-src 'none'"
}

// validOrigin пропускает только схему, хост и порт: в политику не должны попасть пробелы, «;» и подстановки.
func validOrigin(origin string) error {
	if origin == "" {
		return nil
	}
	u, err := url.Parse(origin)
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" || u.Path != "" || u.RawQuery != "" ||
		u.Fragment != "" || u.User != nil || strings.ContainsAny(origin, " ;,*'\"") {
		return fmt.Errorf("адрес хранилища файлов должен быть вида https://files.example.ru[:порт], получено %q", origin)
	}
	return nil
}

func main() {
	addr := flag.String("addr", ":8080", "адрес HTTP")
	root := flag.String("root", "/srv", "каталог собранного SPA")
	filesOrigin := flag.String("files-origin", os.Getenv("EASYITSM_FILES_ORIGIN"),
		"адрес хранилища файлов для CSP (по умолчанию из EASYITSM_FILES_ORIGIN)")
	flag.Parse()

	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := validOrigin(*filesOrigin); err != nil {
		logger.Error("web-server misconfigured", "error", err)
		os.Exit(2)
	}
	srv := &http.Server{
		Addr:              *addr,
		Handler:           newHandler(*root, *filesOrigin),
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

func newHandler(root, filesOrigin string) http.Handler {
	files := http.FileServer(http.Dir(root))
	index := filepath.Join(root, "index.html")
	policy := csp(filesOrigin)

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Security-Policy", policy)
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
