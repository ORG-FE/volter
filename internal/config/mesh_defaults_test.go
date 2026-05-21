package config

import "testing"

func TestApplyMeshDefaultsFillsStun(t *testing.T) {
	m := &MeshConfig{Enabled: true}
	ApplyMeshDefaults(m)
	if len(m.STUN.Servers) < 2 {
		t.Fatalf("stun: %+v", m.STUN.Servers)
	}
	if m.Policy.MaxPeerHops != DefaultMeshMaxPeerHops {
		t.Fatalf("maxPeerHops %d", m.Policy.MaxPeerHops)
	}
	m.P2P.Enabled = true
	ApplyMeshDefaults(m)
	if !m.P2P.UseUDP || !m.P2P.UseQUIC || !m.P2P.UseTCP {
		t.Fatalf("p2p transports: %+v", m.P2P)
	}
}

func TestApplyMeshDefaultsVolunteer(t *testing.T) {
	m := &MeshConfig{Enabled: true, Volunteer: MeshVolunteerOptions{Enabled: true}}
	ApplyMeshDefaults(m)
	if m.Volunteer.PeerID == "" || m.Volunteer.UDPListen != "0.0.0.0:0" {
		t.Fatalf("volunteer: %+v", m.Volunteer)
	}
	if m.Volunteer.MaxConcurrent != DefaultMeshMaxConcurrent {
		t.Fatalf("maxConcurrent %d", m.Volunteer.MaxConcurrent)
	}
}

func TestApplyRelayDefaults(t *testing.T) {
	r := &RelayOptions{}
	ApplyRelayDefaults(r)
	if len(r.StunServers) < 2 {
		t.Fatalf("stun %v", r.StunServers)
	}
	if r.MaxPeerHops != DefaultMeshMaxPeerHops || !r.PeerPathFromDiscovery {
		t.Fatalf("%+v", r)
	}
	if r.PeerRelayUseTCP == nil || !*r.PeerRelayUseTCP {
		t.Fatal("expected peer tcp default on")
	}
}

func TestDefaultSTUNServerListDedupes(t *testing.T) {
	list := DefaultSTUNServerList()
	seen := make(map[string]struct{})
	for _, s := range list {
		if _, ok := seen[s]; ok {
			t.Fatalf("dup %s", s)
		}
		seen[s] = struct{}{}
	}
}

func TestApplyClusterProtectionDefaults(t *testing.T) {
	p := &ProtectionOptions{}
	ApplyClusterProtectionDefaults(p)
	if p.ClusterMapPath == "" || !p.RoutePlannerV2 {
		t.Fatalf("cluster defaults: %+v", p)
	}
	if p.ClusterSessionsPath == "" || p.ClusterClientsPath == "" {
		t.Fatal("cluster paths incomplete")
	}
}

func TestMeshToRelayAfterDefaults(t *testing.T) {
	m := &MeshConfig{Enabled: true, Volunteer: MeshVolunteerOptions{Enabled: true}, P2P: MeshP2POptions{Enabled: true}}
	ApplyMeshDefaults(m)
	r := MeshToRelayOptions(m)
	if r == nil || len(r.StunServers) == 0 || r.MaxPeerHops != DefaultMeshMaxPeerHops {
		t.Fatalf("%+v", r)
	}
}
