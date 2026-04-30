package discovery

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"sort"
	"strings"
	"time"
)

type RelayNode struct {
	ID        string   `json:"id"`
	Endpoints []string `json:"endpoints"`
	Class     string   `json:"class"`
	UpdatedAt int64    `json:"updatedAt"`
	Srflx     string   `json:"srflx,omitempty"`
	Turn      string   `json:"turn,omitempty"`
	Stake     int      `json:"stake,omitempty"`
	Region    string   `json:"region,omitempty"`
	Quic      string   `json:"quic,omitempty"`
	DhtRPC    string   `json:"dhtRpc,omitempty"`
}

func RelayHasContact(n RelayNode) bool {
	for _, e := range n.Endpoints {
		if strings.TrimSpace(e) != "" {
			return true
		}
	}
	return strings.TrimSpace(n.Quic) != "" || strings.TrimSpace(n.Srflx) != "" ||
		strings.TrimSpace(n.DhtRPC) != ""
}

type SignedBootstrap struct {
	EpochSec  int64       `json:"epochSec"`
	ExpiresAt int64       `json:"expiresAt"`
	Nodes     []RelayNode `json:"nodes"`
	Signature string      `json:"signature"`
}

func SignBootstrap(epoch, exp int64, nodes []RelayNode, priv ed25519.PrivateKey) (SignedBootstrap, error) {
	if len(priv) != ed25519.PrivateKeySize {
		return SignedBootstrap{}, errors.New("bootstrap: bad private key size")
	}
	body, err := canonicalBootstrapBody(epoch, exp, nodes)
	if err != nil {
		return SignedBootstrap{}, err
	}
	sig := ed25519.Sign(priv, body)
	return SignedBootstrap{
		EpochSec:  epoch,
		ExpiresAt: exp,
		Nodes:     append([]RelayNode(nil), nodes...),
		Signature: base64.StdEncoding.EncodeToString(sig),
	}, nil
}

func (b SignedBootstrap) Verify(pub ed25519.PublicKey, now time.Time) error {
	if len(pub) != ed25519.PublicKeySize {
		return errors.New("bootstrap: bad public key")
	}
	if now.Unix() > b.ExpiresAt {
		return errors.New("bootstrap: expired")
	}
	body, err := canonicalBootstrapBody(b.EpochSec, b.ExpiresAt, b.Nodes)
	if err != nil {
		return err
	}
	sig, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b.Signature))
	if err != nil {
		return errors.New("bootstrap: bad signature format")
	}
	if !ed25519.Verify(pub, body, sig) {
		return errors.New("bootstrap: signature verify failed")
	}
	return nil
}

func FilterRelayNodesByClass(nodes []RelayNode, allowed []string) []RelayNode {
	if len(allowed) == 0 {
		return append([]RelayNode(nil), nodes...)
	}
	set := make(map[string]struct{})
	for _, a := range allowed {
		a = strings.TrimSpace(strings.ToLower(a))
		if a != "" {
			set[a] = struct{}{}
		}
	}
	if len(set) == 0 {
		return append([]RelayNode(nil), nodes...)
	}
	out := make([]RelayNode, 0, len(nodes))
	for _, n := range nodes {
		c := strings.TrimSpace(strings.ToLower(n.Class))
		if _, ok := set[c]; ok {
			out = append(out, n)
		}
	}
	return out
}

func FilterRelayNodesByStake(nodes []RelayNode, minStake int) []RelayNode {
	if minStake <= 0 {
		out := make([]RelayNode, len(nodes))
		copy(out, nodes)
		return out
	}
	out := make([]RelayNode, 0, len(nodes))
	for _, n := range nodes {
		if n.Stake >= minStake {
			out = append(out, n)
		}
	}
	return out
}

func MergeGossip(base []RelayNode, updates []RelayNode, now time.Time, maxAge time.Duration) []RelayNode {
	idx := make(map[string]RelayNode, len(base))
	for _, n := range base {
		if n.ID == "" {
			continue
		}
		idx[n.ID] = n
	}
	for _, n := range updates {
		if n.ID == "" || !RelayHasContact(n) {
			continue
		}
		old, ok := idx[n.ID]
		if !ok || n.UpdatedAt > old.UpdatedAt {
			idx[n.ID] = n
		}
	}
	cutoff := now.Add(-maxAge).Unix()
	out := make([]RelayNode, 0, len(idx))
	for _, n := range idx {
		if n.UpdatedAt < cutoff {
			continue
		}
		out = append(out, n)
	}
	sort.Slice(out, func(i, j int) bool {
		return out[i].ID < out[j].ID
	})
	return out
}

func RelayIndexDigest(nodes []RelayNode) (string, error) {
	b, err := canonicalBootstrapBody(0, 0, nodes)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(b)
	return base64.RawStdEncoding.EncodeToString(sum[:]), nil
}

func canonicalBootstrapBody(epoch, exp int64, nodes []RelayNode) ([]byte, error) {
	cp := append([]RelayNode(nil), nodes...)
	sort.Slice(cp, func(i, j int) bool { return cp[i].ID < cp[j].ID })
	payload := struct {
		EpochSec  int64       `json:"epochSec"`
		ExpiresAt int64       `json:"expiresAt"`
		Nodes     []RelayNode `json:"nodes"`
	}{
		EpochSec:  epoch,
		ExpiresAt: exp,
		Nodes:     cp,
	}
	return json.Marshal(payload)
}
