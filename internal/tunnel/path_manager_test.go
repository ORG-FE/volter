package tunnel

import (
	"net"
	"testing"

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
