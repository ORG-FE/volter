package vpn

import (
	"testing"

	"dev.c0redev.volter/internal/config"
)

func TestMeshVolunteerGate(t *testing.T) {
	mesh := &config.MeshConfig{
		Enabled: true,
		P2P:     config.MeshP2POptions{Enabled: true, UseUDP: true},
		STUN:    config.MeshSTUNOptions{Enabled: true, PublishSrflx: true},
	}
	if meshAllowsVolunteerRelay(mesh) {
		t.Fatal("mesh without volunteer must not allow relay forwarding")
	}
	if meshAllowsPresencePublish(mesh) {
		t.Fatal("mesh without volunteer must not publish relay presence")
	}

	mesh.Volunteer.Enabled = true
	if !meshAllowsVolunteerRelay(mesh) {
		t.Fatal("volunteer mesh must allow relay forwarding")
	}
	if !meshAllowsPresencePublish(mesh) {
		t.Fatal("volunteer mesh must publish relay presence")
	}
}

func TestProtectionWithMeshPolicyRouteMode(t *testing.T) {
	base := &config.ProtectionOptions{RouteMode: "auto", ClusterPreferredServer: "old:443"}
	got := protectionWithMeshPolicy(base, &config.MeshConfig{
		Enabled: true,
		P2P:     config.MeshP2POptions{Enabled: true},
		Policy:  config.MeshPolicyOptions{RouteMode: "peer_relay"},
	})
	if got == base {
		t.Fatal("mesh policy override must copy protection")
	}
	if got.RouteMode != "peer_relay" || got.ClusterPreferredServer != "old:443" {
		t.Fatalf("bad mesh policy protection: %+v", got)
	}
	if base.RouteMode != "auto" {
		t.Fatalf("base mutated: %+v", base)
	}
}

func TestProtectionWithMeshPolicyRespectsDisabledRouteClass(t *testing.T) {
	base := &config.ProtectionOptions{RouteMode: "auto"}
	if got := protectionWithMeshPolicy(base, &config.MeshConfig{
		Enabled: true,
		P2P:     config.MeshP2POptions{Enabled: false},
		Policy:  config.MeshPolicyOptions{RouteMode: "peer_relay"},
	}); got != base {
		t.Fatalf("disabled p2p must ignore peer policy: %+v", got)
	}
	if got := protectionWithMeshPolicy(base, &config.MeshConfig{
		Enabled:     true,
		ServerRelay: config.MeshServerRelayOptions{Enabled: false},
		Policy:      config.MeshPolicyOptions{RouteMode: "server_relay"},
	}); got != base {
		t.Fatalf("disabled server relay must ignore server policy: %+v", got)
	}
}

func TestProtectionWithMeshPolicyIgnoresAuto(t *testing.T) {
	base := &config.ProtectionOptions{RouteMode: "server_relay"}
	got := protectionWithMeshPolicy(base, &config.MeshConfig{
		Enabled: true,
		Policy:  config.MeshPolicyOptions{RouteMode: "auto"},
	})
	if got != base {
		t.Fatalf("auto policy should preserve explicit protection: %+v", got)
	}
}
