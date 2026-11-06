package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func write(t *testing.T, name, body string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(p, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestLoadDefaultsAndTokenFile(t *testing.T) {
	t.Setenv(EnvToken, "")
	t.Setenv(EnvServer, "")
	token := write(t, "token", "eitsm_cmdb_abc\n")
	p := write(t, "c.json", `{"server":"https://itsm.example.ru","tokenFile":"`+token+`",
		"scan":{"enabled":true,"networks":[{"cidr":"10.0.0.0/24"}],"timeout":"300ms"},
		"host":{"databasePorts":{"5433":"PostgreSQL","6379":""}}}`)
	c, err := Load(p, true)
	if err != nil {
		t.Fatal(err)
	}
	if c.Token != "eitsm_cmdb_abc" || c.Interval.Duration != 6*time.Hour || c.Environment != "production" {
		t.Fatalf("%+v", c)
	}
	if !c.HostEnabled() || !c.Databases() || !c.ReverseDNS() || c.Scan.Timeout.Duration != 300*time.Millisecond {
		t.Fatalf("%+v", c)
	}
	if n := c.Scan.Networks[0]; n.ClassCode != "server" || n.Environment != "production" {
		t.Fatalf("сеть %+v", n)
	}
	if ports := c.DatabasePorts(); ports[5433] != "PostgreSQL" || ports[6379] != "" || len(ports) != 2 {
		t.Fatalf("порты %v", ports)
	}
}

func TestEnvironmentOverridesFile(t *testing.T) {
	t.Setenv(EnvToken, "eitsm_cmdb_env")
	t.Setenv(EnvServer, "https://env.example.ru")
	c, err := Load(write(t, "c.json", `{"server":"https://file.example.ru","tokenFile":"/нет/файла"}`), true)
	if err != nil {
		t.Fatal(err)
	}
	if c.Server != "https://env.example.ru" || c.Token != "eitsm_cmdb_env" {
		t.Fatalf("%+v", c)
	}
}

func TestLoadRejectsMistakes(t *testing.T) {
	t.Setenv(EnvToken, "")
	t.Setenv(EnvServer, "")
	cases := map[string]string{
		`{"sever":"https://x"}`:      "unknown field",
		`{"interval":"10s"}`:         "interval меньше минуты",
		`{"interval":6}`:             "строкой",
		`{}`:                         "не задан токен",
		`{"host":{"enabled":false}}`: "нечего собирать",
		`{"scan":{"enabled":true}}`:  "не заданы scan.networks",
		`{"scan":{"enabled":true,"networks":[{"cidr":"10.0.0.0/24","classCode":"database"}]}}`: "допустимы",
		`{"scan":{"ports":[0]}}`:                 "вне 1–65535",
		`{"logFormat":"xml"}`:                    "logFormat",
		`{"host":{"databasePorts":{"abc":"X"}}}`: "не порт",
	}
	for body, want := range cases {
		_, err := Load(write(t, "c.json", body), true)
		if err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("%s: ждали %q, получили %v", body, want, err)
		}
	}
	// Для print токен и адрес не нужны.
	if _, err := Load(write(t, "c.json", `{}`), false); err != nil {
		t.Fatal(err)
	}
}
