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
