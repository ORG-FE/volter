package discovery

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

type SignedEmergencyPolicy struct {
	EpochSec         int64  `json:"epochSec"`
	ExpiresAt        int64  `json:"expiresAt"`
	DisablePeerRelay bool   `json:"disablePeerRelay"`
	Signature        string `json:"signature"`
}

func ParseSignedEmergencyPolicyJSON(raw string) (SignedEmergencyPolicy, error) {
	var p SignedEmergencyPolicy
	if err := json.Unmarshal([]byte(strings.TrimSpace(raw)), &p); err != nil {
		return SignedEmergencyPolicy{}, err
	}
	return p, nil
}

func (p SignedEmergencyPolicy) Verify(pub ed25519.PublicKey, now time.Time) error {
	if len(pub) != ed25519.PublicKeySize {
		return errors.New("policy: bad public key")
	}
	if now.Unix() > p.ExpiresAt {
		return errors.New("policy: expired")
	}
	payload := emergencySigningPayload(p.EpochSec, p.ExpiresAt, p.DisablePeerRelay)
	sig, err := base64.StdEncoding.DecodeString(strings.TrimSpace(p.Signature))
	if err != nil {
		return errors.New("policy: bad signature encoding")
	}
	if !ed25519.Verify(pub, payload, sig) {
		return errors.New("policy: signature verify failed")
	}
	return nil
}

func emergencySigningPayload(epoch, exp int64, disable bool) []byte {
	return fmt.Appendf(nil, "policy-v1|%d|%d|%t", epoch, exp, disable)
}
