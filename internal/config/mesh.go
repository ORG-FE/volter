package config

import "encoding/json"

type MeshConfig struct {
	Enabled     bool                   `json:"enabled"`
	Volunteer   MeshVolunteerOptions   `json:"volunteer,omitempty"`
	P2P         MeshP2POptions         `json:"p2p,omitempty"`
	ServerRelay MeshServerRelayOptions `json:"serverRelay,omitempty"`
	STUN        MeshSTUNOptions        `json:"stun,omitempty"`
	Discovery   MeshDiscoveryOptions   `json:"discovery,omitempty"`
	Policy      MeshPolicyOptions      `json:"policy,omitempty"`
}

func (m *MeshConfig) UnmarshalJSON(b []byte) error {
	type alias MeshConfig
	var a alias
	if err := json.Unmarshal(b, &a); err != nil {
		return err
	}
	if !jsonHas(b, "stun", "enabled") {
		a.STUN.Enabled = true
	}
	if !jsonHas(b, "serverRelay", "enabled") {
		a.ServerRelay.Enabled = true
	}
	if !jsonHas(b, "p2p", "useUdp") {
		a.P2P.UseUDP = true
	}
	if !jsonHas(b, "p2p", "useQuic") {
		a.P2P.UseQUIC = true
	}
	if !jsonHas(b, "p2p", "useTcp") {
		a.P2P.UseTCP = true
	}
	*m = MeshConfig(a)
	return nil
}

func jsonHas(b []byte, obj, key string) bool {
	var root map[string]json.RawMessage
	if json.Unmarshal(b, &root) != nil {
		return false
	}
	raw, ok := root[obj]
	if !ok {
		return false
	}
	var child map[string]json.RawMessage
	if json.Unmarshal(raw, &child) != nil {
		return false
	}
	_, ok = child[key]
	return ok
}

type MeshVolunteerOptions struct {
	Enabled       bool   `json:"enabled,omitempty"`
	PeerID        string `json:"peerId,omitempty"`
	PrivateKey    string `json:"privateKey,omitempty"`
	UDPListen     string `json:"udpListen,omitempty"`
	UDPAdvertise  string `json:"udpAdvertise,omitempty"`
	MaxConcurrent int    `json:"maxConcurrent,omitempty"`
	BudgetKbps    int    `json:"budgetKbps,omitempty"`
}

type MeshP2POptions struct {
	Enabled        bool   `json:"enabled,omitempty"`
	UseUDP         bool   `json:"useUdp"`
	UseQUIC        bool   `json:"useQuic"`
	UseTCP         bool   `json:"useTcp"`
	QuicServerName string `json:"quicServerName,omitempty"`
}

type MeshServerRelayOptions struct {
	Enabled         bool     `json:"enabled"`
	AllowedClasses  []string `json:"allowedClasses,omitempty"`
	DiscoveryURL    string   `json:"discoveryUrl,omitempty"`
	BootstrapPubKey string   `json:"bootstrapPubKey,omitempty"`
}

type MeshSTUNOptions struct {
	Enabled               bool     `json:"enabled"`
	Servers               []string `json:"servers,omitempty"`
	PublishSrflx          bool     `json:"publishSrflx,omitempty"`
	SymmetricNatHolePunch bool     `json:"symmetricNatHolePunch,omitempty"`
}

type MeshDiscoveryOptions struct {
	GossipEnabled      bool     `json:"gossipEnabled,omitempty"`
	GossipPeers        []string `json:"gossipPeers,omitempty"`
	GossipIntervalSec  int      `json:"gossipIntervalSec,omitempty"`
	GossipMaxAgeSec    int      `json:"gossipMaxAgeSec,omitempty"`
	DHTFindURLs        []string `json:"dhtFindUrls,omitempty"`
	DhtRpcListenUDP    string   `json:"dhtRpcListenUdp,omitempty"`
	DhtRpcSecret       string   `json:"dhtRpcSecret,omitempty"`
	DhtRpcSeedPeers    []string `json:"dhtRpcSeedPeers,omitempty"`
	DhtRpcIntervalSec  int      `json:"dhtRpcIntervalSec,omitempty"`
	DhtRpcFindK        int      `json:"dhtRpcFindK,omitempty"`
	DhtIterativeRounds int      `json:"dhtIterativeRounds,omitempty"`
	DhtIterativeAlpha  int      `json:"dhtIterativeAlpha,omitempty"`
}

type MeshPolicyOptions struct {
	RouteMode       string `json:"routeMode,omitempty"`
	MaxPeerHops     int    `json:"maxPeerHops,omitempty"`
	BudgetKbps      int    `json:"budgetKbps,omitempty"`
	PathAggressive  bool   `json:"pathAggressive,omitempty"`
	PathCooldownMs  int    `json:"pathCooldownMs,omitempty"`
	HealthMaxAgeSec int    `json:"healthMaxAgeSec,omitempty"`
}

func (m *MeshConfig) VolunteerEnabled() bool {
	return m != nil && m.Enabled && m.Volunteer.Enabled
}

func (m *MeshConfig) P2PEnabled() bool {
	return m != nil && m.Enabled && m.P2P.Enabled
}

func (m *MeshConfig) ServerRelayEnabled() bool {
	return m != nil && m.Enabled && m.ServerRelay.Enabled
}

func MeshToRelayOptions(m *MeshConfig) *RelayOptions {
	if m == nil || !m.Enabled {
		return nil
	}
	r := &RelayOptions{
		PeerID:                m.Volunteer.PeerID,
		PrivateKey:            m.Volunteer.PrivateKey,
		MaxConcurrent:         m.Volunteer.MaxConcurrent,
		BudgetKbps:            m.Policy.BudgetKbps,
		PeerRelayBudgetKbps:   m.Volunteer.BudgetKbps,
		MaxPeerHops:           m.Policy.MaxPeerHops,
		HealthMaxAgeSec:       m.Policy.HealthMaxAgeSec,
		PathAggressive:        m.Policy.PathAggressive,
		PathCooldownMs:        m.Policy.PathCooldownMs,
		GossipEnabled:         m.Discovery.GossipEnabled,
		GossipPeers:           append([]string(nil), m.Discovery.GossipPeers...),
		GossipIntervalSec:     m.Discovery.GossipIntervalSec,
		GossipMaxAgeSec:       m.Discovery.GossipMaxAgeSec,
		PeerPathFromDiscovery: m.P2P.Enabled,
		PeerRelayUseQUIC:      m.P2P.Enabled && m.P2P.UseQUIC,
		PeerRelayUseUDP:       m.P2P.Enabled && m.P2P.UseUDP,
		PeerRelayUseTCP:       boolPtr(m.P2P.Enabled && m.P2P.UseTCP),
		PeerQuicServerName:    m.P2P.QuicServerName,
		DHTFindURLs:           append([]string(nil), m.Discovery.DHTFindURLs...),
		DhtRpcListenUDP:       m.Discovery.DhtRpcListenUDP,
		DhtRpcSecret:          m.Discovery.DhtRpcSecret,
		DhtRpcSeedPeers:       append([]string(nil), m.Discovery.DhtRpcSeedPeers...),
		DhtRpcIntervalSec:     m.Discovery.DhtRpcIntervalSec,
		DhtRpcFindK:           m.Discovery.DhtRpcFindK,
		DhtIterativeRounds:    m.Discovery.DhtIterativeRounds,
		DhtIterativeAlpha:     m.Discovery.DhtIterativeAlpha,
	}
	if m.STUN.Enabled {
		r.StunServers = append([]string(nil), m.STUN.Servers...)
	}
	if m.ServerRelay.Enabled {
		r.AllowedClasses = append([]string(nil), m.ServerRelay.AllowedClasses...)
		r.DiscoveryURL = m.ServerRelay.DiscoveryURL
		r.BootstrapPubKey = m.ServerRelay.BootstrapPubKey
	}
	if m.Volunteer.Enabled {
		r.PeerRelayUDPListen = m.Volunteer.UDPListen
		r.PeerRelayUDPAdvertise = m.Volunteer.UDPAdvertise
		if m.STUN.Enabled {
			r.DhtPublishSrflx = m.STUN.PublishSrflx
			r.SymmetricNatHolePunch = m.STUN.SymmetricNatHolePunch
		}
	}
	return r
}

func boolPtr(v bool) *bool { return &v }

func EffectiveRelayOptions(cfg *Config) *RelayOptions {
	if cfg == nil {
		return nil
	}
	if cfg.Mesh != nil && cfg.Mesh.Enabled {
		return RelayOptionsWithMeshOverlay(cfg.Relay, cfg.Mesh)
	}
	return cfg.Relay
}

func MigrateLegacyRelayToMeshInPlace(cfg *Config) bool {
	if cfg == nil || cfg.Relay == nil {
		return false
	}
	if cfg.Mesh != nil && cfg.Mesh.Enabled {
		return false
	}
	if !RelayOptionsHasMeshProfileData(cfg.Relay) {
		return false
	}
	cfg.Mesh = RelayOptionsToMesh(cfg.Relay, cfg.Mesh)
	cfg.Relay = LegacyRelayCarryOver(cfg.Relay)
	return true
}

func RelayOptionsHasProfileData(r *RelayOptions) bool {
	return RelayOptionsHasMeshProfileData(r) || RelayOptionsHasCarryOverData(r)
}

func RelayOptionsHasMeshProfileData(r *RelayOptions) bool {
	if r == nil {
		return false
	}
	return r.PeerID != "" || r.DiscoveryURL != "" || r.BootstrapPubKey != "" ||
		len(r.StunServers) > 0 || len(r.GossipPeers) > 0 ||
		len(r.DHTFindURLs) > 0 || r.DhtRpcListenUDP != "" || r.DhtRpcSecret != "" ||
		len(r.DhtRpcSeedPeers) > 0 || r.PeerRelayUDPListen != "" || r.PeerRelayUDPAdvertise != ""
}

func RelayOptionsHasCarryOverData(r *RelayOptions) bool {
	if r == nil {
		return false
	}
	return len(r.TurnURLs) > 0 ||
		r.EmergencyPolicyURL != "" || r.EmergencyPolicyPubKey != "" || r.DiscoverySigned != "" ||
		len(r.GeoAllowCountries) > 0 || len(r.GeoDenyCountries) > 0 ||
		r.StakeRegistryURL != "" || r.StakeRegistryPubKey != "" || r.StakeReputationFile != "" ||
		r.StakeBonusHTTPURL != "" || r.StakeMerkleFile != "" || r.StakeMerkleRootURL != ""
}

func RelayOptionsWithMeshOverlay(legacy *RelayOptions, mesh *MeshConfig) *RelayOptions {
	if mesh == nil || !mesh.Enabled {
		return legacy
	}
	var out RelayOptions
	if legacy != nil {
		out = *legacy
	}
	mapped := MeshToRelayOptions(mesh)
	if mapped == nil {
		return &out
	}
	carry := out
	out.PeerID = mapped.PeerID
	out.PrivateKey = mapped.PrivateKey
	out.MaxConcurrent = mapped.MaxConcurrent
	out.BudgetKbps = mapped.BudgetKbps
	out.PeerRelayBudgetKbps = mapped.PeerRelayBudgetKbps
	out.MaxPeerHops = mapped.MaxPeerHops
	out.HealthMaxAgeSec = mapped.HealthMaxAgeSec
	out.AllowedClasses = mapped.AllowedClasses
	out.DiscoveryURL = mapped.DiscoveryURL
	out.BootstrapPubKey = mapped.BootstrapPubKey
	out.PathAggressive = mapped.PathAggressive
	out.PathCooldownMs = mapped.PathCooldownMs
	out.StunServers = mapped.StunServers
	out.GossipEnabled = mapped.GossipEnabled
	out.GossipPeers = mapped.GossipPeers
	out.GossipIntervalSec = mapped.GossipIntervalSec
	out.GossipMaxAgeSec = mapped.GossipMaxAgeSec
	out.PeerPathFromDiscovery = mapped.PeerPathFromDiscovery
	out.PeerRelayUseQUIC = mapped.PeerRelayUseQUIC
	out.PeerRelayUseUDP = mapped.PeerRelayUseUDP
	out.PeerRelayUseTCP = mapped.PeerRelayUseTCP
	out.PeerRelayUDPListen = mapped.PeerRelayUDPListen
	out.PeerRelayUDPAdvertise = mapped.PeerRelayUDPAdvertise
	out.PeerQuicServerName = mapped.PeerQuicServerName
	out.DHTFindURLs = mapped.DHTFindURLs
	out.DhtRpcListenUDP = mapped.DhtRpcListenUDP
	out.DhtRpcSecret = mapped.DhtRpcSecret
	out.DhtRpcSeedPeers = mapped.DhtRpcSeedPeers
	out.DhtRpcIntervalSec = mapped.DhtRpcIntervalSec
	out.DhtRpcFindK = mapped.DhtRpcFindK
	out.DhtIterativeRounds = mapped.DhtIterativeRounds
	out.DhtIterativeAlpha = mapped.DhtIterativeAlpha
	out.DhtPublishSrflx = mapped.DhtPublishSrflx
	out.SymmetricNatHolePunch = mapped.SymmetricNatHolePunch
	out.TurnURLs = carry.TurnURLs
	out.DiscoverySigned = carry.DiscoverySigned
	out.EmergencyPolicyURL = carry.EmergencyPolicyURL
	out.EmergencyPolicyPubKey = carry.EmergencyPolicyPubKey
	out.GeoAllowCountries = carry.GeoAllowCountries
	out.GeoDenyCountries = carry.GeoDenyCountries
	out.StakeRegistryURL = carry.StakeRegistryURL
	out.StakeRegistryPubKey = carry.StakeRegistryPubKey
	out.StakeReputationFile = carry.StakeReputationFile
	out.StakeBonusHTTPURL = carry.StakeBonusHTTPURL
	out.StakeMerkleFile = carry.StakeMerkleFile
	out.StakeMerkleRootURL = carry.StakeMerkleRootURL
	return &out
}

func LegacyRelayCarryOver(r *RelayOptions) *RelayOptions {
	if r == nil {
		return nil
	}
	out := &RelayOptions{
		TurnURLs:              append([]string(nil), r.TurnURLs...),
		DiscoverySigned:       r.DiscoverySigned,
		EmergencyPolicyURL:    r.EmergencyPolicyURL,
		EmergencyPolicyPubKey: r.EmergencyPolicyPubKey,
		GeoAllowCountries:     append([]string(nil), r.GeoAllowCountries...),
		GeoDenyCountries:      append([]string(nil), r.GeoDenyCountries...),
		StakeRegistryURL:      r.StakeRegistryURL,
		StakeRegistryPubKey:   r.StakeRegistryPubKey,
		StakeReputationFile:   r.StakeReputationFile,
		StakeBonusHTTPURL:     r.StakeBonusHTTPURL,
		StakeMerkleFile:       r.StakeMerkleFile,
		StakeMerkleRootURL:    r.StakeMerkleRootURL,
	}
	if !RelayOptionsHasCarryOverData(out) {
		return nil
	}
	return out
}

func RelayOptionsToMesh(r *RelayOptions, base *MeshConfig) *MeshConfig {
	if base == nil {
		base = &MeshConfig{Enabled: true}
	}
	out := *base
	out.Enabled = true
	if r == nil {
		return &out
	}
	out.Volunteer.PeerID = r.PeerID
	out.Volunteer.PrivateKey = r.PrivateKey
	out.Volunteer.UDPListen = r.PeerRelayUDPListen
	out.Volunteer.UDPAdvertise = r.PeerRelayUDPAdvertise
	out.Volunteer.MaxConcurrent = r.MaxConcurrent
	out.Volunteer.BudgetKbps = r.PeerRelayBudgetKbps
	if out.Volunteer.BudgetKbps <= 0 {
		out.Volunteer.BudgetKbps = r.BudgetKbps
	}
	out.Volunteer.Enabled = r.PeerRelayUDPListen != "" || r.PeerRelayUDPAdvertise != ""
	out.P2P.Enabled = r.PeerPathFromDiscovery
	out.P2P.UseUDP = r.PeerRelayUseUDP
	out.P2P.UseQUIC = r.PeerRelayUseQUIC
	out.P2P.UseTCP = r.PeerRelayUseTCP == nil || *r.PeerRelayUseTCP
	out.P2P.QuicServerName = r.PeerQuicServerName
	out.ServerRelay.Enabled = true
	out.ServerRelay.AllowedClasses = append([]string(nil), r.AllowedClasses...)
	out.ServerRelay.DiscoveryURL = r.DiscoveryURL
	out.ServerRelay.BootstrapPubKey = r.BootstrapPubKey
	out.STUN.Enabled = len(r.StunServers) > 0 || r.DhtPublishSrflx || r.SymmetricNatHolePunch
	out.STUN.Servers = append([]string(nil), r.StunServers...)
	out.STUN.PublishSrflx = r.DhtPublishSrflx
	out.STUN.SymmetricNatHolePunch = r.SymmetricNatHolePunch
	out.Discovery.GossipEnabled = r.GossipEnabled
	out.Discovery.GossipPeers = append([]string(nil), r.GossipPeers...)
	out.Discovery.GossipIntervalSec = r.GossipIntervalSec
	out.Discovery.GossipMaxAgeSec = r.GossipMaxAgeSec
	out.Discovery.DHTFindURLs = append([]string(nil), r.DHTFindURLs...)
	out.Discovery.DhtRpcListenUDP = r.DhtRpcListenUDP
	out.Discovery.DhtRpcSecret = r.DhtRpcSecret
	out.Discovery.DhtRpcSeedPeers = append([]string(nil), r.DhtRpcSeedPeers...)
	out.Discovery.DhtRpcIntervalSec = r.DhtRpcIntervalSec
	out.Discovery.DhtRpcFindK = r.DhtRpcFindK
	out.Discovery.DhtIterativeRounds = r.DhtIterativeRounds
	out.Discovery.DhtIterativeAlpha = r.DhtIterativeAlpha
	out.Policy.PathAggressive = r.PathAggressive
	out.Policy.PathCooldownMs = r.PathCooldownMs
	out.Policy.MaxPeerHops = r.MaxPeerHops
	out.Policy.BudgetKbps = r.BudgetKbps
	out.Policy.HealthMaxAgeSec = r.HealthMaxAgeSec
	return &out
}
