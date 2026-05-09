package tunnel

import (
	"context"
	"io"
	"net"
	"testing"
	"time"

	"dev.c0redev.volter/internal/config"
)

func TestPeerRelayUDPListenerEcho(t *testing.T) {
	target, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()
	go func() {
		c, err := target.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		_, _ = io.Copy(c, c)
	}()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	relay, err := ListenPeerRelayUDP(ctx, PeerRelayUDPOptions{
		Addr:          "127.0.0.1:0",
		Token:         "tok",
		MaxConcurrent: 4,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()

	host, port, err := net.SplitHostPort(target.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	ip := net.ParseIP(host)
	if ip == nil {
		t.Fatalf("bad ip %s", host)
	}
	dstPort, err := net.LookupPort("tcp", port)
	if err != nil {
		t.Fatal(err)
	}
	prot := RelayProtForPeerHop(&config.ProtectionOptions{}, &config.RelayOptions{PeerID: "peer-a"})
	c, err := DialPeerRelayUDP(relay.Addr().String(), ip, uint16(dstPort), "tok", prot)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := c.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(c, buf); err != nil {
		t.Fatal(err)
	}
	if string(buf) != "ping" {
		t.Fatalf("got %q", buf)
	}
}

func TestPeerRelayUDPListenerEchoWithHopAck(t *testing.T) {
	target, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()
	go func() {
		c, err := target.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		_, _ = io.Copy(c, c)
	}()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	relay, err := ListenPeerRelayUDP(ctx, PeerRelayUDPOptions{
		Addr:          "127.0.0.1:0",
		Token:         "tok",
		MaxConcurrent: 4,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()

	host, port, err := net.SplitHostPort(target.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	dstPort, err := net.LookupPort("tcp", port)
	if err != nil {
		t.Fatal(err)
	}
	prot := RelayProtForPeerHop(&config.ProtectionOptions{RoutePlannerV2: true, RouteID: "r-1"}, &config.RelayOptions{PeerID: "peer-a"})
	c, err := DialPeerRelayUDP(relay.Addr().String(), net.ParseIP(host), uint16(dstPort), "tok", prot)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := c.Write([]byte("ack!")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(c, buf); err != nil {
		t.Fatal(err)
	}
	if string(buf) != "ack!" {
		t.Fatalf("got %q", buf)
	}
}

func TestPeerRelayUDPUsesInjectedPeerSocket(t *testing.T) {
	target, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()
	go func() {
		c, err := target.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		_, _ = io.Copy(c, c)
	}()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ps, err := ListenPeerSocket(ctx, "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ps.Close()

	relay, err := ListenPeerRelayUDP(ctx, PeerRelayUDPOptions{
		Socket:        ps,
		Token:         "tok",
		MaxConcurrent: 4,
	})
	if err != nil {
		t.Fatal(err)
	}

	host, port, err := net.SplitHostPort(target.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	dstPort, err := net.LookupPort("tcp", port)
	if err != nil {
		t.Fatal(err)
	}
	prot := RelayProtForPeerHop(&config.ProtectionOptions{}, &config.RelayOptions{PeerID: "peer-a"})
	c, err := DialPeerRelayUDP(ps.Addr().String(), net.ParseIP(host), uint16(dstPort), "tok", prot)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := c.Write([]byte("pong")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(c, buf); err != nil {
		t.Fatal(err)
	}
	if string(buf) != "pong" {
		t.Fatalf("got %q", buf)
	}

	if err := relay.Close(); err != nil {
		t.Fatal(err)
	}
	var tx [12]byte
	copy(tx[:], []byte("abcdefghijkl"))
	ch, stop := ps.RegisterSTUN(tx)
	defer stop()
	raw, err := net.Dial("udp", ps.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer raw.Close()
	if _, err := raw.Write(testSTUNResponse(tx)); err != nil {
		t.Fatal(err)
	}
	select {
	case <-ch:
	case <-time.After(time.Second):
		t.Fatal("injected socket was closed by relay")
	}
}
