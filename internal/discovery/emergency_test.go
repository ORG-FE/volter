package discovery

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"testing"
	"time"
)

func TestSignedEmergencyPolicyRoundTrip(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(nil)
	epoch := int64(1)
	exp := time.Now().Add(time.Hour).Unix()
	disable := true
	payload := emergencySigningPayload(epoch, exp, disable)
	sig := base64.StdEncoding.EncodeToString(ed25519.Sign(priv, payload))
	b, err := json.Marshal(SignedEmergencyPolicy{
		EpochSec: epoch, ExpiresAt: exp, DisablePeerRelay: disable, Signature: sig,
	})
	if err != nil {
		t.Fatal(err)
	}
	p, err := ParseSignedEmergencyPolicyJSON(string(b))
	if err != nil {
		t.Fatal(err)
	}
	if err := p.Verify(pub, time.Now()); err != nil {
		t.Fatal(err)
	}
}
