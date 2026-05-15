package dpiengine

import (
	"bytes"
	"io"
	"net"
	"sync"
	"testing"
	"time"
)

type memUpstream struct {
	mu     sync.Mutex
	writes [][]byte
}

func (m *memUpstream) Read(p []byte) (int, error) { return 0, io.EOF }
func (m *memUpstream) Write(b []byte) (int, error) {
	m.mu.Lock()
	m.writes = append(m.writes, append([]byte(nil), b...))
	m.mu.Unlock()
	return len(b), nil
}
func (m *memUpstream) Close() error                       { return nil }
func (m *memUpstream) LocalAddr() net.Addr                { return nil }
func (m *memUpstream) RemoteAddr() net.Addr               { return nil }
func (m *memUpstream) SetDeadline(t time.Time) error      { return nil }
func (m *memUpstream) SetReadDeadline(t time.Time) error  { return nil }
func (m *memUpstream) SetWriteDeadline(t time.Time) error { return nil }

func (m *memUpstream) chunks() [][]byte {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([][]byte, len(m.writes))
	for i, w := range m.writes {
		out[i] = append([]byte(nil), w...)
	}
	return out
}

func TestRelayDisorderSendsSecondSegmentFirst(t *testing.T) {
	cRead, cWrite := net.Pipe()
	up := &memUpstream{}
	done := make(chan struct{})
	go func() {
		defer close(done)
		relayPipe(cRead, up, Options{SplitAfter: 2, TTLMillis: 0, Disorder: true, JitterMaxMs: 0, LeadInMs: 0})
	}()
	if _, err := cWrite.Write([]byte("abcd")); err != nil {
		t.Fatal(err)
	}
	if err := cWrite.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("relay timeout")
	}
	ch := up.chunks()
	if len(ch) < 2 {
		t.Fatalf("want 2+ writes, got %d: %q", len(ch), ch)
	}
	if string(ch[0]) != "cd" || string(ch[1]) != "ab" {
		t.Fatalf("disorder want cd,ab got %q %q", ch[0], ch[1])
	}
}

func TestRelaySplitInsertsTTLBetweenChunks(t *testing.T) {
	cRead, cWrite := net.Pipe()
	up := &memUpstream{}
	done := make(chan struct{})
	t0 := time.Now()
	go func() {
		defer close(done)
		relayPipe(cRead, up, Options{SplitAfter: 1, TTLMillis: 50, Disorder: false, JitterMaxMs: 0, LeadInMs: 0})
	}()
	if _, err := cWrite.Write([]byte("ab")); err != nil {
		t.Fatal(err)
	}
	if err := cWrite.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("relay timeout")
	}
	if time.Since(t0) < 45*time.Millisecond {
		t.Fatalf("ttl too short: %v", time.Since(t0))
	}
	ch := up.chunks()
	if len(ch) < 2 {
		t.Fatalf("want 2 writes, got %d", len(ch))
	}
	if string(ch[0]) != "a" || string(ch[1]) != "b" {
		t.Fatalf("order got %q %q", ch[0], ch[1])
	}
}

func TestRelayTripleSplitOrder(t *testing.T) {
	cRead, cWrite := net.Pipe()
	up := &memUpstream{}
	done := make(chan struct{})
	go func() {
		defer close(done)
		relayPipe(cRead, up, Options{
			SplitAfter: 2, SplitAfter2: 3, TTLMillis: 0, TTL2Millis: 0, Disorder: false,
			JitterMaxMs: 0, LeadInMs: 0,
		})
	}()
	if _, err := cWrite.Write([]byte("abcde")); err != nil {
		t.Fatal(err)
	}
	if err := cWrite.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("relay timeout")
	}
	ch := up.chunks()
	if len(ch) < 3 {
		t.Fatalf("want 3 writes, got %d %q", len(ch), ch)
	}
	if string(ch[0]) != "ab" || string(ch[1]) != "c" || string(ch[2]) != "de" {
		t.Fatalf("triple order got %q %q %q", ch[0], ch[1], ch[2])
	}
}

func TestRelayTripleDisorder(t *testing.T) {
	cRead, cWrite := net.Pipe()
	up := &memUpstream{}
	done := make(chan struct{})
	go func() {
		defer close(done)
		relayPipe(cRead, up, Options{
			SplitAfter: 2, SplitAfter2: 3, TTLMillis: 0, TTL2Millis: 0, Disorder: true,
			JitterMaxMs: 0, LeadInMs: 0,
		})
	}()
	if _, err := cWrite.Write([]byte("abcde")); err != nil {
		t.Fatal(err)
	}
	if err := cWrite.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("relay timeout")
	}
	ch := up.chunks()
	if len(ch) < 3 {
		t.Fatalf("want 3 writes, got %d", len(ch))
	}
	if string(ch[0]) != "c" || string(ch[1]) != "ab" || string(ch[2]) != "de" {
		t.Fatalf("triple disorder got %q %q %q", ch[0], ch[1], ch[2])
	}
}

func tcpEchoServer(t *testing.T, banner []byte) (addr string, cleanup func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		if len(banner) > 0 {
			if _, err := c.Write(banner); err != nil {
				return
			}
		}
		buf := make([]byte, 65536)
		for {
			n, err := c.Read(buf)
			if n > 0 {
				if _, werr := c.Write(buf[:n]); werr != nil {
					return
				}
			}
			if err != nil {
				return
			}
		}
	}()
	return ln.Addr().String(), func() {
		_ = ln.Close()
		<-done
	}
}

func TestRelayBidirectionalNoSplit(t *testing.T) {
	addr, cleanup := tcpEchoServer(t, nil)
	defer cleanup()

	remote, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer remote.Close()

	clientUser, clientRelay := net.Pipe()
	defer clientUser.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		relayPipe(clientRelay, remote, Options{LeadInMs: 0, JitterMaxMs: 0})
	}()

	payload := []byte("hello-echo-roundtrip-12345")
	if _, err := clientUser.Write(payload); err != nil {
		t.Fatal(err)
	}
	echo := make([]byte, len(payload))
	if _, err := io.ReadFull(clientUser, echo); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(echo, payload) {
		t.Fatalf("echo mismatch got %q want %q", echo, payload)
	}
	if err := clientUser.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("relay timeout")
	}
}

func TestRelayBidirectionalNoSplitWithLeadIn(t *testing.T) {
	addr, cleanup := tcpEchoServer(t, nil)
	defer cleanup()

	remote, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer remote.Close()

	clientUser, clientRelay := net.Pipe()
	defer clientUser.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		relayPipe(clientRelay, remote, Options{LeadInMs: 2, JitterMaxMs: 0})
	}()

	payload := []byte("payload-after-leadin")
	if _, err := clientUser.Write(payload); err != nil {
		t.Fatal(err)
	}
	echo := make([]byte, len(payload))
	if _, err := io.ReadFull(clientUser, echo); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(echo, payload) {
		t.Fatalf("echo mismatch got %q", echo)
	}
	_ = clientUser.Close()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("relay timeout")
	}
}

func TestRelayBidirectionalSplitOrdered(t *testing.T) {
	addr, cleanup := tcpEchoServer(t, nil)
	defer cleanup()

	remote, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer remote.Close()

	clientUser, clientRelay := net.Pipe()
	defer clientUser.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		relayPipe(clientRelay, remote, Options{
			SplitAfter: 4, TTLMillis: 0, Disorder: false, JitterMaxMs: 0, LeadInMs: 0,
		})
	}()

	part1 := []byte("part")
	part2 := []byte("-two-chunks")
	if _, err := clientUser.Write(part1); err != nil {
		t.Fatal(err)
	}
	if _, err := clientUser.Write(part2); err != nil {
		t.Fatal(err)
	}
	want := append(append([]byte(nil), part1...), part2...)
	echo := make([]byte, len(want))
	if _, err := io.ReadFull(clientUser, echo); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(echo, want) {
		t.Fatalf("echo mismatch got %q want %q", echo, want)
	}
	_ = clientUser.Close()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("relay timeout")
	}
}

func TestRelayBidirectionalSplitDisorder(t *testing.T) {
	addr, cleanup := tcpEchoServer(t, nil)
	defer cleanup()

	remote, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer remote.Close()

	clientUser, clientRelay := net.Pipe()
	defer clientUser.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		relayPipe(clientRelay, remote, Options{
			SplitAfter: 3, TTLMillis: 0, Disorder: true, JitterMaxMs: 0, LeadInMs: 0,
		})
	}()

	payload := []byte("disorder-echo-body")
	if _, err := clientUser.Write(payload); err != nil {
		t.Fatal(err)
	}
	wantEcho := []byte("order-echo-bodydis")
	echo := make([]byte, len(wantEcho))
	if _, err := io.ReadFull(clientUser, echo); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(echo, wantEcho) {
		t.Fatalf("echo mismatch got %q want %q", echo, wantEcho)
	}
	_ = clientUser.Close()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("relay timeout")
	}
}

func TestRelayBidirectionalTripleSplit(t *testing.T) {
	addr, cleanup := tcpEchoServer(t, nil)
	defer cleanup()

	remote, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer remote.Close()

	clientUser, clientRelay := net.Pipe()
	defer clientUser.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		relayPipe(clientRelay, remote, Options{
			SplitAfter: 2, SplitAfter2: 5, TTLMillis: 0, TTL2Millis: 0, Disorder: false,
			JitterMaxMs: 0, LeadInMs: 0,
		})
	}()

	payload := []byte("abcdefgh")
	if _, err := clientUser.Write(payload); err != nil {
		t.Fatal(err)
	}
	echo := make([]byte, len(payload))
	if _, err := io.ReadFull(clientUser, echo); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(echo, payload) {
		t.Fatalf("echo mismatch got %q want %q", echo, payload)
	}
	_ = clientUser.Close()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("relay timeout")
	}
}

func TestRelayBidirectionalTripleDisorder(t *testing.T) {
	addr, cleanup := tcpEchoServer(t, nil)
	defer cleanup()

	remote, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer remote.Close()

	clientUser, clientRelay := net.Pipe()
	defer clientUser.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		relayPipe(clientRelay, remote, Options{
			SplitAfter: 2, SplitAfter2: 5, TTLMillis: 0, TTL2Millis: 0, Disorder: true,
			JitterMaxMs: 0, LeadInMs: 0,
		})
	}()

	payload := []byte("abcdefgh")
	if _, err := clientUser.Write(payload); err != nil {
		t.Fatal(err)
	}
	wantEcho := []byte("cdeabfgh")
	echo := make([]byte, len(wantEcho))
	if _, err := io.ReadFull(clientUser, echo); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(echo, wantEcho) {
		t.Fatalf("echo mismatch got %q want %q", echo, wantEcho)
	}
	_ = clientUser.Close()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("relay timeout")
	}
}

func TestRelayServerBannerThenClientSplit(t *testing.T) {
	banner := []byte("220 ptera-echo\r\n")
	addr, cleanup := tcpEchoServer(t, banner)
	defer cleanup()

	remote, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer remote.Close()

	clientUser, clientRelay := net.Pipe()
	defer clientUser.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		relayPipe(clientRelay, remote, Options{
			SplitAfter: 4, TTLMillis: 0, Disorder: false, JitterMaxMs: 0, LeadInMs: 0,
		})
	}()

	readBanner := make([]byte, 64)
	n, err := clientUser.Read(readBanner)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(readBanner[:n], banner) {
		t.Fatalf("banner got %q want %q", readBanner[:n], banner)
	}

	req := []byte("USER anonymous\r\n")
	if _, err := clientUser.Write(req); err != nil {
		t.Fatal(err)
	}
	echo := make([]byte, len(req))
	if _, err := io.ReadFull(clientUser, echo); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(echo, req) {
		t.Fatalf("echo req got %q want %q", echo, req)
	}
	_ = clientUser.Close()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("relay timeout")
	}
}
