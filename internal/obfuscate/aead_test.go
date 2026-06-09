package obfuscate

import (
	"bytes"
	"crypto/rand"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"dev.c0redev.volter/internal/dexote"
)

func keysPair() (*dexote.Keys, *dexote.Keys) {
	sScalar, sPub, _ := dexote.GenerateServerKey()
	c, s := net.Pipe()
	defer c.Close()
	defer s.Close()
	var sk *dexote.Keys
	done := make(chan struct{})
	go func() {
		sk, _, _ = dexote.ServerHandshake(s, sScalar, sPub, 1, nil, dexote.NewMemReplayCache())
		close(done)
	}()
	ck, _, _ := dexote.ClientHandshake(c, sPub, 1, dexote.ClientHelloPayload{Role: 2, Token: "t"})
	<-done
	return ck, sk
}

func TestAEADRoundtrip(t *testing.T) {
	ck, sk := keysPair()
	c, s := net.Pipe()
	cc := WrapAEAD(c, ck, dexote.NewPoly(ck.Secret, 1, "tx"), dexote.NewPoly(ck.Secret, 1, "rx"), 64)

	sc := WrapAEAD(s, sk, dexote.NewPoly(sk.Secret, 1, "rx"), dexote.NewPoly(sk.Secret, 1, "tx"), 64)
	defer cc.Close()
	defer sc.Close()

	msg := make([]byte, 40000)
	rand.Read(msg)
	go func() {
		io.Copy(sc, sc)
	}()

	go func() {
		cc.Write(msg)
	}()

	got := make([]byte, len(msg))
	if _, err := io.ReadFull(cc, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(msg, got) {
		t.Fatal("echo mismatch")
	}
}

func TestAEADShapeHookRoundtrip(t *testing.T) {
	ck, sk := keysPair()
	c, s := net.Pipe()

	var mu sync.Mutex
	var seenLens []int
	hook := func(payloadLen int) (int, time.Duration) {
		mu.Lock()
		seenLens = append(seenLens, payloadLen)
		mu.Unlock()
		return payloadLen + 200, time.Millisecond
	}
	cc := WrapAEADShaped(c, ck, dexote.NewPoly(ck.Secret, 1, "tx"), dexote.NewPoly(ck.Secret, 1, "rx"), 64, hook)
	sc := WrapAEAD(s, sk, dexote.NewPoly(sk.Secret, 1, "rx"), dexote.NewPoly(sk.Secret, 1, "tx"), 64)
	defer cc.Close()
	defer sc.Close()

	msg := make([]byte, 5000)
	rand.Read(msg)
	go io.Copy(sc, sc)
	go cc.Write(msg)

	got := make([]byte, len(msg))
	if _, err := io.ReadFull(cc, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(msg, got) {
		t.Fatal("shape-hook echo mismatch")
	}
	mu.Lock()
	defer mu.Unlock()
	if len(seenLens) == 0 {
		t.Fatal("shape-хук не вызывался")
	}
}

func TestAEADNilHookEqualsPlain(t *testing.T) {
	ck, sk := keysPair()
	c, s := net.Pipe()
	cc := WrapAEADShaped(c, ck, dexote.NewPoly(ck.Secret, 1, "tx"), dexote.NewPoly(ck.Secret, 1, "rx"), 64, nil)
	sc := WrapAEAD(s, sk, dexote.NewPoly(sk.Secret, 1, "rx"), dexote.NewPoly(sk.Secret, 1, "tx"), 64)
	defer cc.Close()
	defer sc.Close()

	msg := []byte("hello dexote shaper")
	go io.Copy(sc, sc)
	go cc.Write(msg)
	got := make([]byte, len(msg))
	if _, err := io.ReadFull(cc, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(msg, got) {
		t.Fatal("nil-hook roundtrip mismatch")
	}
}
