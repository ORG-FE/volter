package main

import (
	"net"
	"net/url"
	"strings"

	"dev.c0redev.volter/internal/config"
)

func relayBypassHosts(relay *config.RelayOptions) []string {
	if relay == nil {
		return nil
	}
	seen := make(map[string]struct{})
	out := make([]string, 0, 16)
	add := func(h string) {
		h = strings.TrimSpace(h)
		if h == "" {
			return
		}
		k := strings.ToLower(h)
		if _, ok := seen[k]; ok {
			return
		}
		seen[k] = struct{}{}
		out = append(out, h)
	}
	for _, hp := range relay.DhtRpcSeedPeers {
		if host, _, err := net.SplitHostPort(strings.TrimSpace(hp)); err == nil && host != "" {
			add(host)
		}
	}
	for _, s := range relay.StunServers {
		spec := strings.TrimSpace(s)
		spec = strings.TrimPrefix(spec, "udp://")
		spec = strings.TrimPrefix(spec, "tcp://")
		if host, _, err := net.SplitHostPort(spec); err == nil && host != "" {
			add(host)
		}
	}
	for _, u := range relay.TurnURLs {
		if h := parseURLHost(u); h != "" {
			add(h)
		}
	}
	if h := parseURLHost(relay.DiscoveryURL); h != "" {
		add(h)
	}
	for _, u := range relay.DHTFindURLs {
		if h := parseURLHost(u); h != "" {
			add(h)
		}
	}
	for _, u := range relay.GossipPeers {
		if h := parseURLHost(u); h != "" {
			add(h)
		}
	}
	return out
}

func parseURLHost(raw string) string {
	s := strings.TrimSpace(raw)
	if s == "" {
		return ""
	}
	if !strings.Contains(s, "://") {
		s = "http://" + s
	}
	u, err := url.Parse(s)
	if err != nil || strings.TrimSpace(u.Hostname()) == "" {
		return ""
	}
	return strings.TrimSpace(u.Hostname())
}

func resolveBypassHosts(hosts []string) []net.IP {
	var out []net.IP
	for _, h := range hosts {
		ips, err := net.LookupIP(strings.TrimSpace(h))
		if err != nil {
			continue
		}
		out = append(out, ips...)
	}
	return out
}
