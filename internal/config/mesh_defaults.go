package config

import (
	"crypto/rand"
	"encoding/hex"
	"strings"

	"dev.c0redev.volter/internal/ice"
)

var (
	DefaultMeshSTUNServers      = append([]string(nil), ice.DefaultSTUNServers...)
	DefaultMeshSTUNServersExtra = []string{
		"stun.cloudflare.com:3478",
		"stun1.l.google.com:19302",
	}
	DefaultMeshAllowedClasses = []string{"server", "peer"}
)

const (
	DefaultMeshMaxPeerHops       = 2
	DefaultMeshHealthMaxAgeSec   = 300
	DefaultMeshGossipIntervalSec = 180
	DefaultMeshGossipMaxAgeSec   = 900
	DefaultMeshDhtRpcIntervalSec = 120
	DefaultMeshDhtRpcFindK       = 20
	DefaultMeshDhtIterativeAlpha = 3
	DefaultMeshMaxConcurrent     = 32
	DefaultMeshVolunteerBudget   = 768
	DefaultMeshPolicyBudget      = 2048
	DefaultMeshPathCooldownMs    = 0
	DefaultClusterMapPath        = "/volter/cluster-map.json"
	DefaultClusterSessionsPath   = "/volter/cluster-sessions.json"
	DefaultClusterClientsPath    = "/volter/cluster-clients.json"
	DefaultClusterInvitePath     = "/volter/cluster-invite"
	DefaultClusterPeerHandshake  = "/volter/cluster-peer-handshake"
)

func DefaultSTUNServerList() []string {
	seen := make(map[string]struct{})
	var out []string
	for _, s := range append(DefaultMeshSTUNServers, DefaultMeshSTUNServersExtra...) {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		if _, ok := seen[s]; ok {
			continue
		}
		seen[s] = struct{}{}
		out = append(out, s)
	}
	return out
}

func ApplyMeshDefaults(m *MeshConfig) {
	if m == nil {
		return
	}
	if !m.Enabled {
		return
	}
	if !m.STUN.Enabled {
		m.STUN.Enabled = true
	}
	if len(m.STUN.Servers) == 0 {
		m.STUN.Servers = DefaultSTUNServerList()
	}
	if m.Volunteer.Enabled {
		m.STUN.PublishSrflx = true
		m.STUN.SymmetricNatHolePunch = true
	}
	if !m.ServerRelay.Enabled {
		m.ServerRelay.Enabled = true
	}
	if len(m.ServerRelay.AllowedClasses) == 0 {
		m.ServerRelay.AllowedClasses = append([]string(nil), DefaultMeshAllowedClasses...)
	}
	if m.P2P.Enabled {
		if !m.P2P.UseUDP && !m.P2P.UseQUIC && !m.P2P.UseTCP {
			m.P2P.UseUDP = true
			m.P2P.UseQUIC = true
			m.P2P.UseTCP = true
		}
	}
	if m.Policy.MaxPeerHops <= 0 {
		m.Policy.MaxPeerHops = DefaultMeshMaxPeerHops
	}
	if m.Policy.HealthMaxAgeSec <= 0 {
		m.Policy.HealthMaxAgeSec = DefaultMeshHealthMaxAgeSec
	}
	if strings.TrimSpace(m.Policy.RouteMode) == "" {
		m.Policy.RouteMode = "auto"
	}
	if m.Policy.BudgetKbps <= 0 {
		m.Policy.BudgetKbps = DefaultMeshPolicyBudget
	}
	if m.Discovery.GossipIntervalSec <= 0 {
		m.Discovery.GossipIntervalSec = DefaultMeshGossipIntervalSec
	}
	if m.Discovery.GossipMaxAgeSec <= 0 {
		m.Discovery.GossipMaxAgeSec = DefaultMeshGossipMaxAgeSec
	}
	if m.Discovery.DhtRpcIntervalSec <= 0 {
		m.Discovery.DhtRpcIntervalSec = DefaultMeshDhtRpcIntervalSec
	}
	if m.Discovery.DhtRpcFindK <= 0 {
		m.Discovery.DhtRpcFindK = DefaultMeshDhtRpcFindK
	}
	if m.Discovery.DhtIterativeAlpha <= 0 {
		m.Discovery.DhtIterativeAlpha = DefaultMeshDhtIterativeAlpha
	}
	if m.Volunteer.Enabled {
		if strings.TrimSpace(m.Volunteer.PeerID) == "" {
			m.Volunteer.PeerID = randomPeerID()
		}
		if strings.TrimSpace(m.Volunteer.UDPListen) == "" {
			m.Volunteer.UDPListen = "0.0.0.0:0"
		}
		if m.Volunteer.MaxConcurrent <= 0 {
			m.Volunteer.MaxConcurrent = DefaultMeshMaxConcurrent
		}
		if m.Volunteer.BudgetKbps <= 0 {
			m.Volunteer.BudgetKbps = DefaultMeshVolunteerBudget
		}
	}
	if m.P2P.Enabled || m.Volunteer.Enabled {
		m.Discovery.GossipEnabled = true
	}
}

func ApplyRelayDefaults(r *RelayOptions) {
	if r == nil {
		return
	}
	if len(r.StunServers) == 0 {
		r.StunServers = DefaultSTUNServerList()
	}
	if len(r.AllowedClasses) == 0 {
		r.AllowedClasses = append([]string(nil), DefaultMeshAllowedClasses...)
	}
	if r.MaxPeerHops <= 0 {
		r.MaxPeerHops = DefaultMeshMaxPeerHops
	}
	if r.HealthMaxAgeSec <= 0 {
		r.HealthMaxAgeSec = DefaultMeshHealthMaxAgeSec
	}
	if r.GossipIntervalSec <= 0 {
		r.GossipIntervalSec = DefaultMeshGossipIntervalSec
	}
	if r.GossipMaxAgeSec <= 0 {
		r.GossipMaxAgeSec = DefaultMeshGossipMaxAgeSec
	}
	if r.DhtRpcIntervalSec <= 0 {
		r.DhtRpcIntervalSec = DefaultMeshDhtRpcIntervalSec
	}
	if r.DhtRpcFindK <= 0 {
		r.DhtRpcFindK = DefaultMeshDhtRpcFindK
	}
	if r.DhtIterativeAlpha <= 0 {
		r.DhtIterativeAlpha = DefaultMeshDhtIterativeAlpha
	}
	if r.MaxConcurrent <= 0 {
		r.MaxConcurrent = DefaultMeshMaxConcurrent
	}
	if r.PeerRelayBudgetKbps <= 0 {
		r.PeerRelayBudgetKbps = DefaultMeshVolunteerBudget
	}
	if r.BudgetKbps <= 0 {
		r.BudgetKbps = DefaultMeshPolicyBudget
	}
	if strings.TrimSpace(r.PeerID) == "" {
		r.PeerID = randomPeerID()
	}
	if strings.TrimSpace(r.PeerRelayUDPListen) == "" && r.PeerRelayUseUDP {
		r.PeerRelayUDPListen = "0.0.0.0:0"
	}
	if !r.PeerPathFromDiscovery && !r.GossipEnabled {
		r.PeerPathFromDiscovery = true
	}
	if !r.DhtPublishSrflx {
		r.DhtPublishSrflx = true
	}
	if !r.SymmetricNatHolePunch {
		r.SymmetricNatHolePunch = true
	}
	if r.PeerRelayUseTCP == nil {
		v := true
		r.PeerRelayUseTCP = &v
	}
	if !r.PeerRelayUseUDP && !r.PeerRelayUseQUIC && (r.PeerRelayUseTCP == nil || *r.PeerRelayUseTCP) {
		r.PeerRelayUseUDP = true
	}
}

func ApplyClusterProtectionDefaults(p *ProtectionOptions) {
	if p == nil {
		return
	}
	if strings.TrimSpace(p.ClusterMapPath) == "" {
		p.ClusterMapPath = DefaultClusterMapPath
	}
	if strings.TrimSpace(p.ClusterSessionsPath) == "" {
		p.ClusterSessionsPath = DefaultClusterSessionsPath
	}
	if strings.TrimSpace(p.ClusterClientsPath) == "" {
		p.ClusterClientsPath = DefaultClusterClientsPath
	}
	if strings.TrimSpace(p.ClusterInvitePath) == "" {
		p.ClusterInvitePath = DefaultClusterInvitePath
	}
	if strings.TrimSpace(p.ClusterPeerHandshakePath) == "" {
		p.ClusterPeerHandshakePath = DefaultClusterPeerHandshake
	}
	mode := strings.ToLower(strings.TrimSpace(p.RouteMode))
	if mode == "server_relay" || mode == "peer_relay" {
		if !p.RoutePlannerV2 {
			p.RoutePlannerV2 = true
		}
	}
}

func randomPeerID() string {
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return "peer-local"
	}
	return "peer-" + hex.EncodeToString(b)
}
