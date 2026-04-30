package stake

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"

	"dev.c0redev.volter/internal/discovery"
)

type merkleFile struct {
	Root  string                 `json:"root"`
	Nodes map[string]merkleEntry `json:"nodes"`
}

type merkleEntry struct {
	Stake int      `json:"stake"`
	Proof []string `json:"proof"`
}

func leafHash(nodeID string, stake int) []byte {
	h := sha256.New()
	h.Write([]byte{0x00})
	h.Write([]byte(nodeID))
	var b [4]byte
	binary.BigEndian.PutUint32(b[:], uint32(stake))
	h.Write(b[:])
	return h.Sum(nil)
}

func parentHash(a, b []byte) []byte {
	if bytes.Compare(a, b) <= 0 {
		sum := sha256.Sum256(append(append([]byte{}, a...), b...))
		return sum[:]
	}
	sum := sha256.Sum256(append(append([]byte{}, b...), a...))
	return sum[:]
}

func verifyToRoot(root []byte, leaf []byte, proofHex []string) bool {
	cur := append([]byte(nil), leaf...)
	for _, ph := range proofHex {
		ph = strings.TrimSpace(ph)
		if ph == "" {
			return false
		}
		sib, err := hex.DecodeString(ph)
		if err != nil || len(sib) != 32 {
			return false
		}
		cur = parentHash(cur, sib)
	}
	return bytes.Equal(cur, root)
}

func decodeHex32(s string) ([]byte, error) {
	s = strings.TrimSpace(s)
	s = strings.TrimPrefix(s, "0x")
	s = strings.TrimPrefix(s, "0X")
	return hex.DecodeString(s)
}

func FetchMerkleRootFromURL(ctx context.Context, url string) ([]byte, error) {
	url = strings.TrimSpace(url)
	if url == "" {
		return nil, nil
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("merkle root http status %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	var jr struct {
		Root string `json:"root"`
	}
	if err := json.Unmarshal(body, &jr); err != nil {
		return nil, err
	}
	root, err := decodeHex32(jr.Root)
	if err != nil || len(root) != 32 {
		return nil, err
	}
	return root, nil
}

func mergeVerifiedOverrides(nodes []discovery.RelayNode, mf merkleFile, root []byte) []discovery.RelayNode {
	out := append([]discovery.RelayNode(nil), nodes...)
	for i := range out {
		id := strings.TrimSpace(out[i].ID)
		ent, ok := mf.Nodes[id]
		if !ok || ent.Stake <= 0 {
			continue
		}
		lh := leafHash(id, ent.Stake)
		if !verifyToRoot(root, lh, ent.Proof) {
			continue
		}
		out[i].Stake = ent.Stake
	}
	return out
}

func MergeMerkle(nodes []discovery.RelayNode, path string) []discovery.RelayNode {
	return MergeMerkleFromSources(context.Background(), nodes, path, "")
}

func MergeMerkleFromSources(ctx context.Context, nodes []discovery.RelayNode, path, merkleRootURL string) []discovery.RelayNode {
	path = strings.TrimSpace(path)
	if path == "" || len(nodes) == 0 {
		return nodes
	}
	b, err := os.ReadFile(path)
	if err != nil || len(b) > 2<<20 {
		return nodes
	}
	var mf merkleFile
	if err := json.Unmarshal(b, &mf); err != nil || len(mf.Nodes) == 0 {
		return nodes
	}
	var root []byte
	if u := strings.TrimSpace(merkleRootURL); u != "" {
		r, err := FetchMerkleRootFromURL(ctx, u)
		if err == nil && len(r) == 32 {
			root = r
		}
	}
	if len(root) != 32 {
		rf, err := decodeHex32(mf.Root)
		if err != nil || len(rf) != 32 {
			return nodes
		}
		root = rf
	}
	return mergeVerifiedOverrides(nodes, mf, root)
}
