package stake

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"time"

	"dev.c0redev.volter/internal/discovery"
)

func MergeHTTPBonus(ctx context.Context, nodes []discovery.RelayNode, url string) []discovery.RelayNode {
	url = strings.TrimSpace(url)
	if url == "" || len(nodes) == 0 {
		return nodes
	}
	sub, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(sub, http.MethodGet, url, nil)
	if err != nil {
		return nodes
	}
	req.Header.Set("Accept", "application/json")
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
	var doc struct {
		Bonuses map[string]int `json:"bonuses"`
	}
	if err := json.Unmarshal(body, &doc); err != nil || len(doc.Bonuses) == 0 {
		return nodes
	}
	out := make([]discovery.RelayNode, len(nodes))
	copy(out, nodes)
	for i := range out {
		id := strings.TrimSpace(out[i].ID)
		if v, ok := doc.Bonuses[id]; ok && v >= 0 {
			out[i].Stake += v
		}
	}
	return out
}
