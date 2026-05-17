package vpn

import (
	"strings"

	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/tunnel"
)

func clusterHTTPPollTarget(base []string, prot *config.ProtectionOptions) string {
	if a := strings.TrimSpace(tunnel.ActiveVolterServer()); a != "" {
		return a
	}
	for _, raw := range dialServerAddrs(base, prot) {
		if a := strings.TrimSpace(raw); a != "" {
			return a
		}
	}
	return ""
}
