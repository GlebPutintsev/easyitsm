// Агент обнаружения Easy ITSM (CFG-04): собирает сведения о сервере и узлах сети и загружает их в CMDB
// пакетами через API источников. Один исполняемый файл без зависимостей.
//
//	discovery-agent [-config путь] run      обходы по расписанию (служба)
//	discovery-agent [-config путь] once     один обход (cron, таймер systemd); код 1 — пакет не принят
//	discovery-agent [-config путь] check    проверить адрес, сертификат и токен
//	discovery-agent [-config путь] print    собрать и показать объекты, ничего не отправляя
//	discovery-agent version
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"math/rand/v2"
	"net"
	"os"
	"os/signal"
	"runtime"
	"strings"
	"syscall"
	"time"

	"easyitsm.ru/discovery-agent/internal/cmdb"
	"easyitsm.ru/discovery-agent/internal/collect"
	"easyitsm.ru/discovery-agent/internal/config"
)

// version задаётся при сборке: -ldflags "-X main.version=1.0.0".
var version = "dev"

const (
	exitOK     = 0
	exitFailed = 1
	exitConfig = 2
)

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr))
}

func run(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("discovery-agent", flag.ContinueOnError)
	fs.SetOutput(stderr)
	path := fs.String("config", defaultConfig(), "файл настроек JSON")
	fs.Usage = func() {
		fmt.Fprintln(stderr, "Агент обнаружения Easy ITSM", version)
		fmt.Fprintln(stderr, "Использование: discovery-agent [-config файл] run|once|check|print|version")
		fs.PrintDefaults()
	}
	if err := fs.Parse(args); err != nil {
		return exitConfig
	}
	cmd := fs.Arg(0)
	if cmd == "version" {
		fmt.Fprintln(stdout, version)
		return exitOK
	}
	if cmd != "run" && cmd != "once" && cmd != "check" && cmd != "print" {
		fs.Usage()
		return exitConfig
	}
	cfg, err := config.Load(*path, cmd != "print")
	if err != nil {
		fmt.Fprintln(stderr, err)
		return exitConfig
	}
	log := logger(cfg, stderr)
	warnTokenFile(cfg, log)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if cmd == "print" {
		items, err := collectAll(ctx, cfg, log)
		if err != nil {
			log.Error("сбор не удался", "ошибка", err)
			return exitFailed
		}
		enc := json.NewEncoder(stdout)
		enc.SetIndent("", "  ")
		_ = enc.Encode(items)
		return exitOK
	}

	client, err := cmdb.New(cfg.Server, cfg.Token, cmdb.Options{
		CAFile:    cfg.CAFile,
		AllowHTTP: cfg.AllowHTTP,
		UserAgent: "easyitsm-discovery-agent/" + version,
		Logger:    log,
	})
	if err != nil {
		fmt.Fprintln(stderr, err)
		return exitConfig
	}

	switch cmd {
	case "check":
		src, err := client.Source(ctx)
		if err != nil {
			log.Error("проверка не прошла", "ошибка", err)
			return exitFailed
		}
		fmt.Fprintf(stdout, "Источник %s «%s»: вид %s, приоритет %d, принимает данные: %s\n",
			src.Code, src.Name, src.Kind, src.Priority, yesNo(src.Active))
		if !src.Active {
			return exitFailed
		}
		return exitOK
	case "once":
		if err := pass(ctx, cfg, client, log); err != nil {
			return exitFailed
		}
		return exitOK
	default:
		log.Info("агент запущен", "версия", version, "сервер", cfg.Server, "интервал", cfg.Interval.String())
		for {
			_ = pass(ctx, cfg, client, log)
			// Разброс ±10 %: агенты на сотнях серверов не приходят к системе одновременно.
			wait := cfg.Interval.Duration + time.Duration(rand.Int64N(int64(cfg.Interval.Duration)/5+1)) -
				cfg.Interval.Duration/10
			log.Info("следующий обход", "через", wait.Round(time.Second).String())
			select {
			case <-ctx.Done():
				log.Info("агент остановлен")
				return exitOK
			case <-time.After(wait):
			}
		}
	}
}

// pass — один обход: собрать объекты и загрузить пакетами.
func pass(ctx context.Context, cfg config.Config, client *cmdb.Client, log *slog.Logger) error {
	started := time.Now()
	items, err := collectAll(ctx, cfg, log)
	if err != nil {
		log.Error("сбор не удался", "ошибка", err)
		return err
	}
	id := cmdb.SafeID(agentID(cfg), 120)
	if id == "" {
		id = "agent"
	}
	runID := id + "-" + started.UTC().Format("20060102T150405Z")
	sum, err := cmdb.Upload(ctx, client, runID, items, cfg.Scan.Enabled && cfg.Scan.FullSweep, log)
	for _, f := range sum.Failed {
		log.Warn("объект не принят", "externalId", f.ExternalID, "код", f.Code, "поле", f.Field)
	}
	if err != nil {
		log.Error("обход не загружен", "обход", runID, "ошибка", err)
		return err
	}
	log.Info("обход загружен", "обход", runID, "объектов", len(items), "пакетов", sum.Batches,
		"создано", sum.Created, "изменено", sum.Updated, "без изменений", sum.Unchanged,
		"конфликтов", sum.Conflicts, "ошибок", sum.Errors, "полный", sum.Final,
		"за", time.Since(started).Round(time.Millisecond).String())
	return nil
}

func collectAll(ctx context.Context, cfg config.Config, log *slog.Logger) ([]cmdb.Item, error) {
	var items []cmdb.Item
	if cfg.HostEnabled() {
		f := collect.ReadHostFacts()
		f.IP = collect.PrimaryIP(serverAddr(cfg.Server))
		if !strings.Contains(f.Hostname, ".") {
			lctx, cancel := context.WithTimeout(ctx, 3*time.Second)
			f.FQDN = collect.LookupFQDN(lctx, f.Hostname, f.IP)
			cancel()
		}
		hostItems := f.Items(collect.HostOptions{
			Environment:   cfg.Environment,
			Domain:        cfg.Host.Domain,
			Databases:     cfg.Databases(),
			DatabasePorts: cfg.DatabasePorts(),
		})
		log.Info("сервер", "класс", hostItems[0].ClassCode, "имя", hostItems[0].Name, "баз данных", len(hostItems)-1)
		items = append(items, hostItems...)
	}
	if cfg.Scan.Enabled {
		nets := make([]collect.Network, len(cfg.Scan.Networks))
		for i, n := range cfg.Scan.Networks {
			nets[i] = collect.Network{CIDR: n.CIDR, ClassCode: n.ClassCode, Environment: n.Environment}
		}
		started := time.Now()
		found, err := collect.Scan(ctx, collect.ScanOptions{
			Networks:    nets,
			Exclude:     cfg.Scan.Exclude,
			Ports:       cfg.Scan.Ports,
			Timeout:     cfg.Scan.Timeout.Duration,
			Concurrency: cfg.Scan.Concurrency,
			MaxHosts:    cfg.Scan.MaxHosts,
			ReverseDNS:  cfg.ReverseDNS(),
		})
		if err != nil {
			return nil, fmt.Errorf("обход сети: %w", err)
		}
		log.Info("обход сети", "узлов", len(found), "за", time.Since(started).Round(time.Millisecond).String())
		items = append(items, found...)
	}
	return items, nil
}

func agentID(cfg config.Config) string {
	if cfg.AgentID != "" {
		return cfg.AgentID
	}
	h, _ := os.Hostname()
	if i := strings.IndexByte(h, '.'); i > 0 {
		h = h[:i]
	}
	if h == "" {
		h = "agent"
	}
	return h
}

// serverAddr — host:port сервера для выбора исходного адреса.
func serverAddr(server string) string {
	s := strings.TrimSpace(server)
	scheme, rest, ok := strings.Cut(s, "://")
	if !ok {
		return ""
	}
	host, _, _ := strings.Cut(rest, "/")
	if _, _, err := net.SplitHostPort(host); err == nil {
		return host
	}
	port := "443"
	if scheme == "http" {
		port = "80"
	}
	return net.JoinHostPort(strings.Trim(host, "[]"), port)
}

func logger(cfg config.Config, w io.Writer) *slog.Logger {
	level := slog.LevelInfo
	switch cfg.LogLevel {
	case "debug":
		level = slog.LevelDebug
	case "warn":
		level = slog.LevelWarn
	case "error":
		level = slog.LevelError
	}
	opts := &slog.HandlerOptions{Level: level}
	if cfg.LogFormat == "json" {
		return slog.New(slog.NewJSONHandler(w, opts))
	}
	return slog.New(slog.NewTextHandler(w, opts))
}

// warnTokenFile предупреждает, если файл токена читают другие пользователи.
func warnTokenFile(cfg config.Config, log *slog.Logger) {
	if cfg.TokenFile == "" || runtime.GOOS == "windows" {
		return
	}
	st, err := os.Stat(cfg.TokenFile)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			log.Warn("файл токена недоступен", "ошибка", err)
		}
		return
	}
	if st.Mode().Perm()&0o077 != 0 {
		log.Warn("файл токена доступен другим пользователям, выполните chmod 600", "файл", cfg.TokenFile)
	}
}

func defaultConfig() string {
	if runtime.GOOS == "windows" {
		return `C:\ProgramData\EasyITSM\discovery.json`
	}
	return "/etc/easyitsm/discovery.json"
}

func yesNo(b bool) string {
	if b {
		return "да"
	}
	return "нет"
}
