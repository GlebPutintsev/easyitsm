package collect

import (
	"bufio"
	"bytes"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// ReadLinuxFacts читает сведения о сервере Linux из файлов под root ("/" на сервере, каталог testdata в
// тестах): /etc/os-release, /proc/meminfo, /proc/cpuinfo, /sys/class/dmi/id, /etc/machine-id,
// /proc/net/tcp{,6}. Серийный номер и UUID платформы читаются только под root — без них ВМ опознаётся
// по гипервизору, а сервер по FQDN.
func ReadLinuxFacts(root string) HostFacts {
	var f HostFacts
	osr := keyValues(read(root, "etc/os-release"))
	if len(osr) == 0 {
		osr = keyValues(read(root, "usr/lib/os-release"))
	}
	f.OS = osr["NAME"]
	f.OSVersion = osr["VERSION_ID"]
	if f.OSVersion == "" {
		f.OSVersion = osr["VERSION"]
	}
	if kb := memTotalKB(read(root, "proc/meminfo")); kb > 0 {
		f.RAMGB = round1(float64(kb) / 1024 / 1024)
	}
	f.MachineID = strings.ToLower(strings.TrimSpace(string(read(root, "etc/machine-id"))))
	if len(f.MachineID) != 32 {
		f.MachineID = ""
	}

	dmi := func(name string) string { return clean(string(read(root, "sys/class/dmi/id/"+name))) }
	f.Vendor = dmi("sys_vendor")
	f.Model = dmi("product_name")
	f.Serial = dmi("product_serial")
	if f.Serial == "" {
		f.Serial = dmi("board_serial")
	}
	uuid := strings.ToLower(dmi("product_uuid"))
	f.Hardware = f.Vendor != "" || f.Model != ""
	f.Hypervisor = hypervisor(f.Vendor, f.Model, dmi("bios_vendor"), dmi("chassis_vendor"))
	f.Virtual = f.Hypervisor != "" || cpuHypervisor(read(root, "proc/cpuinfo"))
	if f.Virtual {
		f.VMUUID = uuid
	}
	f.ListenPorts = append(listenPorts(read(root, "proc/net/tcp")), listenPorts(read(root, "proc/net/tcp6"))...)
	return f
}

func read(root, rel string) []byte {
	data, err := os.ReadFile(filepath.Join(root, rel))
	if err != nil {
		return nil
	}
	return data
}

// keyValues разбирает файл вида KEY="значение" (os-release).
func keyValues(data []byte) map[string]string {
	out := map[string]string{}
	sc := bufio.NewScanner(bytes.NewReader(data))
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		if uq, err := strconv.Unquote(v); err == nil {
			v = uq
		} else {
			v = strings.Trim(v, `"'`)
		}
		out[strings.TrimSpace(k)] = strings.TrimSpace(v)
	}
	return out
}

func memTotalKB(data []byte) int64 {
	sc := bufio.NewScanner(bytes.NewReader(data))
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) >= 2 && fields[0] == "MemTotal:" {
			n, _ := strconv.ParseInt(fields[1], 10, 64)
			return n
		}
	}
	return 0
}

func cpuHypervisor(data []byte) bool {
	sc := bufio.NewScanner(bytes.NewReader(data))
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		line := sc.Text()
		if strings.HasPrefix(line, "flags") {
			for _, flag := range strings.Fields(line) {
				if flag == "hypervisor" {
					return true
				}
			}
			return false
		}
	}
	return false
}

// junk — заглушки производителей вместо серийного номера и названия.
var junk = []string{"to be filled by o.e.m.", "not specified", "not applicable", "default string", "none",
	"system serial number", "system product name", "system manufacturer", "0", "00000000", "123456789", "n/a",
	"chassis serial number", "invalid", "unknown"}

func clean(s string) string {
	s = strings.TrimSpace(s)
	l := strings.ToLower(s)
	for _, j := range junk {
		if l == j {
			return ""
		}
	}
	if strings.Trim(l, "0-") == "" || strings.Trim(l, "f-") == "" {
		return ""
	}
	return s
}

// hypervisor по строкам DMI; пусто — физический сервер (или гипервизор не распознан).
func hypervisor(values ...string) string {
	all := strings.ToLower(strings.Join(values, " | "))
	switch {
	case strings.Contains(all, "vmware"):
		return "VMware"
	case strings.Contains(all, "virtualbox"), strings.Contains(all, "innotek"):
		return "VirtualBox"
	case strings.Contains(all, "microsoft corporation") && strings.Contains(all, "virtual machine"):
		return "Hyper-V"
	case strings.Contains(all, "xen"):
		return "Xen"
	case strings.Contains(all, "openstack"):
		return "OpenStack"
	case strings.Contains(all, "ovirt"), strings.Contains(all, "rhev"):
		return "oVirt"
	case strings.Contains(all, "nutanix"):
		return "Nutanix AHV"
	case strings.Contains(all, "parallels"):
		return "Parallels"
	case strings.Contains(all, "yandex"):
		return "Yandex Cloud"
	case strings.Contains(all, "amazon ec2"):
		return "Amazon EC2"
	case strings.Contains(all, "google compute engine"):
		return "Google Compute Engine"
	case strings.Contains(all, "kvm"), strings.Contains(all, "qemu"), strings.Contains(all, "bochs"):
		return "KVM"
	}
	return ""
}

// listenPorts — порты в состоянии LISTEN (0A) из /proc/net/tcp.
func listenPorts(data []byte) []int {
	var ports []int
	sc := bufio.NewScanner(bytes.NewReader(data))
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	first := true
	for sc.Scan() {
		if first {
			first = false
			continue
		}
		fields := strings.Fields(sc.Text())
		if len(fields) < 4 || fields[3] != "0A" {
			continue
		}
		_, portHex, ok := strings.Cut(fields[1], ":")
		if !ok {
			continue
		}
		if p, err := strconv.ParseUint(portHex, 16, 16); err == nil && p > 0 {
			ports = append(ports, int(p))
		}
	}
	return ports
}
