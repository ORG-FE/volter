package tunnel

import (
	"net"
	"testing"
	"time"

	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/dht"
	"dev.c0redev.volter/internal/discovery"
	"dev.c0redev.volter/internal/ice"
)

func TestPathManagerDegradeToTCP(t *testing.T) {
	pm := NewPathManager()
	dst := net.ParseIP("1.1.1.1")
	if dst == nil {
		t.Fatal("bad ip")
	}
	dec := pm.Decide(dst, true, true, false, false)
	if dec.PreferTCP {
		t.Fatalf("unexpected initial tcp decision")
	}
	pm.Record(dst, false, PathClassDirect, 1)
	pm.Record(dst, false, PathClassDirect, 1)
	pm.Record(dst, false, PathClassDirect, 1)
	dec = pm.Decide(dst, true, true, false, false)
	if !dec.PreferTCP {
		t.Fatalf("expected tcp preference after failures")
	}
}

func TestPathManagerForcedTCPWhenNoQUIC(t *testing.T) {
	pm := NewPathManager()
	dst := net.ParseIP("8.8.8.8")
	dec := pm.Decide(dst, true, false, false, false)
	if !dec.PreferTCP {
		t.Fatalf("expected tcp when quic disabled")
	}
}

func TestPathManagerAggressiveTwoFails(t *testing.T) {
	pm := NewPathManagerFromRelay(&config.RelayOptions{PathAggressive: true})
	dst := net.ParseIP("9.9.9.9")
	pm.Record(dst, false, PathClassDirect, 1)
	pm.Record(dst, false, PathClassDirect, 1)
	if !pm.Decide(dst, true, true, false, false).PreferTCP {
		t.Fatalf("aggressive: tcp after 2 fails")
	}
}

func TestPathManagerPeerPathWhenWeak(t *testing.T) {
	pm := NewPathManagerFromRelay(&config.RelayOptions{PeerPathFromDiscovery: true})
	dst := net.ParseIP("1.1.1.1")
	pm.SetGlobalCandidate(ice.CandidateSrflx)
	pm.Record(dst, false, PathClassDirect, 1)
	dht.DefaultTable().Insert(discovery.RelayNode{
		ID: "peer-test", Class: "peer", Endpoints: []string{"127.0.0.1:443"}, UpdatedAt: 100,
	})
	dec := pm.Decide(dst, true, true, true, false)
	if dec.PeerAddr == "" || dec.RelayClass != PathClassPeer {
		t.Fatalf("want peer path, got %+v", dec)
	}
}

func TestPathManagerForcePeerIgnoresHealthyDirectGate(t *testing.T) {
	pm := NewPathManagerFromRelay(&config.RelayOptions{PeerPathFromDiscovery: true})
	dst := net.ParseIP("1.1.1.1")
	pm.SetGlobalCandidate(ice.CandidateHost)
	dht.DefaultTable().Insert(discovery.RelayNode{
		ID: "peer-force", Class: "peer", Endpoints: []string{"127.0.0.1:8443"}, UpdatedAt: 100,
	})
	dec := pm.Decide(dst, true, true, true, true)
	if dec.RelayClass != PathClassPeer || dec.PeerAddr == "" {
		t.Fatalf("force peer expected, got %+v", dec)
	}
}

func TestPathManagerRespectsPeerTCPDisabled(t *testing.T) {
	noTCP := false
	pm := NewPathManagerFromRelay(&config.RelayOptions{PeerPathFromDiscovery: true, PeerRelayUseTCP: &noTCP})
	dst := net.ParseIP("1.1.1.2")
	pm.SetGlobalCandidate(ice.CandidateHost)
	dht.DefaultTable().Insert(discovery.RelayNode{
		ID: "peer-no-tcp", Class: "peer", Endpoints: []string{"127.0.0.1:9443"}, UpdatedAt: 100,
	})
	dec := pm.Decide(dst, true, true, true, true)
	if dec.PeerAddr != "" || len(dec.PeerTCPCandidates) != 0 {
		t.Fatalf("tcp peer path must be disabled, got %+v", dec)
	}
}

func TestPathManagerCapsPeerHopsFromRelay(t *testing.T) {
	pm := NewPathManagerFromRelay(&config.RelayOptions{
		PeerPathFromDiscovery: true,
		PeerRelayUseUDP:       true,
		MaxPeerHops:           1,
	})
	dst := net.ParseIP("1.1.1.3")
	pm.SetGlobalCandidate(ice.CandidateSrflx)
	for i := 0; i < 3; i++ {
		pm.Record(dst, false, PathClassDirect, 1)
	}
	for _, id := range []string{"peer-cap-a", "peer-cap-b", "peer-cap-c"} {
		dht.DefaultTable().Insert(discovery.RelayNode{
			ID: id, Class: "peer", Endpoints: []string{id + ":443"}, UpdatedAt: 100,
		})
	}
	dec := pm.Decide(dst, true, true, true, false)
	if dec.RelayClass != PathClassPeer || dec.MaxPeerHops != 1 || dec.PathTTL != 1 {
		t.Fatalf("peer hop cap lost: %+v", dec)
	}
	if len(dec.PeerUDPCandidates) > 1 || len(dec.PeerTCPCandidates) > 1 || len(dec.PeerQUICCandidates) > 1 {
		t.Fatalf("peer candidates exceed cap: %+v", dec)
	}
}

func TestPathManagerSkipsStalePeersFromRelayHealthMaxAge(t *testing.T) {
	SetGlobalClusterPeerTCPHints([]string{"stale-cluster-hint:443"})
	defer SetGlobalClusterPeerTCPHints(nil)
	pm := NewPathManagerFromRelay(&config.RelayOptions{
		PeerPathFromDiscovery: true,
		PeerRelayUseUDP:       true,
		HealthMaxAgeSec:       60,
	})
	dst := net.ParseIP("1.1.1.4")
	pm.SetGlobalCandidate(ice.CandidateSrflx)
	pm.Record(dst, false, PathClassDirect, 1)
	now := time.Now()
	dht.DefaultTable().Insert(discovery.RelayNode{
		ID: "peer-stale-health", Class: "peer", Endpoints: []string{"stale-health:443"}, UpdatedAt: now.Add(-2 * time.Hour).Unix(),
	})
	dht.DefaultTable().Insert(discovery.RelayNode{
		ID: "peer-fresh-health", Class: "peer", Endpoints: []string{"fresh-health:443"}, UpdatedAt: now.Unix(),
	})
	dec := pm.Decide(dst, true, true, true, false)
	if dec.RelayClass != PathClassPeer {
		t.Fatalf("expected fresh peer, got %+v", dec)
	}
	for _, c := range append(append([]string{}, dec.PeerTCPCandidates...), dec.PeerUDPCandidates...) {
		if c == "stale-health:443" {
			t.Fatalf("stale peer candidate was not filtered: %+v", dec)
		}
		if c == "stale-cluster-hint:443" {
			t.Fatalf("untimed cluster peer hint bypassed health max age: %+v", dec)
		}
	}
}
