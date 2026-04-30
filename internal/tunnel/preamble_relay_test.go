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
