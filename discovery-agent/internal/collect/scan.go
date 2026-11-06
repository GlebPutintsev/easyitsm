package collect

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"easyitsm.ru/discovery-agent/internal/cmdb"
)

// Network — сеть для обхода и класс найденных в ней узлов.
type Network struct {
	CIDR        string
	ClassCode   string
	Environment string
}

// ScanClasses — классы узлов сети и атрибуты, которые обход может заполнить.
var ScanClasses = map[string][]string{
	"server":         {"hostname", "domain", "fqdn", "ip_address"},
	"network_device": {"hostname", "ip_address"},
	"workstation":    {"hostname"},
}

// DefaultScanPorts — порты, по которым узел считается живым: SSH, HTTP(S), RDP, SMB, WinRM, SNMP-шлюзы и т. п.
var DefaultScanPorts = []int{22, 80, 135, 443, 445, 3389, 5985, 8080, 8443}

// ScanOptions — настройки обхода сети.
type ScanOptions struct {
	Networks    []Network
	Exclude     []string
	Ports       []int
	Timeout     time.Duration
	Concurrency int
	MaxHosts    int
	ReverseDNS  bool

	// Dial и LookupAddr подменяются в тестах.
	Dial       func(ctx context.Context, network, addr string) (net.Conn, error)
	LookupAddr func(ctx context.Context, addr string) ([]string, error)
}

// Target — адрес для обхода.
type target struct {
	addr    netip.Addr
	network Network
}

// Hosts раскрывает сети в список адресов без исключённых, без адреса сети и широковещательного.
// Обход ограничен MaxHosts: большая сеть по ошибке в настройке не должна нагрузить чужую инфраструктуру.
func Hosts(opts ScanOptions) ([]netip.Addr, error) {
	ts, err := targets(opts)
	if err != nil {
		return nil, err
	}
	out := make([]netip.Addr, len(ts))
	for i, t := range ts {
		out[i] = t.addr
	}
	return out, nil
}

func targets(opts ScanOptions) ([]target, error) {
	excluded := map[netip.Addr]bool{}
	var excludedNets []netip.Prefix
	for _, e := range opts.Exclude {
		e = strings.TrimSpace(e)
		if p, err := netip.ParsePrefix(e); err == nil {
			excludedNets = append(excludedNets, p.Masked())
			continue
		}
		a, err := netip.ParseAddr(e)
		if err != nil {
			return nil, fmt.Errorf("исключение %q: нужен адрес или сеть", e)
		}
		excluded[a] = true
	}
	limit := opts.MaxHosts
	if limit <= 0 {
		limit = 4096
	}
	seen := map[netip.Addr]bool{}
	var out []target
	for _, n := range opts.Networks {
		p, err := netip.ParsePrefix(strings.TrimSpace(n.CIDR))
		if err != nil {
			return nil, fmt.Errorf("сеть %q: нужен вид 10.0.0.0/24", n.CIDR)
		}
		if !p.Addr().Is4() {
			return nil, fmt.Errorf("сеть %q: обход поддерживает только IPv4", n.CIDR)
		}
		p = p.Masked()
		size := 1 << (32 - p.Bits())
		if size > limit {
			return nil, fmt.Errorf("сеть %s: %d адресов больше лимита maxHosts %d", p, size, limit)
		}
		first, last := p.Addr(), lastAddr(p)
		for a := first; p.Contains(a); a = a.Next() {
			if p.Bits() <= 30 && (a == first || a == last) {
				continue
			}
			if seen[a] || excluded[a] || inAny(excludedNets, a) {
				continue
			}
			seen[a] = true
			out = append(out, target{addr: a, network: n})
			if len(out) > limit {
				return nil, fmt.Errorf("в сетях обхода больше %d адресов (maxHosts)", limit)
			}
			if a == last {
				break
			}
		}
	}
	return out, nil
}

func lastAddr(p netip.Prefix) netip.Addr {
	b := p.Addr().As4()
	host := uint32(1)<<(32-p.Bits()) - 1
	v := (uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3])) | host
	return netip.AddrFrom4([4]byte{byte(v >> 24), byte(v >> 16), byte(v >> 8), byte(v)})
}

func inAny(nets []netip.Prefix, a netip.Addr) bool {
	for _, n := range nets {
		if n.Contains(a) {
			return true
		}
	}
	return false
}

// Scan обходит сети TCP-подключениями: узел живой, если хоть один порт принял соединение или отказал
// (RST). Найденные узлы — объекты CMDB с ключом ip:<адрес>, именем из обратной зоны DNS или адресом.
func Scan(ctx context.Context, opts ScanOptions) ([]cmdb.Item, error) {
	ts, err := targets(opts)
	if err != nil {
		return nil, err
	}
	ports := opts.Ports
	if len(ports) == 0 {
		ports = DefaultScanPorts
	}
	timeout := opts.Timeout
	if timeout <= 0 {
		timeout = 500 * time.Millisecond
	}
	workers := opts.Concurrency
	if workers <= 0 {
		workers = 64
	}
	dial := opts.Dial
	if dial == nil {
		d := &net.Dialer{}
		dial = d.DialContext
	}
	lookup := opts.LookupAddr
	if lookup == nil {
		lookup = net.DefaultResolver.LookupAddr
	}

	var (
		mu    sync.Mutex
		items []cmdb.Item
		wg    sync.WaitGroup
	)
	jobs := make(chan target)
	for range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for t := range jobs {
				if !alive(ctx, dial, t.addr, ports, timeout) {
					continue
				}
				var name string
				if opts.ReverseDNS {
					lctx, cancel := context.WithTimeout(ctx, 2*time.Second)
					if names, err := lookup(lctx, t.addr.String()); err == nil && len(names) > 0 {
						name = strings.ToLower(strings.TrimSuffix(names[0], "."))
					}
					cancel()
				}
				it := scanItem(t, name)
				mu.Lock()
				items = append(items, it)
				mu.Unlock()
			}
		}()
	}
feed:
	for _, t := range ts {
		select {
		case <-ctx.Done():
			break feed
		case jobs <- t:
		}
	}
	close(jobs)
	wg.Wait()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	sort.Slice(items, func(i, j int) bool {
		a, _ := netip.ParseAddr(strings.TrimPrefix(items[i].ExternalID, "ip:"))
		b, _ := netip.ParseAddr(strings.TrimPrefix(items[j].ExternalID, "ip:"))
		return a.Less(b)
	})
	return items, nil
}

func alive(ctx context.Context, dial func(context.Context, string, string) (net.Conn, error), a netip.Addr,
	ports []int, timeout time.Duration) bool {
	for _, p := range ports {
		dctx, cancel := context.WithTimeout(ctx, timeout)
		conn, err := dial(dctx, "tcp", net.JoinHostPort(a.String(), strconv.Itoa(p)))
		cancel()
		if err == nil {
			_ = conn.Close()
			return true
		}
		if errors.Is(err, syscall.ECONNREFUSED) {
			return true
		}
		if ctx.Err() != nil {
			return false
		}
	}
	return false
}

func scanItem(t target, dnsName string) cmdb.Item {
	class := t.network.ClassCode
	if class == "" {
		class = "server"
	}
	ip := t.addr.String()
	short, domain, fqdn := "", "", ""
	if dnsName != "" {
		short, domain, fqdn = names(dnsName, "", "")
	}
	values := map[string]string{"hostname": short, "domain": domain, "fqdn": fqdn, "ip_address": ip}
	attrs := map[string]any{}
	for _, a := range ScanClasses[class] {
		put(attrs, a, values[a])
	}
	name := short
	if name == "" {
		name = ip
	}
	return cmdb.Item{
		ExternalID:  "ip:" + ip,
		ClassCode:   class,
		Name:        name,
		Status:      "active",
		Environment: t.network.Environment,
		Attributes:  attrs,
	}
}
