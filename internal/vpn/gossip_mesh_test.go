package vpn

import (
	"testing"

	"dev.c0redev.volter/internal/config"
)

func TestShouldRunGossipMesh(t *testing.T) {
	if shouldRunGossipMesh(nil) {
		t.Fatal("nil relay must be disabled")
	}
	if shouldRunGossipMesh(&config.RelayOptions{GossipEnabled: false, GossipPeers: []string{"https://x"}}) {
		t.Fatal("gossip must be gated by gossipEnabled")
	}
	if shouldRunGossipMesh(&config.RelayOptions{GossipEnabled: true}) {
		t.Fatal("gossip requires peers or dhtFindUrls")
	}
	if !shouldRunGossipMesh(&config.RelayOptions{GossipEnabled: true, GossipPeers: []string{"https://x"}}) {
		t.Fatal("gossip peer source must enable mesh")
	}
	if !shouldRunGossipMesh(&config.RelayOptions{GossipEnabled: true, DHTFindURLs: []string{"https://x/find"}}) {
		t.Fatal("dht find source must enable mesh")
	}
}
