// Package config читает настройки агента из JSON-файла (по умолчанию /etc/easyitsm/discovery.json).
// Токен в файле настроек не хранится: только в отдельном файле tokenFile (права 600) или в переменной
// окружения EASYITSM_DISCOVERY_TOKEN.
package config

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Переменные окружения переопределяют файл: удобно в контейнере и в systemd.
const (
	EnvServer = "EASYITSM_URL"
	EnvToken  = "EASYITSM_DISCOVERY_TOKEN"
)

// Duration — длительность строкой: "6h", "30m", "500ms".
type Duration struct{ time.Duration }

func (d *Duration) UnmarshalJSON(b []byte) error {
	var s string
	if err := json.Unmarshal(b, &s); err != nil {
		return errors.New("длительность пишется строкой, например \"6h\"")
	}
	v, err := time.ParseDuration(s)
	if err != nil {
		return fmt.Errorf("длительность %q: %w", s, err)
	}
	d.Duration = v
	return nil
}

func (d Duration) MarshalJSON() ([]byte, error) {
	return json.Marshal(d.String())
}

// Config — настройки агента.
type Config struct {
	// Server — адрес системы: https://itsm.example.ru.
	Server    string `json:"server"`
	TokenFile string `json:"tokenFile"`
	// CAFile — сертификат УЦ заказчика (PEM), если сертификат системы выпущен внутренним УЦ.
	CAFile    string `json:"caFile"`
	AllowHTTP bool   `json:"allowHttp"`
	// AgentID — имя агента в номерах пакетов; по умолчанию имя сервера.
	AgentID string `json:"agentId"`
	// Interval — пауза между обходами в режиме run.
	Interval    Duration `json:"interval"`
	Environment string   `json:"environment"`
	LogFormat   string   `json:"logFormat"`
	LogLevel    string   `json:"logLevel"`
	Host        Host     `json:"host"`
	Scan        Scan     `json:"scan"`

	// Token — из tokenFile или EASYITSM_DISCOVERY_TOKEN, в файл настроек не пишется.
	Token string `json:"-"`
}

// Host — сведения о сервере, на котором работает агент.
type Host struct {
	Enabled *bool `json:"enabled"`
	// Domain — домен, если сервер не знает своего FQDN.
	Domain    string `json:"domain"`
	Databases *bool  `json:"databases"`
	// DatabasePorts — порт → СУБД: дополняет стандартные, пустая строка отключает порт.
	DatabasePorts map[string]string `json:"databasePorts"`
}

// Scan — обход сетей.
type Scan struct {
	Enabled bool `json:"enabled"`
	// FullSweep — обход полный: узлы источника, не найденные в обходе, CMDB отметит неактуальными.
	// Включайте, только если этот агент — единственный, кто шлёт данные в источник.
	FullSweep   bool      `json:"fullSweep"`
	Networks    []Network `json:"networks"`
	Exclude     []string  `json:"exclude"`
	Ports       []int     `json:"ports"`
	Timeout     Duration  `json:"timeout"`
	Concurrency int       `json:"concurrency"`
	MaxHosts    int       `json:"maxHosts"`
	ReverseDNS  *bool     `json:"reverseDns"`
}

// Network — сеть обхода.
type Network struct {
	CIDR        string `json:"cidr"`
	ClassCode   string `json:"classCode"`
	Environment string `json:"environment"`
}

// HostEnabled — сбор о сервере включён (по умолчанию да).
func (c Config) HostEnabled() bool { return c.Host.Enabled == nil || *c.Host.Enabled }

// Databases — заводить базы данных по слушаемым портам (по умолчанию да).
func (c Config) Databases() bool { return c.Host.Databases == nil || *c.Host.Databases }

// ReverseDNS — искать имена узлов в обратной зоне (по умолчанию да).
func (c Config) ReverseDNS() bool { return c.Scan.ReverseDNS == nil || *c.Scan.ReverseDNS }

// DatabasePorts — порты СУБД из настроек.
func (c Config) DatabasePorts() map[int]string {
	out := map[int]string{}
	for k, v := range c.Host.DatabasePorts {
		p, _ := strconv.Atoi(k)
		out[p] = v
	}
	return out
}

// Load читает файл, применяет переменные окружения и значения по умолчанию. needToken — команда
// обращается к серверу.
func Load(path string, needToken bool) (Config, error) {
	var c Config
	data, err := os.ReadFile(path)
	if err != nil {
		return c, fmt.Errorf("файл настроек: %w", err)
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&c); err != nil {
		return c, fmt.Errorf("файл настроек %s: %w", path, err)
	}
	if v := strings.TrimSpace(os.Getenv(EnvServer)); v != "" {
		c.Server = v
	}
	c.Token = strings.TrimSpace(os.Getenv(EnvToken))
	if c.Token == "" && c.TokenFile != "" && needToken {
		raw, err := os.ReadFile(c.TokenFile)
		if err != nil {
			return c, fmt.Errorf("файл токена: %w", err)
		}
		c.Token = strings.TrimSpace(string(raw))
	}
	c.defaults()
	return c, c.validate(needToken)
}

func (c *Config) defaults() {
	if c.Interval.Duration == 0 {
		c.Interval.Duration = 6 * time.Hour
	}
	if c.Environment == "" {
		c.Environment = "production"
	}
	if c.Scan.Timeout.Duration == 0 {
		c.Scan.Timeout.Duration = 500 * time.Millisecond
	}
	if c.Scan.Concurrency == 0 {
		c.Scan.Concurrency = 64
	}
	if c.Scan.MaxHosts == 0 {
		c.Scan.MaxHosts = 4096
	}
	for i := range c.Scan.Networks {
		if c.Scan.Networks[i].ClassCode == "" {
			c.Scan.Networks[i].ClassCode = "server"
		}
		if c.Scan.Networks[i].Environment == "" {
			c.Scan.Networks[i].Environment = c.Environment
		}
	}
}

// ScanClasses — классы, которые может заводить обход сети (collect.ScanClasses).
var ScanClasses = []string{"network_device", "server", "workstation"}

func (c Config) validate(needToken bool) error {
	var errs []string
	if needToken {
		if c.Server == "" {
			errs = append(errs, "не задан server (адрес системы) или "+EnvServer)
		}
		if c.Token == "" {
			errs = append(errs, "не задан токен: tokenFile или "+EnvToken)
		}
	}
	if !c.HostEnabled() && !c.Scan.Enabled {
		errs = append(errs, "выключены и host, и scan: агенту нечего собирать")
	}
	if c.Interval.Duration < time.Minute {
		errs = append(errs, "interval меньше минуты")
	}
	if c.Scan.Enabled && len(c.Scan.Networks) == 0 {
		errs = append(errs, "scan.enabled, но не заданы scan.networks")
	}
	if c.Scan.Concurrency < 1 || c.Scan.Concurrency > 1024 {
		errs = append(errs, "scan.concurrency — от 1 до 1024")
	}
	if c.Scan.MaxHosts < 1 || c.Scan.MaxHosts > 1<<20 {
		errs = append(errs, "scan.maxHosts — от 1 до 1048576")
	}
	for _, p := range c.Scan.Ports {
		if p < 1 || p > 65535 {
			errs = append(errs, fmt.Sprintf("scan.ports: порт %d вне 1–65535", p))
		}
	}
	for _, n := range c.Scan.Networks {
		ok := false
		for _, cls := range ScanClasses {
			ok = ok || n.ClassCode == cls
		}
		if !ok {
			errs = append(errs, fmt.Sprintf("scan.networks %s: класс %q, допустимы %s", n.CIDR, n.ClassCode,
				strings.Join(ScanClasses, ", ")))
		}
	}
	keys := make([]string, 0, len(c.Host.DatabasePorts))
	for k := range c.Host.DatabasePorts {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		if p, err := strconv.Atoi(k); err != nil || p < 1 || p > 65535 {
			errs = append(errs, fmt.Sprintf("host.databasePorts: %q — не порт", k))
		}
	}
	switch c.LogFormat {
	case "", "text", "json":
	default:
		errs = append(errs, "logFormat — text или json")
	}
	switch c.LogLevel {
	case "", "debug", "info", "warn", "error":
	default:
		errs = append(errs, "logLevel — debug, info, warn или error")
	}
	if len(errs) > 0 {
		return errors.New("настройки: " + strings.Join(errs, "; "))
	}
	return nil
}
