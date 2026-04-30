package vpn

import (
	"strings"

	"dev.c0redev.volter/internal/config"
)

const (
	defaultClusterMapPath        = "/volter/cluster-map.json"
	defaultClusterSessionsPath   = "/volter/cluster-sessions.json"
)

func clusterPollPaths(prot *config.ProtectionOptions) (mapPath, sessionsPath string) {
	mapPath = defaultClusterMapPath
	sessionsPath = defaultClusterSessionsPath
	if prot == nil {
		return mapPath, sessionsPath
	}
	if p := strings.TrimSpace(prot.ClusterMapPath); p != "" {
		mapPath = normalizeClusterHTTPPath(p)
	}
	if p := strings.TrimSpace(prot.ClusterSessionsPath); p != "" {
		sessionsPath = normalizeClusterHTTPPath(p)
	}
	return mapPath, sessionsPath
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
