package vpn

import (
	"strings"

	"dev.c0redev.volter/internal/config"
)

func meshAllowsVolunteerRelay(mesh *config.MeshConfig) bool {
	return mesh != nil && mesh.Enabled && mesh.Volunteer.Enabled
}

func meshAllowsPresencePublish(mesh *config.MeshConfig) bool {
	return meshAllowsVolunteerRelay(mesh)
}

func meshAllowsSTUN(mesh *config.MeshConfig) bool {
	return mesh == nil || !mesh.Enabled || mesh.STUN.Enabled
}

func meshAllowsServerRelay(mesh *config.MeshConfig) bool {
	return mesh == nil || !mesh.Enabled || mesh.ServerRelay.Enabled
}

func protectionWithMeshPolicy(base *config.ProtectionOptions, mesh *config.MeshConfig) *config.ProtectionOptions {
	mode := ""
	if mesh != nil && mesh.Enabled {
		mode = strings.ToLower(strings.TrimSpace(mesh.Policy.RouteMode))
	}
	if mode == "" || mode == "auto" {
		return base
	}
	switch mode {
	case "direct":
	case "peer_relay":
		if mesh == nil || !mesh.P2PEnabled() {
			return base
		}
	case "server_relay":
		if !meshAllowsServerRelay(mesh) {
			return base
		}
	default:
		return base
	}
	var out config.ProtectionOptions
	if base != nil {
		out = *base
	}
	out.RouteMode = mode
	return &out
}
