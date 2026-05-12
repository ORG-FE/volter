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

func TestOrderedServerAddrs_IgnoresClusterPreferred(t *testing.T) {
	addrs := []string{"de.example:443", "ru.example:443"}
	out := orderedServerAddrs(addrs, &config.ProtectionOptions{ClusterPreferredServer: "ru.example:443"})
	if len(out) != 2 || out[0] != addrs[0] {
		t.Fatalf("profile order must stay (entry first); got %v", out)
	}
}

func TestDialServerAddrs_IgnoresPreferenceWhenClusterExit(t *testing.T) {
	t.Cleanup(func() { SetClusterDialPreference("") })
	SetClusterDialPreference("ru.example:443")
	out := dialServerAddrs([]string{"de.example:443", "ru.example:443"}, &config.ProtectionOptions{
		ClusterPreferredServer: "ru.example:443",
	})
	if len(out) != 1 || out[0] != "de.example:443" {
		t.Fatalf("cluster chain must dial entry only despite dial preference; got %v", out)
	}
}

func TestDialServerAddrs_FiltersClusterExit(t *testing.T) {
	out := dialServerAddrs([]string{"de.example:443", "ru.example:443", "nl.example:443"}, &config.ProtectionOptions{
		ClusterPreferredServer: "ru.example:443",
	})
	if len(out) != 2 || out[0] != "de.example:443" || out[1] != "nl.example:443" {
		t.Fatalf("cluster exit must be removed from dial candidates; got %v", out)
	}
}

func TestDialServerAddrs_AllEntriesAreClusterExit(t *testing.T) {
	out := dialServerAddrs([]string{"ru.example:443"}, &config.ProtectionOptions{
		ClusterPreferredServer: "ru.example:443",
	})
	if len(out) != 0 {
		t.Fatalf("cluster exit must not be used as entry; got %v", out)
	}
}

func TestDialServerAddrs_CanonicalFiltersClusterExit(t *testing.T) {
	out := dialServerAddrs([]string{"de.example:443", "ru.example:443"}, &config.ProtectionOptions{
		ClusterPreferredServer: "ru.example",
	})
	if len(out) != 2 {
		t.Fatalf("host without port must not filter unrelated dial entries; got %v", out)
	}
}
