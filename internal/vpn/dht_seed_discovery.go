package vpn

import (
	"encoding/json"
	"net"
	"strings"

	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/dht"
)

func dhtRPCSeeds(relay *config.RelayOptions) []string {
	seen := make(map[string]struct{})
	out := make([]string, 0, 32)
	add := func(v string) {
		v = strings.TrimSpace(v)
		if v == "" {
			return
		}
		if _, _, err := net.SplitHostPort(v); err != nil {
			return
		}
		key := strings.ToLower(v)
		if _, ok := seen[key]; ok {
			return
		}
		seen[key] = struct{}{}
		out = append(out, v)
	}
	if relay != nil {
		for _, s := range relay.DhtRpcSeedPeers {
			add(s)
		}
	}
	for _, n := range dht.DefaultTable().Nearest(64) {
		add(n.DhtRPC)
	}
	for _, s := range clusterMapSeeds() {
		add(s)
	}
	return out
}

func clusterMapSeeds() []string {
	raw := strings.TrimSpace(LastClusterMap())
	if raw == "" {
		return nil
	}
	var doc struct {
		Nodes []struct {
			DhtRPC string `json:"dhtRpc"`
		} `json:"nodes"`
	}
	if err := json.Unmarshal([]byte(raw), &doc); err != nil {
		return nil
	}
	out := make([]string, 0, len(doc.Nodes))
	for _, n := range doc.Nodes {
		if hp := strings.TrimSpace(n.DhtRPC); hp != "" {
			out = append(out, hp)
		}
	}
	return out
}
