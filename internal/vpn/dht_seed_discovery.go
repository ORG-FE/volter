package vpn

import (
	"encoding/json"
	"net"
	"net/url"
	"strconv"
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
		ClusterListen int `json:"clusterListen"`
		Nodes         []struct {
			Endpoint string `json:"endpoint"`
			DhtRPC   string `json:"dhtRpc"`
		} `json:"nodes"`
	}
	if err := json.Unmarshal([]byte(raw), &doc); err != nil {
		return nil
	}
	out := make([]string, 0, len(doc.Nodes))
	for _, n := range doc.Nodes {
		if hp := strings.TrimSpace(n.DhtRPC); hp != "" {
			out = append(out, hp)
			continue
		}
		host, port := hostPortFromEndpoint(n.Endpoint)
		if host == "" {
			continue
		}
		if port <= 0 {
			port = doc.ClusterListen
		}
		if port <= 0 {
			continue
		}
		out = append(out, net.JoinHostPort(host, strconv.Itoa(port)))
	}
	return out
}

func hostPortFromEndpoint(ep string) (host string, port int) {
	ep = strings.TrimSpace(ep)
	if ep == "" {
		return "", 0
	}
	if !strings.HasPrefix(ep, "http://") && !strings.HasPrefix(ep, "https://") {
		ep = "http://" + ep
	}
	u, err := url.Parse(ep)
	if err != nil {
		return "", 0
	}
	host = strings.TrimSpace(u.Hostname())
	if host == "" {
		return "", 0
	}
	if p := u.Port(); p != "" {
		if v, err := strconv.Atoi(p); err == nil {
			return host, v
		}
	}
	return host, 0
}
