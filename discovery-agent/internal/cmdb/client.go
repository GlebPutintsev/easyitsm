package cmdb

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/rand/v2"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	batchesPath = "/api/v1/cmdb/ingest/batches"
	sourcePath  = "/api/v1/cmdb/ingest/source"
	maxResponse = 16 << 20
)

// Options — настройки клиента.
type Options struct {
	// CAFile — сертификат УЦ заказчика (PEM) в дополнение к системным.
	CAFile string
	// AllowHTTP разрешает http:// не только для localhost (стенд разработчика). Токен тогда идёт открытым текстом.
	AllowHTTP bool
	// Timeout одного запроса. Пакет обрабатывается сервером синхронно, поэтому по умолчанию 2 минуты.
	Timeout time.Duration
	// Attempts — сколько раз пробовать пакет при сбое сети, 429 и 5xx.
	Attempts  int
	UserAgent string
	Logger    *slog.Logger
}

// Client — клиент API загрузки CMDB с токеном источника.
type Client struct {
	base      *url.URL
	token     string
	http      *http.Client
	attempts  int
	userAgent string
	log       *slog.Logger
	// backoff и sleep подменяются в тестах.
	backoff func(attempt int) time.Duration
	sleep   func(ctx context.Context, d time.Duration) error
}

// APIError — ответ сервера с ошибкой (RFC 9457: code, detail, field).
type APIError struct {
	Status     int
	Code       string
	Detail     string
	Field      string
	RetryAfter time.Duration
}

func (e *APIError) Error() string {
	msg := fmt.Sprintf("сервер ответил %d", e.Status)
	if e.Code != "" {
		msg += " " + e.Code
	}
	if e.Detail != "" {
		msg += ": " + e.Detail
	}
	if e.Field != "" {
		msg += " (поле " + e.Field + ")"
	}
	return msg
}

// Temporary — сбой, который имеет смысл повторить: перегрузка или ошибка сервера.
func (e *APIError) Temporary() bool {
	return e.Status == http.StatusTooManyRequests || e.Status == http.StatusRequestTimeout || e.Status >= 500
}

// New создаёт клиент. server — адрес системы, например https://itsm.example.ru.
func New(server, token string, opts Options) (*Client, error) {
	u, err := url.Parse(strings.TrimRight(strings.TrimSpace(server), "/"))
	if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") {
		return nil, fmt.Errorf("адрес сервера %q: нужен вид https://itsm.example.ru", server)
	}
	if u.Scheme == "http" && !opts.AllowHTTP && !isLoopback(u.Hostname()) {
		return nil, fmt.Errorf("адрес сервера %q: токен нельзя передавать по http, используйте https "+
			"(или allowHttp для стенда)", server)
	}
	if strings.TrimSpace(token) == "" {
		return nil, errors.New("не задан токен источника")
	}
	tlsConfig := &tls.Config{MinVersion: tls.VersionTLS12}
	if opts.CAFile != "" {
		pem, err := os.ReadFile(opts.CAFile)
		if err != nil {
			return nil, fmt.Errorf("сертификат УЦ: %w", err)
		}
		pool, err := x509.SystemCertPool()
		if err != nil || pool == nil {
			pool = x509.NewCertPool()
		}
		if !pool.AppendCertsFromPEM(pem) {
			return nil, fmt.Errorf("сертификат УЦ %s: в файле нет сертификатов PEM", opts.CAFile)
		}
		tlsConfig.RootCAs = pool
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.TLSClientConfig = tlsConfig
	timeout := opts.Timeout
	if timeout <= 0 {
		timeout = 2 * time.Minute
	}
	attempts := opts.Attempts
	if attempts <= 0 {
		attempts = 6
	}
	log := opts.Logger
	if log == nil {
		log = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	ua := opts.UserAgent
	if ua == "" {
		ua = "easyitsm-discovery-agent"
	}
	return &Client{
		base:      u,
		token:     strings.TrimSpace(token),
		http:      &http.Client{Transport: transport, Timeout: timeout},
		attempts:  attempts,
		userAgent: ua,
		log:       log,
		backoff:   expBackoff,
		sleep:     sleepCtx,
	}, nil
}

// Server — адрес сервера, к которому обращается клиент.
func (c *Client) Server() *url.URL {
	return c.base
}

// Source возвращает источник, которому принадлежит токен: проверка настройки агента.
func (c *Client) Source(ctx context.Context) (Source, error) {
	var s Source
	err := c.retry(ctx, "проверка источника", func() error {
		return c.do(ctx, http.MethodGet, sourcePath, nil, &s)
	})
	return s, err
}

// Send отправляет пакет. Сбои сети, 429 и 5xx повторяются с тем же batchId: сервер применит пакет один раз.
func (c *Client) Send(ctx context.Context, b Batch) (BatchResult, error) {
	body, err := json.Marshal(b)
	if err != nil {
		return BatchResult{}, err
	}
	var res BatchResult
	err = c.retry(ctx, "пакет "+b.BatchID, func() error {
		res = BatchResult{}
		return c.do(ctx, http.MethodPost, batchesPath, body, &res)
	})
	return res, err
}

func (c *Client) retry(ctx context.Context, what string, call func() error) error {
	var err error
	for attempt := 1; ; attempt++ {
		err = call()
		if err == nil || attempt >= c.attempts || !temporary(ctx, err) {
			return err
		}
		delay := c.backoff(attempt)
		var apiErr *APIError
		if errors.As(err, &apiErr) && apiErr.RetryAfter > delay {
			delay = min(apiErr.RetryAfter, 10*time.Minute)
		}
		c.log.Warn("сбой, повтор", "что", what, "попытка", attempt, "через", delay.String(), "ошибка", err)
		if serr := c.sleep(ctx, delay); serr != nil {
			return err
		}
	}
}

func temporary(ctx context.Context, err error) bool {
	if ctx.Err() != nil {
		return false
	}
	var apiErr *APIError
	if errors.As(err, &apiErr) {
		return apiErr.Temporary()
	}
	// Ошибки TLS и неверного адреса не пройдут сами: повторять их бессмысленно.
	var certErr *tls.CertificateVerificationError
	var unknownAuthority x509.UnknownAuthorityError
	var hostErr x509.HostnameError
	if errors.As(err, &certErr) || errors.As(err, &unknownAuthority) || errors.As(err, &hostErr) {
		return false
	}
	return true
}

func (c *Client) do(ctx context.Context, method, path string, body []byte, out any) error {
	u := *c.base
	u.Path = strings.TrimRight(u.Path, "/") + path
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, u.String(), reader)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", c.userAgent)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxResponse))
	if err != nil {
		return err
	}
	if resp.StatusCode >= 300 {
		return apiError(resp, data)
	}
	if err := json.Unmarshal(data, out); err != nil {
		return fmt.Errorf("ответ сервера не разобран: %w", err)
	}
	return nil
}

func apiError(resp *http.Response, data []byte) *APIError {
	var problem struct {
		Code   string `json:"code"`
		Detail string `json:"detail"`
		Field  string `json:"field"`
	}
	_ = json.Unmarshal(data, &problem)
	e := &APIError{Status: resp.StatusCode, Code: problem.Code, Detail: problem.Detail, Field: problem.Field}
	if s, err := strconv.Atoi(strings.TrimSpace(resp.Header.Get("Retry-After"))); err == nil && s > 0 {
		e.RetryAfter = time.Duration(s) * time.Second
	}
	return e
}

// expBackoff: 2, 4, 8… секунд с разбросом ±20 %, не больше 2 минут.
func expBackoff(attempt int) time.Duration {
	d := time.Second << min(attempt, 7)
	d = min(d, 2*time.Minute)
	jitter := time.Duration(rand.Int64N(int64(d)/5+1)) - d/10
	return d + jitter
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

func isLoopback(host string) bool {
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}
