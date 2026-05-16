package vpn

import (
	"strings"

	"dev.c0redev.volter/internal/clusteraddr"
	"dev.c0redev.volter/internal/tunnel"
)

func clusterPollAcceptSource(serverAddr string) bool {
	want := strings.TrimSpace(tunnel.ActiveVolterServer())
	if want == "" {
		return true
	}
	return clusteraddr.CanonicalHostPort(serverAddr) == clusteraddr.CanonicalHostPort(want)
}

func reorderServerAddrsActiveFirst(addrs []string, active string) []string {
	active = strings.TrimSpace(active)
	if active == "" || len(addrs) <= 1 {
		return addrs
	}
	want := clusteraddr.CanonicalHostPort(active)
	if want == "" {
		return addrs
	}
	var head, tail []string
	for _, a := range addrs {
		a = strings.TrimSpace(a)
		if a == "" {
			continue
		}
		if clusteraddr.CanonicalHostPort(a) == want {
			head = append(head, a)
		} else {
			tail = append(tail, a)
		}
	}
	if len(head) == 0 {
		return addrs
	}
	return append(head, tail...)
}
