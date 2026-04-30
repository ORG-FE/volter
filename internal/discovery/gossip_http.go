package discovery

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

var gossipHTTP = &http.Client{Timeout: 45 * time.Second}

type gossipEnvelope struct {
	Nodes []RelayNode `json:"nodes"`
}

func FetchGossipHTTP(ctx context.Context, rawURL string) ([]RelayNode, error) {
	rawURL = strings.TrimSpace(rawURL)
	if rawURL == "" {
		return nil, fmt.Errorf("gossip: empty url")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	resp, err := gossipHTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		return nil, fmt.Errorf("gossip: http %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	var env gossipEnvelope
	if err := json.Unmarshal(body, &env); err == nil && len(env.Nodes) > 0 {
		return env.Nodes, nil
	}
	var flat []RelayNode
	if err := json.Unmarshal(body, &flat); err == nil && len(flat) > 0 {
		return flat, nil
	}
	if err := json.Unmarshal(body, &env); err != nil {
		return nil, fmt.Errorf("gossip decode: %w", err)
	}
	return env.Nodes, nil
}
