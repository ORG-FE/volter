package discovery

import (
	"crypto/ed25519"
	"encoding/base64"
	"testing"
)

func TestDecodeEd25519PublicKey(t *testing.T) {
	pub, _, _ := ed25519.GenerateKey(nil)
	raw := base64.RawStdEncoding.EncodeToString(pub)
	got, err := DecodeEd25519PublicKey(raw)
	if err != nil || string(got) != string(pub) {
		t.Fatalf("got err=%v pub=%v", err, got)
	}
}

func TestParseSignedBootstrapJSON(t *testing.T) {
	s := `{"epochSec":1,"expiresAt":9999999999,"nodes":[],"signature":"ab"}`
	b, err := ParseSignedBootstrapJSON(s)
	if err != nil || b.EpochSec != 1 {
		t.Fatalf("parse: %+v err=%v", b, err)
	}
}
