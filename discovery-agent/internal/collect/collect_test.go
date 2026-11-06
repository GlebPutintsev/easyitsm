package collect

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"reflect"
	"strings"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"easyitsm.ru/discovery-agent/internal/cmdb"
)

func TestLinuxVirtualMachine(t *testing.T) {
	f := ReadLinuxFacts("testdata/vmware")
	if !f.Virtual || f.Hypervisor != "VMware" || f.VMUUID != "421c5e6f-7a8b-9c0d-1e2f-3a4b5c6d7e8f" {
		t.Fatalf("ВМ не опознана: %+v", f)
	}
	if f.OS != "Astra Linux" || f.OSVersion != "1.8_x86-64" || f.RAMGB != 15.5 {
		t.Fatalf("ОС и память: %+v", f)
	}
	if f.MachineID != "4c4c4544004b4d10804ab2c04f4b3132" {
		t.Fatalf("machine-id: %q", f.MachineID)
	}
	if !reflect.DeepEqual(f.ListenPorts, []int{5432, 6379, 22, 5432}) {
		t.Fatalf("порты: %v", f.ListenPorts)
	}

	f.Hostname, f.FQDN, f.IP, f.CPUCount = "app-01", "app-01.corp.local.", "10.0.0.10", 4
	items := f.Items(HostOptions{Environment: "production", Databases: true, DatabasePorts: map[int]string{6379: ""}})
	if len(items) != 2 {
		t.Fatalf("ждали сервер и PostgreSQL, получили %+v", items)
	}
	vm := items[0]
	want := map[string]any{"hostname": "app-01", "domain": "corp.local", "fqdn": "app-01.corp.local",
		"ip_address": "10.0.0.10", "os": "Astra Linux", "os_version": "1.8_x86-64", "cpu_count": 4, "ram_gb": 15.5,
		"vm_uuid": "421c5e6f-7a8b-9c0d-1e2f-3a4b5c6d7e8f", "hypervisor": "VMware"}
	if vm.ClassCode != "virtual_machine" || vm.Name != "app-01" || vm.ExternalID != "host:4c4c4544004b4d10804ab2c04f4b3132" ||
		!reflect.DeepEqual(vm.Attributes, want) || vm.Relations != nil {
		t.Fatalf("ВМ: %+v", vm)
	}
	db := items[1]
	if db.ClassCode != "database" || db.Name != "PostgreSQL app-01:5432" || db.ExternalID != vm.ExternalID+"/db/5432" ||
		db.Attributes["engine"] != "PostgreSQL" || db.Attributes["port"] != 5432 {
		t.Fatalf("база: %+v", db)
	}
	wantRel := []cmdb.Relation{{TypeCode: "runs_on", Direction: "outgoing", Target: cmdb.Target{ExternalID: vm.ExternalID}}}
	if !reflect.DeepEqual(db.Relations, wantRel) {
		t.Fatalf("связь базы: %+v", db.Relations)
	}
	if got := f.Items(HostOptions{}); len(got) != 1 {
		t.Fatalf("без баз: %d объектов", len(got))
	}
}

func TestLinuxPhysicalServer(t *testing.T) {
	f := ReadLinuxFacts("testdata/physical")
	if f.Virtual || !f.Hardware || f.Serial != "CN7016312A00XY" || f.MachineID != "" {
		t.Fatalf("физический сервер: %+v", f)
	}
	if f.OS != "RED OS" || f.OSVersion != "7.3.4 (МУРОМ)" || f.RAMGB != 251.6 {
		t.Fatalf("ОС и память: %+v", f)
	}
	f.Hostname = "DB-01"
	items := f.Items(HostOptions{Domain: "Corp.Local", Databases: true})
	srv := items[0]
	if len(items) != 1 || srv.ClassCode != "physical_server" || srv.ExternalID != "host:db-01.corp.local" {
		t.Fatalf("сервер: %+v", items)
	}
	for k, v := range map[string]any{"serial_number": "CN7016312A00XY", "vendor": "Dell Inc.", "model": "PowerEdge R750",
		"fqdn": "db-01.corp.local", "domain": "corp.local", "hostname": "db-01"} {
		if srv.Attributes[k] != v {
			t.Fatalf("атрибут %s = %v, ждали %v", k, srv.Attributes[k], v)
		}
	}
	if _, ok := srv.Attributes["vm_uuid"]; ok {
		t.Fatal("vm_uuid у физического сервера")
	}
}

func TestUnknownPlatformIsServer(t *testing.T) {
	f := ReadLinuxFacts("testdata/нет")
	f.Hostname = "box"
	items := f.Items(HostOptions{})
	if items[0].ClassCode != "server" || items[0].ExternalID != "host:box" || len(items[0].Attributes) != 1 {
		t.Fatalf("%+v", items[0])
	}
}

func TestNames(t *testing.T) {
	cases := []struct{ host, fqdn, domain, short, dom, full string }{
		{"app-01", "", "", "app-01", "", ""},
		{"app-01.corp.local", "", "", "app-01", "corp.local", "app-01.corp.local"},
		{"app-01", "", "corp.local", "app-01", "corp.local", "app-01.corp.local"},
		{"app-01", "app-01.dc.corp.local.", "corp.local", "app-01", "dc.corp.local", "app-01.dc.corp.local"},
		{"", "web.corp.local", "", "web", "corp.local", "web.corp.local"},
	}
	for _, c := range cases {
		s, d, f := names(c.host, c.fqdn, c.domain)
		if s != c.short || d != c.dom || f != c.full {
			t.Errorf("names(%q, %q, %q) = %q %q %q", c.host, c.fqdn, c.domain, s, d, f)
		}
	}
}

func TestClean(t *testing.T) {
	for _, v := range []string{"To Be Filled By O.E.M.", " Not Specified ", "0", "00000000-0000-0000-0000-000000000000",
		"FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF", "Default string"} {
		if clean(v) != "" {
			t.Errorf("clean(%q) = %q", v, clean(v))
		}
	}
	if clean(" SN123 ") != "SN123" {
		t.Error("серийный номер испорчен")
	}
}

func TestHypervisor(t *testing.T) {
	cases := map[string][]string{
		"KVM":          {"QEMU", "Standard PC (Q35 + ICH9, 2009)"},
		"Hyper-V":      {"Microsoft Corporation", "Virtual Machine"},
		"OpenStack":    {"OpenStack Foundation", "OpenStack Nova"},
		"VirtualBox":   {"innotek GmbH", "VirtualBox"},
		"":             {"HPE", "ProLiant DL380 Gen10"},
		"Yandex Cloud": {"Yandex", "xeon-gold-6230"},
	}
	for want, in := range cases {
		if got := hypervisor(in...); got != want {
			t.Errorf("hypervisor(%v) = %q, ждали %q", in, got, want)
		}
	}
}

func TestHostsExpandsNetworks(t *testing.T) {
	hosts, err := Hosts(ScanOptions{
		Networks: []Network{{CIDR: "10.0.0.0/29"}, {CIDR: "10.0.0.5/32"}, {CIDR: "10.0.1.0/31"}},
		Exclude:  []string{"10.0.0.2", "10.0.0.4/31"},
	})
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, h := range hosts {
		got = append(got, h.String())
	}
	if strings.Join(got, ",") != "10.0.0.1,10.0.0.3,10.0.0.6,10.0.1.0,10.0.1.1" {
		t.Fatalf("адреса: %v", got)
	}
	if _, err := Hosts(ScanOptions{Networks: []Network{{CIDR: "10.0.0.0/16"}}, MaxHosts: 1024}); err == nil {
		t.Fatal("сеть больше лимита")
	}
	if _, err := Hosts(ScanOptions{Networks: []Network{{CIDR: "fd00::/120"}}}); err == nil {
		t.Fatal("IPv6")
	}
	if _, err := Hosts(ScanOptions{Networks: []Network{{CIDR: "10.0.0.0"}}}); err == nil {
		t.Fatal("сеть без маски")
	}
	if _, err := Hosts(ScanOptions{Networks: []Network{{CIDR: "255.255.255.255/32"}}}); err != nil {
		t.Fatal(err)
	}
}

type fakeConn struct{ net.Conn }

func (fakeConn) Close() error { return nil }

func TestScanFindsLiveHosts(t *testing.T) {
	var dials atomic.Int32
	dial := func(ctx context.Context, _, addr string) (net.Conn, error) {
		dials.Add(1)
		host, port, _ := net.SplitHostPort(addr)
		switch {
		case host == "10.0.0.1" && port == "22":
			return fakeConn{}, nil
		case host == "10.0.0.2" && port == "443":
			return nil, &net.OpError{Op: "dial", Err: syscall.ECONNREFUSED}
		case host == "10.0.0.3":
			return nil, &net.OpError{Op: "dial", Err: errors.New("i/o timeout")}
		}
		return nil, errors.New("no route")
	}
	lookup := func(_ context.Context, addr string) ([]string, error) {
		if addr == "10.0.0.1" {
			return []string{"Core-SW1.Corp.Local."}, nil
		}
		return nil, errors.New("NXDOMAIN")
	}
	items, err := Scan(context.Background(), ScanOptions{
		Networks:   []Network{{CIDR: "10.0.0.0/30", ClassCode: "network_device", Environment: "production"}, {CIDR: "10.0.0.2/31"}},
		Ports:      []int{22, 443},
		Timeout:    time.Second,
		ReverseDNS: true,
		Dial:       dial,
		LookupAddr: lookup,
	})
	if err != nil {
		t.Fatal(err)
	}
	want := []cmdb.Item{
		{ExternalID: "ip:10.0.0.1", ClassCode: "network_device", Name: "core-sw1", Status: "active", Environment: "production",
			Attributes: map[string]any{"hostname": "core-sw1", "ip_address": "10.0.0.1"}},
		{ExternalID: "ip:10.0.0.2", ClassCode: "network_device", Name: "10.0.0.2", Status: "active", Environment: "production",
			Attributes: map[string]any{"ip_address": "10.0.0.2"}},
	}
	if !reflect.DeepEqual(items, want) {
		t.Fatalf("узлы:\n%+v\nждали\n%+v", items, want)
	}
	// 10.0.0.1 — первый порт открыт, дальше не проверяем; 10.0.0.2 и 10.0.0.3 — оба порта.
	if dials.Load() != 5 {
		t.Fatalf("подключений %d", dials.Load())
	}
}

func TestScanServerAttributes(t *testing.T) {
	it := scanItem(target{network: Network{ClassCode: "server"}, addr: mustAddr("192.168.1.7")}, "web-01.corp.local")
	want := map[string]any{"hostname": "web-01", "domain": "corp.local", "fqdn": "web-01.corp.local", "ip_address": "192.168.1.7"}
	if !reflect.DeepEqual(it.Attributes, want) || it.Name != "web-01" {
		t.Fatalf("%+v", it)
	}
	ws := scanItem(target{network: Network{ClassCode: "workstation"}, addr: mustAddr("192.168.1.8")}, "")
	if len(ws.Attributes) != 0 || ws.Name != "192.168.1.8" {
		t.Fatalf("%+v", ws)
	}
}

func TestScanStopsOnCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := Scan(ctx, ScanOptions{Networks: []Network{{CIDR: "10.0.0.0/24"}},
		Dial: func(context.Context, string, string) (net.Conn, error) { return nil, errors.New("x") }})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("ошибка %v", err)
	}
}

func mustAddr(s string) netip.Addr {
	return netip.MustParseAddr(s)
}
