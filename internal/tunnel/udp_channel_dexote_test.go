package tunnel

import (
	"bufio"
	"net"
	"testing"
	"time"

	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/dexote"
	"dev.c0redev.volter/internal/obfuscate"
	"dev.c0redev.volter/internal/protocol"
)

func TestDialUDPChannelDexote(t *testing.T) {
	sScalar, sPub, err := dexote.GenerateServerKey()
	if err != nil {
		t.Fatal(err)
	}
	SetDexoteServerPub(sPub)

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	const channelID = byte(3)
	const token = "udp-chan-token"
	prot := &config.ProtectionOptions{}
	slot := SlotForProtection(prot)

	type srvRes struct {
		role    byte
		opts    []byte
		frame   protocol.UDPFrame
		readErr error
	}
	srvCh := make(chan srvRes, 1)
	go func() {
		conn, aerr := ln.Accept()
		if aerr != nil {
			srvCh <- srvRes{readErr: aerr}
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
		keys, payload, herr := dexote.ServerHandshake(conn, sScalar, sPub, slot, nil, dexote.NewMemReplayCache())
		if herr != nil {
			srvCh <- srvRes{readErr: herr}
			return
		}

		w := obfuscate.WrapAEAD(conn, keys,
			dexote.NewPoly(keys.Secret, slot, "tx"),
			dexote.NewPoly(keys.Secret, slot, "rx"), dexotePadMax(prot))
		br := bufio.NewReaderSize(w, protocol.BufSizeForConn(slot))
		f, rerr := protocol.ReadUDPFrame(br)
		srvCh <- srvRes{role: payload.Role, opts: payload.Opts, frame: f, readErr: rerr}
	}()

	c, _, cw, maxPad, err := DialUDPChannelDexote(ln.Addr().String(), channelID, token, prot)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()
	if maxPad != 0 {
		t.Fatalf("maxPad=%d, want 0 (AEAD pads record layer)", maxPad)
	}

	payload := []byte("hello-udp-over-tcp")
	frame := protocol.UDPFrame{
		DstIP:   net.ParseIP("8.8.8.8"),
		DstPort: 53,
		SrcPort: 12345,
		Payload: payload,
	}
	if err := protocol.WriteUDPFrameWithPad(cw, frame, maxPad); err != nil {
		t.Fatalf("write udp frame: %v", err)
	}
	if err := cw.Flush(); err != nil {
		t.Fatalf("flush: %v", err)
	}

	sr := <-srvCh
	if sr.readErr != nil {
		t.Fatalf("server: %v", sr.readErr)
	}
	if sr.role != protocol.RoleUDP() {
		t.Fatalf("role=%d, want UDP(%d)", sr.role, protocol.RoleUDP())
	}
	if len(sr.opts) < 1 || sr.opts[0] != channelID {
		t.Fatalf("opts[0]=%v, want channelID=%d", sr.opts, channelID)
	}
	if string(sr.frame.Payload) != string(payload) {
		t.Fatalf("payload mismatch: %q vs %q", sr.frame.Payload, payload)
	}
	if sr.frame.DstPort != 53 || !sr.frame.DstIP.Equal(net.ParseIP("8.8.8.8")) {
		t.Fatalf("dst mismatch: %s:%d", sr.frame.DstIP, sr.frame.DstPort)
	}
}
