package config

import (
	"encoding/json"
	"testing"
)

func TestMeshToRelayOptionsKeepsVolunteerSeparate(t *testing.T) {
	mesh := &MeshConfig{
		Enabled: true,
		Volunteer: MeshVolunteerOptions{
			Enabled:       false,
			PeerID:        "peer-a",
			PrivateKey:    "priv-a",
			UDPListen:     "0.0.0.0:0",
			MaxConcurrent: 16,
			BudgetKbps:    768,
		},
		P2P: MeshP2POptions{Enabled: true, UseUDP: true, UseQUIC: true},
		STUN: MeshSTUNOptions{
			Enabled:      true,
			Servers:      []string{"stun.l.google.com:19302"},
			PublishSrflx: true,
		},
		Discovery: MeshDiscoveryOptions{
			DhtRpcSeedPeers: []string{"1.2.3.4:4001"},
			DhtRpcSecret:    "s",
		},
		Policy: MeshPolicyOptions{PathAggressive: true, PathCooldownMs: 1000, BudgetKbps: 2048, MaxPeerHops: 2},
	}

	r := MeshToRelayOptions(mesh)
	if r == nil {
		t.Fatal("expected relay options")
	}
	if r.PeerRelayUDPListen != "" {
		t.Fatalf("volunteer off must not listen: %+v", r)
	}
	if !r.PeerPathFromDiscovery || !r.PeerRelayUseUDP || !r.PeerRelayUseQUIC {
		t.Fatalf("p2p route options lost: %+v", r)
	}
	if r.PrivateKey != "priv-a" || r.MaxPeerHops != 2 || r.BudgetKbps != 2048 || r.PeerRelayBudgetKbps != 768 {
		t.Fatalf("mesh private key or policy lost: %+v", r)
	}
	if r.DhtRpcSecret != "s" || len(r.DhtRpcSeedPeers) != 1 {
		t.Fatalf("discovery lost: %+v", r)
	}

	mesh.Volunteer.Enabled = true
	r = MeshToRelayOptions(mesh)
	if r.PeerRelayUDPListen != "0.0.0.0:0" {
		t.Fatalf("volunteer on must listen: %+v", r)
	}
	if !r.DhtPublishSrflx {
		t.Fatalf("volunteer should publish srflx when requested: %+v", r)
	}
}

func TestMeshToRelayOptionsRespectsStunDisabled(t *testing.T) {
	mesh := &MeshConfig{
		Enabled:   true,
		Volunteer: MeshVolunteerOptions{Enabled: true, UDPListen: "0.0.0.0:0"},
		P2P:       MeshP2POptions{Enabled: true, UseUDP: true},
		STUN: MeshSTUNOptions{
			Enabled:               false,
			Servers:               []string{"stun.l.google.com:19302"},
			PublishSrflx:          true,
			SymmetricNatHolePunch: true,
		},
	}
	r := MeshToRelayOptions(mesh)
	if len(r.StunServers) != 0 || r.DhtPublishSrflx || r.SymmetricNatHolePunch {
		t.Fatalf("stun disabled must clear stun behavior: %+v", r)
	}
}

func TestMeshJSONDefaultsMatchAndroid(t *testing.T) {
	var cfg Config
	if err := json.Unmarshal([]byte(`{"server":"h:1","token":"t","mesh":{"enabled":true,"p2p":{"enabled":true},"stun":{},"serverRelay":{}}}`), &cfg); err != nil {
		t.Fatal(err)
	}
	if cfg.Mesh == nil || !cfg.Mesh.STUN.Enabled || !cfg.Mesh.ServerRelay.Enabled {
		t.Fatalf("mesh defaults lost: %+v", cfg.Mesh)
	}
	if !cfg.Mesh.P2P.UseUDP || !cfg.Mesh.P2P.UseQUIC || !cfg.Mesh.P2P.UseTCP {
		t.Fatalf("p2p defaults lost: %+v", cfg.Mesh.P2P)
	}
}

func TestMeshJSONKeepsFalseDefaultTrueFlags(t *testing.T) {
	cfg := Config{
		Server: "h:1",
		Token:  "t",
		Mesh: &MeshConfig{
			Enabled:     true,
			P2P:         MeshP2POptions{Enabled: true, UseUDP: true, UseQUIC: true, UseTCP: false},
			STUN:        MeshSTUNOptions{Enabled: false},
			ServerRelay: MeshServerRelayOptions{Enabled: false},
		},
	}
	b, err := json.Marshal(cfg)
	if err != nil {
		t.Fatal(err)
	}
	var out Config
	if err := json.Unmarshal(b, &out); err != nil {
		t.Fatal(err)
	}
	if out.Mesh == nil || out.Mesh.P2P.UseTCP || out.Mesh.STUN.Enabled || out.Mesh.ServerRelay.Enabled {
		t.Fatalf("false mesh flags lost after json round-trip: %s -> %+v", string(b), out.Mesh)
	}
}

func TestMeshToRelayOptionsRespectsPeerTCPDisabled(t *testing.T) {
	mesh := &MeshConfig{
		Enabled: true,
		P2P:     MeshP2POptions{Enabled: true, UseUDP: true, UseQUIC: true, UseTCP: false},
	}
	r := MeshToRelayOptions(mesh)
	if r.PeerRelayUseTCP == nil || *r.PeerRelayUseTCP {
		t.Fatalf("useTcp=false must map to relay tcp disabled: %+v", r)
	}
}

func TestEffectiveRelayOptionsPrefersMesh(t *testing.T) {
	cfg := Config{
		Mesh:  &MeshConfig{Enabled: true, P2P: MeshP2POptions{Enabled: true, UseUDP: true, UseQUIC: true, UseTCP: true}},
		Relay: &RelayOptions{PeerID: "legacy"},
	}
	r := EffectiveRelayOptions(&cfg)
	if r == nil || r.PeerID == "legacy" {
		t.Fatalf("mesh must win over legacy relay: %+v", r)
	}
	cfg.Mesh.Enabled = false
	r = EffectiveRelayOptions(&cfg)
	if r == nil || r.PeerID != "legacy" {
		t.Fatalf("legacy relay fallback lost: %+v", r)
	}
}

func TestRelayOptionsToMeshRoundTripEssentials(t *testing.T) {
	noTCP := false
	r := &RelayOptions{
		PeerID:                "peer-a",
		PeerPathFromDiscovery: true,
		PeerRelayUseUDP:       true,
		PeerRelayUseQUIC:      true,
		PeerRelayUseTCP:       &noTCP,
		PeerRelayUDPListen:    "0.0.0.0:0",
		PeerRelayUDPAdvertise: "1.2.3.4:4001",
		BootstrapPubKey:       "pub",
		PrivateKey:            "priv",
		BudgetKbps:            2048,
		PeerRelayBudgetKbps:   768,
		MaxPeerHops:           2,
		HealthMaxAgeSec:       120,
		StunServers:           []string{"stun:3478"},
		DhtRpcSeedPeers:       []string{"seed:4001"},
	}
	mesh := RelayOptionsToMesh(r, nil)
	if mesh == nil || !mesh.Enabled || !mesh.Volunteer.Enabled || mesh.Volunteer.PeerID != "peer-a" {
		t.Fatalf("bad mesh: %+v", mesh)
	}
	if !mesh.P2P.Enabled || !mesh.P2P.UseUDP || !mesh.P2P.UseQUIC || mesh.P2P.UseTCP {
		t.Fatalf("bad p2p: %+v", mesh.P2P)
	}
	if mesh.ServerRelay.BootstrapPubKey != "pub" || len(mesh.Discovery.DhtRpcSeedPeers) != 1 {
		t.Fatalf("bad discovery: %+v", mesh)
	}
	if mesh.Volunteer.PrivateKey != "priv" || mesh.Volunteer.BudgetKbps != 768 || mesh.Policy.BudgetKbps != 2048 ||
		mesh.Policy.MaxPeerHops != 2 || mesh.Policy.HealthMaxAgeSec != 120 {
		t.Fatalf("bad volunteer/policy: %+v", mesh)
	}
}

func TestMeshJSONPrivateKeyMapsToEffectiveRelay(t *testing.T) {
	var cfg Config
	if err := json.Unmarshal([]byte(`{"server":"h:1","token":"t","mesh":{"enabled":true,"volunteer":{"enabled":true,"peerId":"p","privateKey":"priv","udpListen":"0.0.0.0:0"},"policy":{"maxPeerHops":2}}}`), &cfg); err != nil {
		t.Fatal(err)
	}
	r := EffectiveRelayOptions(&cfg)
	if r == nil || r.PrivateKey != "priv" || r.MaxPeerHops != 2 {
		t.Fatalf("effective relay lost mesh private key or policy: %+v", r)
	}
}

func TestMigrateLegacyRelayToMeshInPlace(t *testing.T) {
	cfg := Config{
		Relay: &RelayOptions{
			PeerID:                "peer-a",
			PeerPathFromDiscovery: true,
			PeerRelayUseUDP:       true,
			PeerRelayUDPListen:    "0.0.0.0:0",
			TurnURLs:              []string{"turn:legacy"},
			EmergencyPolicyURL:    "https://e",
		},
	}
	if !MigrateLegacyRelayToMeshInPlace(&cfg) {
		t.Fatal("expected migration")
	}
	if cfg.Mesh == nil || !cfg.Mesh.Enabled || cfg.Mesh.Volunteer.PeerID != "peer-a" {
		t.Fatalf("mesh not migrated: %+v", cfg.Mesh)
	}
	if cfg.Relay == nil || len(cfg.Relay.TurnURLs) != 1 || cfg.Relay.EmergencyPolicyURL == "" {
		t.Fatalf("carry-over lost: %+v", cfg.Relay)
	}
}

func TestMigrateLegacyRelayDoesNotEnableEmptyRelay(t *testing.T) {
	cfg := Config{Relay: &RelayOptions{}}
	if MigrateLegacyRelayToMeshInPlace(&cfg) {
		t.Fatal("empty relay must not migrate")
	}
	if cfg.Mesh != nil && cfg.Mesh.Enabled {
		t.Fatalf("empty relay enabled mesh: %+v", cfg.Mesh)
	}
}

func TestMigrateLegacyRelayDoesNotEnableCarryOverOnlyRelay(t *testing.T) {
	cfg := Config{Relay: &RelayOptions{TurnURLs: []string{"turn:legacy"}, EmergencyPolicyURL: "https://e"}}
	if MigrateLegacyRelayToMeshInPlace(&cfg) {
		t.Fatal("carry-over only relay must not migrate")
	}
	if cfg.Mesh != nil && cfg.Mesh.Enabled {
		t.Fatalf("carry-over enabled mesh: %+v", cfg.Mesh)
	}
	if cfg.Relay == nil || len(cfg.Relay.TurnURLs) != 1 || cfg.Relay.EmergencyPolicyURL == "" {
		t.Fatalf("carry-over lost: %+v", cfg.Relay)
	}
}

func TestEffectiveRelayOptionsMergesCarryOver(t *testing.T) {
	cfg := Config{
		Mesh:  &MeshConfig{Enabled: true, P2P: MeshP2POptions{Enabled: true, UseUDP: true, UseQUIC: true, UseTCP: true}},
		Relay: &RelayOptions{TurnURLs: []string{"turn:legacy"}, EmergencyPolicyURL: "https://e"},
	}
	r := EffectiveRelayOptions(&cfg)
	if r == nil || len(r.TurnURLs) != 1 || r.EmergencyPolicyURL == "" {
		t.Fatalf("carry-over not merged: %+v", r)
	}
}

func TestLegacyCarryOverKeepsGeoAndStakeFields(t *testing.T) {
	cfg := Config{
		Relay: &RelayOptions{
			PeerID:                "peer-a",
			PeerPathFromDiscovery: true,
			GeoAllowCountries:     []string{"RU"},
			StakeRegistryURL:      "https://stake",
			StakeMerkleRootURL:    "https://merkle",
		},
	}
	if !MigrateLegacyRelayToMeshInPlace(&cfg) {
		t.Fatal("expected migration")
	}
	if cfg.Relay == nil || len(cfg.Relay.GeoAllowCountries) != 1 ||
		cfg.Relay.StakeRegistryURL == "" || cfg.Relay.StakeMerkleRootURL == "" {
		t.Fatalf("geo/stake carry-over lost: %+v", cfg.Relay)
	}
}
