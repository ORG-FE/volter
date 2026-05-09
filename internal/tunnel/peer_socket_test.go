package tunnel

import (
	"context"
	"net"
	"testing"
	"time"
)

func TestPeerSocketRoutesSTUNAndVP02(t *testing.T) {
	ps := NewPeerSocketForTest()
	var tx [12]byte
	copy(tx[:], []byte("abcdefghijkl"))
	stunCh, cancel := ps.RegisterSTUN(tx)
	defer cancel()

	var vp02 [][]byte
	ps.SetVP02Handler(func(addr *net.UDPAddr, pkt []byte) {
		vp02 = append(vp02, append([]byte(nil), pkt...))
	})

	from := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 4567}
	ps.dispatchPacket(from, testSTUNResponse(tx))
	ps.dispatchPacket(from, []byte("VP02\x00\x01\x00\x00\x00\x00\x00\x01x"))
	ps.dispatchPacket(from, []byte("noise-punch"))

	select {
	case got := <-stunCh:
		if got.From.String() != from.String() {
			t.Fatalf("stun from=%s want %s", got.From, from)
		}
	case <-time.After(time.Second):
		t.Fatal("stun response was not routed")
	}
	if len(vp02) != 1 {
		t.Fatalf("vp02 packets=%d want 1", len(vp02))
	}
}

func testSTUNResponse(tx [12]byte) []byte {
	p := make([]byte, 20)
	p[0] = 0x01
	p[1] = 0x01
	p[4] = 0x21
	p[5] = 0x12
	p[6] = 0xa4
	p[7] = 0x42
	copy(p[8:20], tx[:])
	return p
}

func TestPeerSocketListenReadsUDP(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ps, err := ListenPeerSocket(ctx, "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ps.Close()

	var tx [12]byte
	copy(tx[:], []byte("abcdefghijkl"))
	stunCh, stop := ps.RegisterSTUN(tx)
	defer stop()

	c, err := net.Dial("udp", ps.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if _, err := c.Write(testSTUNResponse(tx)); err != nil {
		t.Fatal(err)
	}

	select {
	case <-stunCh:
	case <-time.After(time.Second):
		t.Fatal("stun response was not received from UDP socket")
	}
}
