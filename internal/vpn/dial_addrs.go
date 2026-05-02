package vpn

import (
	"strings"
	"sync/atomic"

	"dev.c0redev.volter/internal/clusteraddr"
	"dev.c0redev.volter/internal/config"
)

var clusterDialPref atomic.Value

func SetClusterDialPreference(hostPort string) {
	hostPort = clusteraddr.CanonicalHostPort(hostPort)
	if hostPort == "" {
		clusterDialPref.Store("")
		return
	}
	clusterDialPref.Store(hostPort)
}

func dialServerAddrs(base []string, prot *config.ProtectionOptions) []string {
	addrs := orderedServerAddrs(base, prot)
	if prot != nil && strings.TrimSpace(prot.ClusterPreferredServer) != "" {
		return addrs
	}
	v := clusterDialPref.Load()
	pref, _ := v.(string)
	if pref == "" {
		return addrs
	}
	return reorderPrimaryFirst(addrs, pref)
}

func reorderPrimaryFirst(addrs []string, primary string) []string {
	if len(addrs) <= 1 || primary == "" {
		return addrs
	}
	idx := -1
	for i, a := range addrs {
		if clusteraddr.CanonicalHostPort(a) == primary {
			idx = i
			break
		}
	}
	if idx <= 0 {
		return addrs
	}
	out := make([]string, 0, len(addrs))
	out = append(out, addrs[idx])
	out = append(out, addrs[:idx]...)
	out = append(out, addrs[idx+1:]...)
	return out
}
