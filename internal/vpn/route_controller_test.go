package vpn

import (
	"net"
	"testing"
	"time"
)

func TestRouteControllerUsesLiveDirective(t *testing.T) {
	now := time.Unix(1700000000, 0)
	rc := NewRouteController()
	rc.Apply(RouteDirective{
		Target:    "1.1.1.1:443",
		Mode:      "server_relay",
		Endpoint:  "10.0.0.10:443",
		ExpiresAt: now.Add(time.Minute),
	})

	got, ok := rc.DirectiveFor(Flow{DstIP: net.ParseIP("1.1.1.1"), DstPort: 443}, now)
	if !ok {
		t.Fatal("expected directive")
	}
	if got.Endpoint != "10.0.0.10:443" {
		t.Fatalf("endpoint=%s", got.Endpoint)
	}

	_, ok = rc.DirectiveFor(Flow{DstIP: net.ParseIP("1.1.1.1"), DstPort: 443}, now.Add(2*time.Minute))
	if ok {
		t.Fatal("expired directive must not be used")
	}
}

func TestRouteControllerDirectiveForTarget(t *testing.T) {
	now := time.Unix(1700000000, 0)
	rc := NewRouteController()
	rc.Apply(RouteDirective{
		Target:    "cluster-a:443",
		Mode:      "server_relay",
		Endpoint:  "10.0.0.10:443",
		ExpiresAt: now.Add(time.Minute),
	})

	got, ok := rc.DirectiveForTarget("cluster-a:443", now)
	if !ok {
		t.Fatal("expected directive")
	}
	if got.Endpoint != "10.0.0.10:443" {
		t.Fatalf("endpoint=%s", got.Endpoint)
	}
}
