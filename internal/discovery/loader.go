package discovery

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
)

func DecodeEd25519PublicKey(s string) (ed25519.PublicKey, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil, errors.New("empty key")
	}
	for _, enc := range []*base64.Encoding{
		base64.RawStdEncoding,
		base64.StdEncoding,
		base64.RawURLEncoding,
		base64.URLEncoding,
	} {
		b, err := enc.DecodeString(s)
		if err != nil {
			continue
		}
		if len(b) == ed25519.PublicKeySize {
			return ed25519.PublicKey(b), nil
		}
	}
	return nil, errors.New("bad ed25519 public key")
}

func ParseSignedBootstrapJSON(raw string) (SignedBootstrap, error) {
	var b SignedBootstrap
	if err := json.Unmarshal([]byte(strings.TrimSpace(raw)), &b); err != nil {
		return SignedBootstrap{}, err
	}
	return b, nil
}
