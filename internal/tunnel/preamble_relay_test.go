package tunnel

import (
	"bufio"
	"bytes"
	"testing"

	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/protocol"
)

func TestTcpRelayPreambleUsesRelayRole(t *testing.T) {
	var buf bytes.Buffer
	w := bufio.NewWriter(&buf)
	prot := &config.ProtectionOptions{
		RelayHop:    1,
		RelayMaxHop: 2,
		PeerID:      "peer-a",
	}
	if err := tcpRelayPreamble(w, "tok", prot, protocol.TimeSlot()); err != nil {
		t.Fatal(err)
	}
	hs, err := protocol.ReadHandshakeAfterSkip(bufio.NewReader(bytes.NewReader(buf.Bytes())))
	if err != nil {
		t.Fatal(err)
	}
	if hs.Role != protocol.RoleRelayTCP() {
		t.Fatalf("want relay role, got %d", hs.Role)
	}
}

func TestTcpRelayPreambleUsesTcpRoleWithoutRelay(t *testing.T) {
	var buf bytes.Buffer
	w := bufio.NewWriter(&buf)
	prot := &config.ProtectionOptions{}
	if err := tcpRelayPreamble(w, "tok", prot, protocol.TimeSlot()); err != nil {
		t.Fatal(err)
	}
	hs, err := protocol.ReadHandshakeAfterSkip(bufio.NewReader(bytes.NewReader(buf.Bytes())))
	if err != nil {
		t.Fatal(err)
	}
	if hs.Role != protocol.RoleTCP() {
		t.Fatalf("want tcp role, got %d", hs.Role)
	}
}

func TestProtForDirectRouteClearsRelayFields(t *testing.T) {
	src := &config.ProtectionOptions{
		RouteMode:   "direct",
		RelayHop:    2,
		RelayMaxHop: 3,
		PeerID:      "peer-a",
		RelayNonce:  "n",
		RelaySig:    "s",
	}
	got := protForDirectRoute(src)
	if got == nil {
		t.Fatal("expected non nil")
	}
	if got.RelayHop != 0 || got.RelayMaxHop != 0 {
		t.Fatalf("expected relay hop cleared, got %d/%d", got.RelayHop, got.RelayMaxHop)
	}
	if got.PeerID != "" || got.RelayNonce != "" || got.RelaySig != "" {
		t.Fatalf("expected peer relay fields cleared, got peer=%q nonce=%q sig=%q", got.PeerID, got.RelayNonce, got.RelaySig)
	}
}

func TestProtForServerRelayRouteEnforcesRelayHop(t *testing.T) {
	got := protForServerRelayRoute(&config.ProtectionOptions{RouteMode: "server_relay"}, &config.RelayOptions{BudgetKbps: 256})
	if got == nil {
		t.Fatal("expected non nil")
	}
	if got.RelayHop < 1 {
		t.Fatalf("expected relay hop >=1, got %d", got.RelayHop)
	}
	if got.RelayMaxHop != 2 {
		t.Fatalf("expected default relay max hop, got %d", got.RelayMaxHop)
	}
	if got.RelayBudgetKbps != 256 {
		t.Fatalf("expected relay budget from relay opts, got %d", got.RelayBudgetKbps)
	}
}
