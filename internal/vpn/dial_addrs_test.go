package vpn

import (
	"testing"

	"dev.c0redev.volter/internal/config"
)

func TestReorderPrimaryFirst(t *testing.T) {
	addrs := []string{"a:1", "b:2", "c:3"}
	out := reorderPrimaryFirst(addrs, "b:2")
	if len(out) != 3 || out[0] != "b:2" {
		t.Fatalf("%v", out)
	}
}

func TestDialServerAddrs_WithPreference(t *testing.T) {
	t.Cleanup(func() { SetClusterDialPreference("") })
	SetClusterDialPreference("c:3")
	out := dialServerAddrs([]string{"a:1", "b:2", "c:3"}, &config.ProtectionOptions{})
	if out[0] != "c:3" {
		t.Fatalf("%v", out)
	}
}
