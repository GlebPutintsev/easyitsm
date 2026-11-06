//go:build !linux

package collect

import (
	"os"
	"runtime"
)

// ReadHostFacts на Windows и macOS: имя, ОС и число процессоров. Платформа (ВМ или физический сервер),
// серийный номер и память пока собираются только на Linux.
func ReadHostFacts() HostFacts {
	var f HostFacts
	f.Hostname, _ = os.Hostname()
	f.CPUCount = runtime.NumCPU()
	switch runtime.GOOS {
	case "windows":
		f.OS = "Windows"
	case "darwin":
		f.OS = "macOS"
	default:
		f.OS = runtime.GOOS
	}
	return f
}
