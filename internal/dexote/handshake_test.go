package dexote

import (
	"bytes"
	"net"
	"testing"
)

func TestHandshakeE2E(t *testing.T) {
	sScalar, sPub, err := GenerateServerKey()
	if err != nil {
		t.Fatal(err)
	}
	c, s := net.Pipe()
	defer c.Close()
	defer s.Close()

	slot := int64(12345)
	caps := []byte{0xCA, 0xFE, 0x01}
	token := "secret-token"
	opts := []byte(`{"sessionId":"x"}`)

	type res struct {
		keys    *Keys
		payload *ClientHelloPayload
		err     error
	}
	srvCh := make(chan res, 1)
	go func() {
		k, p, err := ServerHandshake(s, sScalar, sPub, slot, caps, NewMemReplayCache())
		srvCh <- res{k, p, err}
	}()

	ck, gotCaps, err := ClientHandshake(c, sPub, slot, ClientHelloPayload{Role: 2, Token: token, Opts: opts})
	if err != nil {
		t.Fatalf("client: %v", err)
	}
	sr := <-srvCh
	if sr.err != nil {
		t.Fatalf("server: %v", sr.err)
	}

	if !bytes.Equal(gotCaps, caps) {
		t.Fatalf("caps mismatch: %x vs %x", gotCaps, caps)
	}
	if sr.payload.Token != token {
		t.Fatalf("token mismatch: %q", sr.payload.Token)
	}
	if sr.payload.Role != 2 {
		t.Fatalf("role mismatch: %d", sr.payload.Role)
	}
	if !bytes.Equal(opts, sr.payload.Opts) {
		t.Fatalf("opts mismatch")
	}

	if !bytes.Equal(ck.TxKey, sr.keys.RxKey) || !bytes.Equal(ck.RxKey, sr.keys.TxKey) {
		t.Fatal("directional data keys not mirrored")
	}
	if !bytes.Equal(ck.TxLenKey, sr.keys.RxLenKey) || !bytes.Equal(ck.RxLenKey, sr.keys.TxLenKey) {
		t.Fatal("directional len keys not mirrored")
	}
	if !bytes.Equal(ck.Secret, sr.keys.Secret) {
		t.Fatal("session secret mismatch")
	}
}

func TestHandshakeBadMAC(t *testing.T) {
	sScalar, sPub, _ := GenerateServerKey()
	_, wrongPub, _ := GenerateServerKey()
	c, s := net.Pipe()
	defer c.Close()
	defer s.Close()

	errCh := make(chan error, 1)
	go func() {
		_, _, err := ServerHandshake(s, sScalar, sPub, 7, nil, NewMemReplayCache())
		errCh <- err
	}()

	go ClientHandshake(c, wrongPub, 7, ClientHelloPayload{Role: 2, Token: "x"})

	if err := <-errCh; err != ErrBadMAC {
		t.Fatalf("want ErrBadMAC, got %v", err)
	}
}

func TestReplayRejected(t *testing.T) {
	cache := NewMemReplayCache()
	nonce := []byte("0123456789abcdef")
	if !cache.Add(nonce, 100) {
		t.Fatal("first add should pass")
	}
	if cache.Add(nonce, 100) {
		t.Fatal("replay should be rejected")
	}
}

func TestPolyDeterministic(t *testing.T) {
	secret := bytes.Repeat([]byte{0xAB}, 32)
	a := NewPoly(secret, 5, "x")
	b := NewPoly(secret, 5, "x")
	for i := 0; i < 100; i++ {
		if a.IntRange(0, 1000) != b.IntRange(0, 1000) {
			t.Fatal("poly not deterministic")
		}
	}
}
