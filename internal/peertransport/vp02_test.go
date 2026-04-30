package peertransport

import (
	"bytes"
	"encoding/binary"
	"net"
	"testing"
)

func TestVP02AssemblerSingle(t *testing.T) {
	var asm Assembler
	pkt := buildTestPkt(1, 0, []byte("hello"))
	msg, ok := asm.Feed(pkt)
	if !ok || string(msg) != "hello" {
		t.Fatalf("got ok=%v msg=%q", ok, msg)
	}
}

func TestVP02AssemblerMulti(t *testing.T) {
	payload := bytes.Repeat([]byte("x"), MaxVP02ChunkPayload+50)
	chunks := chunkTest(payload)
	var asm Assembler
	var got []byte
	for _, ch := range chunks {
		m, ok := asm.Feed(ch)
		if ok {
			got = m
			break
		}
	}
	if len(got) != len(payload) {
		t.Fatalf("len want %d got %d", len(payload), len(got))
	}
}

func TestVP02WriteReadUDP(t *testing.T) {
	srv, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = srv.Close() }()
	cli, err := net.DialUDP("udp", nil, srv.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = cli.Close() }()
	want := bytes.Repeat([]byte("z"), 2400)
	if err := WriteVP02Message(cli, want); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 65536)
	var asm Assembler
	for {
		n, _, err := srv.ReadFromUDP(buf)
		if err != nil {
			t.Fatal(err)
		}
		msg, ok := asm.Feed(buf[:n])
		if ok {
			if !bytes.Equal(msg, want) {
				t.Fatalf("payload mismatch len=%d", len(msg))
			}
			return
		}
	}
}

func buildTestPkt(total, seq uint16, payload []byte) []byte {
	out := make([]byte, vp02Header+len(payload))
	copy(out[:4], vp02Magic)
	binary.BigEndian.PutUint16(out[4:6], total)
	binary.BigEndian.PutUint16(out[6:8], seq)
	binary.BigEndian.PutUint32(out[8:12], uint32(len(payload)))
	copy(out[vp02Header:], payload)
	return out
}

func chunkTest(p []byte) [][]byte {
	var out [][]byte
	off := 0
	seq := 0
	total := (len(p) + MaxVP02ChunkPayload - 1) / MaxVP02ChunkPayload
	for off < len(p) {
		n := MaxVP02ChunkPayload
		if off+n > len(p) {
			n = len(p) - off
		}
		out = append(out, buildTestPkt(uint16(total), uint16(seq), p[off:off+n]))
		off += n
		seq++
	}
	return out
}
