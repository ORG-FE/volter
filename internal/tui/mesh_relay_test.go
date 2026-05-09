package tui

import (
	"os"
	"testing"
	"time"

	"dev.c0redev.volter/internal/config"
)

func TestSplitCommaList(t *testing.T) {
	if x := splitCommaList("a, b , "); len(x) != 2 || x[0] != "a" || x[1] != "b" {
		t.Fatalf("%q", x)
	}
	if splitCommaList("  ") != nil {
		t.Fatal()
	}
}

func TestMeshRelayRoundTrip(t *testing.T) {
	noTCP := false
	in := newMeshRelayInputs(&config.RelayOptions{
		TurnURLs:            []string{"turn:x@y:1"},
		StunServers:         []string{"s:1"},
		DhtRpcIntervalSec:   60,
		PathAggressive:      true,
		PeerRelayUseTCP:     &noTCP,
		AllowedClasses:      []string{"free", "paid"},
		MaxConcurrent:       8,
		BudgetKbps:          512,
		PeerRelayBudgetKbps: 768,
		MaxPeerHops:         2,
	})
	got, err := meshRelayFromInputs(in)
	if err != "" {
		t.Fatal(err)
	}
	if len(got.TurnURLs) != 1 || got.TurnURLs[0] != "turn:x@y:1" {
		t.Fatalf("turn %#v", got.TurnURLs)
	}
	if got.DhtRpcIntervalSec != 60 || !got.PathAggressive {
		t.Fatalf("%+v", got)
	}
	if got.PeerRelayUseTCP == nil || *got.PeerRelayUseTCP {
		t.Fatalf("tcp flag lost: %+v", got.PeerRelayUseTCP)
	}
	if len(got.AllowedClasses) != 2 || got.MaxConcurrent != 8 || got.BudgetKbps != 512 ||
		got.PeerRelayBudgetKbps != 768 || got.MaxPeerHops != 2 {
		t.Fatalf("advanced fields lost: %+v", got)
	}
	mesh := config.RelayOptionsToMesh(&got, nil)
	if mesh.P2P.UseTCP {
		t.Fatalf("mesh tcp flag reset: %+v", mesh.P2P)
	}
	if len(mesh.ServerRelay.AllowedClasses) != 2 || mesh.Volunteer.MaxConcurrent != 8 ||
		mesh.Volunteer.BudgetKbps != 768 || mesh.Policy.BudgetKbps != 512 || mesh.Policy.MaxPeerHops != 2 {
		t.Fatalf("mesh advanced fields lost: %+v", mesh)
	}
}

func TestMergeCfgPreserveRelayProtectionKeepsMesh(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	old := config.Config{
		Server: "1.2.3.4:443",
		Token:  "tok",
		Mesh:   &config.MeshConfig{Enabled: true, P2P: config.MeshP2POptions{Enabled: true}},
	}
	if err := config.Save("p", old); err != nil {
		t.Fatal(err)
	}
	got := mergeCfgPreserveRelayProtection("p", config.Config{Server: "5.6.7.8:443", Token: "new"})
	if got.Mesh == nil || !got.Mesh.Enabled {
		t.Fatalf("mesh not preserved: %+v", got.Mesh)
	}
	_ = os.Unsetenv("XDG_CONFIG_HOME")
}

func TestPeerTicketFromConfigRequiresRealPeerData(t *testing.T) {
	cfg := config.Config{Server: "server:443"}
	if _, err := peerTicketFromConfig(cfg, time.Hour); err == nil {
		t.Fatal("expected missing relay/mesh error")
	}
	cfg.Relay = &config.RelayOptions{PeerID: "peer-a"}
	if _, err := peerTicketFromConfig(cfg, time.Hour); err == nil {
		t.Fatal("expected missing bootstrap pubkey")
	}
	cfg.Relay.BootstrapPubKey = "pub-a"
	if _, err := peerTicketFromConfig(cfg, time.Hour); err == nil {
		t.Fatal("expected missing peer udp endpoint")
	}
}

func TestPeerTicketFromConfigUsesPeerUDPAddrs(t *testing.T) {
	cfg := config.Config{
		Server: "server:443",
		Relay: &config.RelayOptions{
			PeerID:                "peer-a",
			BootstrapPubKey:       "pub-a",
			PeerRelayUDPAdvertise: "198.51.100.10:4001",
			PeerRelayUDPListen:    "0.0.0.0:4001",
		},
	}
	ticket, err := peerTicketFromConfig(cfg, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if ticket.PeerID != "peer-a" || ticket.PubKey != "pub-a" {
		t.Fatalf("bad identity: %+v", ticket)
	}
	if len(ticket.Addrs) != 2 || ticket.Addrs[0] != "198.51.100.10:4001" || ticket.Addrs[1] != "0.0.0.0:4001" {
		t.Fatalf("bad addrs: %+v", ticket.Addrs)
	}
}
