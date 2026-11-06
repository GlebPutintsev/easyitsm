//go:build linux

package collect

import (
	"os"
	"runtime"
)

// ReadHostFacts — сведения о сервере, на котором работает агент.
func ReadHostFacts() HostFacts {
	f := ReadLinuxFacts("/")
	f.Hostname, _ = os.Hostname()
	f.CPUCount = runtime.NumCPU()
	return f
}
