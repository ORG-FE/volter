package stake

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"sort"
	"strings"
	"time"

	"dev.c0redev.volter/internal/discovery"
)

type SignedStakeRegistry struct {
	EpochSec  int64        `json:"epochSec"`
	ExpiresAt int64        `json:"expiresAt"`
	Stakes    []stakeEntry `json:"stakes"`
	Signature string       `json:"signature"`
}

type stakeEntry struct {
	ID    string `json:"id"`
	Stake int    `json:"stake"`
}

func ApplyRegistry(ctx context.Context, nodes []discovery.RelayNode, url string, pubB64 string) []discovery.RelayNode {
	url = strings.TrimSpace(url)
	pubB64 = strings.TrimSpace(pubB64)
	if url == "" || pubB64 == "" || len(nodes) == 0 {
		return nodes
	}
	pub, err := discovery.DecodeEd25519PublicKey(pubB64)
	if err != nil {
		return nodes
	}
	sub, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(sub, http.MethodGet, url, nil)
	if err != nil {
		return nodes
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nodes
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nodes
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return nodes
	}
	var reg SignedStakeRegistry
	if err := json.Unmarshal(body, &reg); err != nil {
		return nodes
	}
	if err := verifyStakeRegistry(pub, reg, time.Now()); err != nil {
		return nodes
	}
	idx := make(map[string]int, len(reg.Stakes))
	for _, s := range reg.Stakes {
		s.ID = strings.TrimSpace(s.ID)
		if s.ID != "" {
			idx[s.ID] = s.Stake
		}
	}
	out := make([]discovery.RelayNode, len(nodes))
	copy(out, nodes)
	for i := range out {
		if v, ok := idx[out[i].ID]; ok && v >= 0 {
			out[i].Stake = v
		}
	}
	return out
}

func verifyStakeRegistry(pub ed25519.PublicKey, reg SignedStakeRegistry, now time.Time) error {
	if now.Unix() > reg.ExpiresAt {
		return errors.New("stake registry expired")
	}
	body, err := canonicalStakeBody(reg.EpochSec, reg.ExpiresAt, reg.Stakes)
	if err != nil {
		return err
	}
	sig, err := base64.StdEncoding.DecodeString(strings.TrimSpace(reg.Signature))
	if err != nil {
		return err
	}
	if !ed25519.Verify(pub, body, sig) {
		return errors.New("stake registry verify failed")
	}
	return nil
}

func canonicalStakeBody(epoch, exp int64, stakes []stakeEntry) ([]byte, error) {
	cp := append([]stakeEntry(nil), stakes...)
	for i := range cp {
		cp[i].ID = strings.TrimSpace(cp[i].ID)
	}
	sort.Slice(cp, func(i, j int) bool { return cp[i].ID < cp[j].ID })
	type payload struct {
		EpochSec  int64        `json:"epochSec"`
		ExpiresAt int64        `json:"expiresAt"`
		Stakes    []stakeEntry `json:"stakes"`
	}
	return json.Marshal(payload{EpochSec: epoch, ExpiresAt: exp, Stakes: cp})
}
