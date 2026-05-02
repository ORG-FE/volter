package dpiengine

import (
	"context"
	"io"
	"net"
	"strconv"
	"testing"
	"time"
)

func dialSocks5Connect(t *testing.T, socksAddr, targetHostPort string) net.Conn {
	t.Helper()
	sc, err := net.Dial("tcp", socksAddr)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := sc.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 256)
	if _, err := io.ReadFull(sc, buf[:2]); err != nil {
		t.Fatal(err)
	}
	if buf[0] != 5 || buf[1] != 0 {
		sc.Close()
		t.Fatal("socks handshake reject")
	}
	host, ps, err := net.SplitHostPort(targetHostPort)
	if err != nil {
		sc.Close()
		t.Fatal(err)
	}
	port, err := strconv.Atoi(ps)
	if err != nil || port <= 0 || port > 65535 {
		sc.Close()
		t.Fatal(err)
	}
	ip := net.ParseIP(host)
	var req []byte
	switch {
	case ip != nil && ip.To4() != nil:
		req = []byte{5, 1, 0, 1}
		req = append(req, ip.To4()...)
	case ip != nil && ip.To16() != nil:
		req = []byte{5, 1, 0, 4}
		req = append(req, ip.To16()...)
	default:
		h := host
		if len(h) > 255 {
			sc.Close()
			t.Fatal("hostname too long")
		}
		req = []byte{5, 1, 0, 3, byte(len(h))}
		req = append(req, h...)
	}
	req = append(req, byte(port>>8), byte(port))
	if _, err := sc.Write(req); err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadFull(sc, buf[:10]); err != nil {
		t.Fatal(err)
	}
	if buf[1] != 0 {
		sc.Close()
		t.Fatalf("socks connect failed rep=%d", buf[1])
	}
	return sc
}

func TestServeEchoRoundTrip(t *testing.T) {
	echo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()

	go func() {
		for {
			c, err := echo.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}(c)
		}
	}()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	opts := Options{SplitAfter: 0}
	addr, err := Serve(ctx, opts)
	if err != nil {
		t.Fatal(err)
	}

	sc := dialSocks5Connect(t, addr, echo.Addr().String())
	defer sc.Close()

	payload := []byte("ping-volter-dpiengine")
	if _, err := sc.Write(payload); err != nil {
		t.Fatal(err)
	}
	out := make([]byte, len(payload))
	if _, err := io.ReadFull(sc, out); err != nil {
		t.Fatal(err)
	}
	if string(out) != string(payload) {
		t.Fatalf("echo mismatch got %q want %q", out, payload)
	}
	cancel()
	time.Sleep(50 * time.Millisecond)
}
