package vpn

import (
	"strings"

	"dev.c0redev.volter/internal/config"
)

const (
	defaultClusterMapPath      = "/volter/cluster-map.json"
	defaultClusterSessionsPath = "/volter/cluster-sessions.json"
	defaultClusterClientsPath  = "/volter/cluster-clients.json"
)

func clusterPollPaths(prot *config.ProtectionOptions) (mapPath, sessionsPath, clientsPath string) {
	mapPath = defaultClusterMapPath
	sessionsPath = defaultClusterSessionsPath
	clientsPath = defaultClusterClientsPath
	if prot == nil {
		return mapPath, sessionsPath, clientsPath
	}
	if p := strings.TrimSpace(prot.ClusterMapPath); p != "" {
		mapPath = normalizeClusterHTTPPath(p)
	}
	if p := strings.TrimSpace(prot.ClusterSessionsPath); p != "" {
		sessionsPath = normalizeClusterHTTPPath(p)
	}
	if p := strings.TrimSpace(prot.ClusterClientsPath); p != "" {
		clientsPath = normalizeClusterHTTPPath(p)
	}
	return mapPath, sessionsPath, clientsPath
}

func ClusterInviteHTTPPath(prot *config.ProtectionOptions) string {
	if prot == nil {
		return ""
	}
	return normalizeClusterHTTPPath(prot.ClusterInvitePath)
}

func ClusterPeerHandshakeHTTPPath(prot *config.ProtectionOptions) string {
	if prot == nil {
		return ""
	}
	return normalizeClusterHTTPPath(prot.ClusterPeerHandshakePath)
}

func normalizeClusterHTTPPath(p string) string {
	p = strings.TrimSpace(p)
	if p == "" {
		return ""
	}
	if !strings.HasPrefix(p, "/") {
		return "/" + p
	}
	return p
}
