package vpn

import (
	"testing"
	"time"

	"dev.c0redev.volter/internal/config"
)

func TestRouteDirectiveProtectionDoesNotMutateBase(t *testing.T) {
	base := &config.ProtectionOptions{RouteMode: "auto", ClusterPreferredServer: "old:443", RouteID: "old-route"}
	dir := RouteDirective{
		Target:    "1.1.1.1:443",
		Mode:      "server_relay",
		Endpoint:  "10.0.0.10:443",
		RouteID:   "new-route",
		ExpiresAt: time.Now().Add(time.Minute),
	}

	got, ok := routeDirectiveProtection(base, dir)
	if !ok {
		t.Fatal("expected protection")
	}
	if got == base {
		t.Fatal("must copy base")
	}
	if got.RouteMode != "server_relay" || got.ClusterPreferredServer != "10.0.0.10:443" {
		t.Fatalf("bad protection: %+v", got)
	}
	if got.RouteID != "" {
		t.Fatalf("server relay directive must not set RouteID until hop ack supports it: %+v", got)
	}
	if base.ClusterPreferredServer != "old:443" || base.RouteID != "old-route" {
		t.Fatalf("base mutated: %+v", base)
	}
}

func TestRouteDirectiveProtectionRespectsServerRelayDisabled(t *testing.T) {
	base := &config.ProtectionOptions{RouteMode: "auto"}
	dir := RouteDirective{Mode: "server_relay", Endpoint: "10.0.0.10:443"}
	mesh := &config.MeshConfig{Enabled: true, ServerRelay: config.MeshServerRelayOptions{Enabled: false}}
	got, ok := routeDirectiveProtectionForMesh(base, dir, mesh)
	if ok {
		t.Fatal("server relay directive must be rejected when mesh.serverRelay.enabled=false")
	}
	if got != base {
		t.Fatal("disabled directive must return base")
	}
}

func TestRouteDirectiveProtectionRespectsPeerRelayDisabled(t *testing.T) {
	base := &config.ProtectionOptions{RouteMode: "auto"}
	dir := RouteDirective{Mode: "peer_relay", Endpoint: "peer:1", PeerID: "p", RouteID: "r"}
	mesh := &config.MeshConfig{Enabled: true, P2P: config.MeshP2POptions{Enabled: false}}
	got, ok := routeDirectiveProtectionForMesh(base, dir, mesh)
	if ok {
		t.Fatalf("expected disabled peer relay directive, got %+v", got)
	}
	mesh.P2P.Enabled = true
	got, ok = routeDirectiveProtectionForMesh(base, dir, mesh)
	if !ok || got.RouteMode != "peer_relay" || got.PeerID != "p" || got.RouteID != "r" {
		t.Fatalf("expected peer relay directive, got ok=%v %+v", ok, got)
	}
}
