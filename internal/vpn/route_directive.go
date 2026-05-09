package vpn

import (
	"strings"

	"dev.c0redev.volter/internal/config"
)

func routeDirectiveProtection(base *config.ProtectionOptions, d RouteDirective) (*config.ProtectionOptions, bool) {
	return routeDirectiveProtectionForMesh(base, d, nil)
}

func routeDirectiveProtectionForMesh(base *config.ProtectionOptions, d RouteDirective, mesh *config.MeshConfig) (*config.ProtectionOptions, bool) {
	if strings.TrimSpace(d.Endpoint) == "" {
		return base, false
	}
	var out config.ProtectionOptions
	if base != nil {
		out = *base
	}
	mode := strings.ToLower(strings.TrimSpace(d.Mode))
	if mode == "" {
		mode = "server_relay"
	}
	switch mode {
	case "server_relay":
		if !meshAllowsServerRelay(mesh) {
			return base, false
		}
		out.RouteMode = "server_relay"
		out.ClusterPreferredServer = strings.TrimSpace(d.Endpoint)
		out.RouteID = ""
	case "direct":
		out.RouteMode = "direct"
		out.ClusterPreferredServer = ""
		out.RouteID = ""
	case "peer_relay":
		if mesh != nil && mesh.Enabled && !mesh.P2PEnabled() {
			return base, false
		}
		out.RouteMode = "peer_relay"
		out.ClusterPreferredServer = ""
		out.RouteID = strings.TrimSpace(d.RouteID)
		out.PeerID = strings.TrimSpace(d.PeerID)
	default:
		return base, false
	}
	return &out, true
}
