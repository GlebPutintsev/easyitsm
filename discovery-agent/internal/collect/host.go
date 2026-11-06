// Package collect собирает объекты для CMDB: сведения о сервере, на котором работает агент, и обход сети.
// Агент шлёт только атрибуты стандартных классов CMDB (CmdbDefaults): заказчик может добавлять атрибуты
// к стандартным классам, но не удалять их, поэтому эти атрибуты есть всегда.
package collect

import (
	"context"
	"fmt"
	"net"
	"sort"
	"strconv"
	"strings"

	"easyitsm.ru/discovery-agent/internal/cmdb"
)

// HostFacts — сведения о сервере. Пустое поле не отправляется.
type HostFacts struct {
	Hostname   string
	FQDN       string
	OS         string
	OSVersion  string
	CPUCount   int
	RAMGB      float64
	Serial     string
	Vendor     string
	Model      string
	VMUUID     string
	Hypervisor string
	Virtual    bool
	// Hardware — сведения о платформе (DMI) прочитаны: можно отличить физический сервер от ВМ.
	Hardware  bool
	MachineID string
	IP        string
	// ListenPorts — TCP-порты, которые слушает сервер.
	ListenPorts []int
}

// HostOptions — настройки сбора о сервере.
type HostOptions struct {
	Environment string
	// Domain задаёт домен, если сервер не знает своего FQDN.
	Domain string
	// Databases — заводить КЕ баз данных по слушаемым портам.
	Databases bool
	// DatabasePorts — порт → СУБД, дополняет и переопределяет DefaultDatabasePorts.
	DatabasePorts map[int]string
}

// DefaultDatabasePorts — стандартные порты СУБД.
var DefaultDatabasePorts = map[int]string{
	1433:  "Microsoft SQL Server",
	1521:  "Oracle Database",
	3306:  "MySQL",
	5432:  "PostgreSQL",
	6379:  "Redis",
	8123:  "ClickHouse",
	9042:  "Cassandra",
	27017: "MongoDB",
	50000: "IBM Db2",
}

// HostExternalID — стабильный ключ сервера в источнике: machine-id переживает смену имени и адреса,
// без него — FQDN или имя.
func (f HostFacts) HostExternalID(domain string) string {
	if f.MachineID != "" {
		return "host:" + f.MachineID
	}
	short, _, fqdn := names(f.Hostname, f.FQDN, domain)
	if fqdn != "" {
		return "host:" + fqdn
	}
	return "host:" + short
}

// Items превращает сведения в объекты CMDB: сервер и, по желанию, базы данных на нём.
func (f HostFacts) Items(opts HostOptions) []cmdb.Item {
	short, domain, fqdn := names(f.Hostname, f.FQDN, opts.Domain)
	attrs := map[string]any{}
	put(attrs, "hostname", short)
	put(attrs, "domain", domain)
	put(attrs, "fqdn", fqdn)
	put(attrs, "ip_address", f.IP)
	put(attrs, "os", f.OS)
	put(attrs, "os_version", f.OSVersion)
	if f.CPUCount > 0 {
		attrs["cpu_count"] = f.CPUCount
	}
	if f.RAMGB > 0 {
		attrs["ram_gb"] = f.RAMGB
	}
	class := "server"
	switch {
	case f.Virtual:
		class = "virtual_machine"
		put(attrs, "vm_uuid", f.VMUUID)
		put(attrs, "hypervisor", f.Hypervisor)
	case f.Hardware:
		class = "physical_server"
		// Серийный номер только у физических: у клонированных ВМ он часто совпадает.
		put(attrs, "serial_number", f.Serial)
		put(attrs, "vendor", f.Vendor)
		put(attrs, "model", f.Model)
	}
	hostID := f.HostExternalID(opts.Domain)
	items := []cmdb.Item{{
		ExternalID:  hostID,
		ClassCode:   class,
		Name:        short,
		Status:      "active",
		Environment: opts.Environment,
		Attributes:  attrs,
	}}
	if !opts.Databases {
		return items
	}
	ports := map[int]string{}
	for p, e := range DefaultDatabasePorts {
		ports[p] = e
	}
	for p, e := range opts.DatabasePorts {
		if strings.TrimSpace(e) == "" {
			delete(ports, p)
		} else {
			ports[p] = e
		}
	}
	seen := map[int]bool{}
	listen := append([]int(nil), f.ListenPorts...)
	sort.Ints(listen)
	for _, p := range listen {
		engine, ok := ports[p]
		if !ok || seen[p] {
			continue
		}
		seen[p] = true
		items = append(items, cmdb.Item{
			ExternalID:  fmt.Sprintf("%s/db/%d", hostID, p),
			ClassCode:   "database",
			Name:        fmt.Sprintf("%s %s:%d", engine, short, p),
			Status:      "active",
			Environment: opts.Environment,
			Attributes:  map[string]any{"engine": engine, "port": p},
			Relations: []cmdb.Relation{{TypeCode: "runs_on", Direction: "outgoing",
				Target: cmdb.Target{ExternalID: hostID}}},
		})
	}
	return items
}

// names: короткое имя, домен и FQDN в нижнем регистре.
func names(hostname, fqdn, domain string) (string, string, string) {
	hostname = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(hostname), "."))
	fqdn = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(fqdn), "."))
	domain = strings.ToLower(strings.Trim(strings.TrimSpace(domain), "."))
	short := hostname
	if i := strings.IndexByte(hostname, '.'); i > 0 {
		short = hostname[:i]
		if fqdn == "" {
			fqdn = hostname
		}
	}
	if fqdn == "" && domain != "" && short != "" {
		fqdn = short + "." + domain
	}
	if fqdn != "" && !strings.Contains(fqdn, ".") {
		fqdn = ""
	}
	if fqdn != "" {
		if i := strings.IndexByte(fqdn, '.'); i > 0 {
			domain = fqdn[i+1:]
			if short == "" {
				short = fqdn[:i]
			}
		}
	}
	return short, domain, fqdn
}

func put(m map[string]any, k, v string) {
	if v = strings.TrimSpace(v); v != "" {
		m[k] = v
	}
}

// LookupFQDN ищет полное имя сервера в DNS: CNAME имени, затем обратная зона адреса.
func LookupFQDN(ctx context.Context, hostname, ip string) string {
	if strings.Contains(hostname, ".") {
		return hostname
	}
	r := net.DefaultResolver
	if cname, err := r.LookupCNAME(ctx, hostname); err == nil {
		if c := strings.TrimSuffix(cname, "."); strings.Contains(c, ".") {
			return c
		}
	}
	if ip != "" {
		if names, err := r.LookupAddr(ctx, ip); err == nil {
			for _, n := range names {
				n = strings.TrimSuffix(n, ".")
				if strings.HasPrefix(strings.ToLower(n), strings.ToLower(hostname)+".") {
					return n
				}
			}
		}
	}
	return ""
}

// PrimaryIP — адрес, с которого сервер ходит к системе: UDP-«соединение» не отправляет пакетов, но
// выбирает маршрут и исходный адрес.
func PrimaryIP(server string) string {
	if server != "" {
		if conn, err := net.Dial("udp", server); err == nil {
			defer conn.Close()
			if a, ok := conn.LocalAddr().(*net.UDPAddr); ok && !a.IP.IsLoopback() && !a.IP.IsUnspecified() {
				return a.IP.String()
			}
		}
	}
	ifaces, err := net.Interfaces()
	if err != nil {
		return ""
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, a := range addrs {
			if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil && n.IP.IsGlobalUnicast() {
				return n.IP.String()
			}
		}
	}
	return ""
}

// round1 — число с одним знаком после запятой.
func round1(v float64) float64 {
	r, _ := strconv.ParseFloat(strconv.FormatFloat(v, 'f', 1, 64), 64)
	return r
}
