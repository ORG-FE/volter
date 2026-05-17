package vpn

import (
	"testing"

	"dev.c0redev.volter/internal/tunnel"
)

func TestClusterHTTPPollTargetActiveWire(t *testing.T) {
	t.Cleanup(func() { tunnel.ClearActiveVolterServer() })
	tunnel.SetActiveVolterServerForTest("10.0.0.1:443")
	got := clusterHTTPPollTarget([]string{"9.9.9.9:1", "8.8.8.8:2"}, nil)
	if got != "10.0.0.1:443" {
		t.Fatalf("got %q", got)
	}
}

func TestClusterHTTPPollTargetFallbackFirst(t *testing.T) {
	t.Cleanup(func() { tunnel.ClearActiveVolterServer() })
	got := clusterHTTPPollTarget([]string{"a:1", "b:2"}, nil)
	if got != "a:1" {
		t.Fatalf("got %q", got)
	}
}
