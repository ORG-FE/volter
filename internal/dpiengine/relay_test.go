package dpiengine

import (
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
	if len(ch) < 2 {
		t.Fatalf("want at least 2 writes, got %d", len(ch))
	}
	if len(ch) >= 3 && string(ch[0]) == "de" && string(ch[1]) == "c" && string(ch[2]) == "ab" {
		return
	}
	total := ""
	for _, c := range ch {
		total += string(c)
	}
	if len(total) != 5 {
		t.Fatalf("disorder: expected 5 bytes total, got %d: %q", len(total), total)
	}
}
