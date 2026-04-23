package server

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/protocol"
)

type nonceCache struct {
	mu   sync.Mutex
	seen map[string]time.Time
}

func newNonceCache() *nonceCache {
	return &nonceCache{seen: make(map[string]time.Time)}
}

func (n *nonceCache) checkAndRemember(key string, window time.Duration) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	now := time.Now()
	for k, t := range n.seen {
		if now.Sub(t) > window {
			delete(n.seen, k)
		}
	}
	if _, ok := n.seen[key]; ok {
		return fmt.Errorf("replay")
	}
	n.seen[key] = now
	return nil
}

func parseClientOptsJSON(raw []byte) (config.ProtectionOptions, error) {
	var o config.ProtectionOptions
	if len(raw) == 0 {
		return o, fmt.Errorf("empty opts")
	}
	if err := json.Unmarshal(raw, &o); err != nil {
		return o, err
	}
	return o, nil
}

func parseClientNonce(s string) ([]byte, error) {
	s = trimSpace(s)
	if s == "" {
		return nil, fmt.Errorf("missing nonce")
	}
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err == nil && len(b) >= 8 {
		return b, nil
	}
	b, err = hex.DecodeString(s)
	if err != nil {
		return nil, err
	}
	if len(b) < 8 {
		return nil, fmt.Errorf("nonce short")
	}
	return b, nil
}

func trimSpace(s string) string {
	for len(s) > 0 && (s[0] == ' ' || s[0] == '\t' || s[0] == '\n') {
		s = s[1:]
	}
	return s
}

func validateClientOpts(cfg *Config, token string, o config.ProtectionOptions, nonces *nonceCache) error {
	if o.CapsVersion != protocol.CapsVersion {
		return fmt.Errorf("caps version")
	}
	if o.TransportMask == 0 {
		return fmt.Errorf("transport mask")
	}
	nonce, err := parseClientNonce(o.ClientNonce)
	if err != nil {
		return err
	}
	if o.ClientTsSec == 0 {
		return fmt.Errorf("timestamp")
	}
	skew := cfg.HandshakeSkew
	if skew <= 0 {
		skew = 2 * time.Minute
	}
	rw := cfg.ReplayWindow
	if rw <= 0 {
		rw = 90 * time.Second
	}
	ts := time.Unix(o.ClientTsSec, 0)
	if ts.Before(time.Now().Add(-skew-rw)) || ts.After(time.Now().Add(skew+rw)) {
		return fmt.Errorf("time skew")
	}
	key := token + ":" + hex.EncodeToString(nonce)
	if err := nonces.checkAndRemember(key, rw); err != nil {
		return err
	}
	return nil
}
