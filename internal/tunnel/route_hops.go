package tunnel

import (
	"fmt"
	"net"
	"strings"

	"dev.c0redev.volter/internal/config"
)

func EncodeRouteHops(plan RoutePlan) []string {
	if len(plan.Hops) == 0 {
		return nil
	}
	out := make([]string, 0, len(plan.Hops))
	for _, h := range plan.Hops {
		kind := strings.TrimSpace(h.Kind)
		addr := strings.TrimSpace(h.Addr)
		if kind == "" || addr == "" {
			continue
		}
		out = append(out, kind+":"+addr)
	}
	return out
}

func ParseRouteHop(raw string) (kind, addr string, ok bool) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", "", false
	}
	i := strings.IndexByte(raw, ':')
	if i <= 0 || i >= len(raw)-1 {
		return "", "", false
	}
	return raw[:i], raw[i+1:], true
}

func NextRelayHop(hops []string, hopIndex int) (kind, addr string, ok bool) {
	if hopIndex < 1 || len(hops) == 0 {
		return "", "", false
	}
	next := hopIndex
	if next >= len(hops) {
		return "", "", false
	}
	return ParseRouteHop(hops[next])
}

func AttachRouteHops(prot *config.ProtectionOptions, plan RoutePlan) *config.ProtectionOptions {
	if prot == nil {
		return nil
	}
	cp := *prot
	cp.RelayRouteHops = EncodeRouteHops(plan)
	if cp.RelayMaxHop <= 0 && len(cp.RelayRouteHops) > 0 {
		cp.RelayMaxHop = len(cp.RelayRouteHops)
	}
	if !cp.RoutePlannerV2 && strings.TrimSpace(cp.RouteID) == "" && len(cp.RelayRouteHops) > 1 {
		cp.RoutePlannerV2 = true
	}
	return &cp
}

func ProtForRelayForward(base *config.ProtectionOptions, token string) *config.ProtectionOptions {
	if base == nil {
		return nil
	}
	cp := *base
	if cp.RelayHop <= 0 {
		cp.RelayHop = 1
	}
	cp.RelayHop++
	cp.HopIndex++
	if cp.RelayMaxHop <= 0 {
		cp.RelayMaxHop = config.DefaultMeshMaxPeerHops
	}
	return relayOptsForHandshake(&cp, token)
}

func firstServerAddr(addrs []string) string {
	for _, a := range addrs {
		if s := strings.TrimSpace(a); s != "" {
			return s
		}
	}
	if s := strings.TrimSpace(ActiveVolterServer()); s != "" {
		return s
	}
	return ""
}

func hopServerAddrs(addr string, fallbacks []string) []string {
	addr = strings.TrimSpace(addr)
	if addr != "" {
		if _, _, err := net.SplitHostPort(addr); err == nil {
			return []string{addr}
		}
	}
	if s := firstServerAddr(fallbacks); s != "" {
		return []string{s}
	}
	return nil
}

func BuildRoutePlan(target string, serverAddr string, decision PathDecision) RoutePlan {
	out := RoutePlan{Target: strings.TrimSpace(target)}
	serverAddr = strings.TrimSpace(serverAddr)
	maxHops := decision.MaxPeerHops
	if maxHops <= 0 {
		maxHops = 1
	}
	add := func(kind, addr string) {
		addr = strings.TrimSpace(addr)
		if addr == "" {
			return
		}
		out.Hops = append(out.Hops, RouteHop{Kind: kind, Addr: addr})
	}
	for i, addr := range decision.PeerTCPCandidates {
		if len(out.Hops) >= maxHops {
			break
		}
		if addr != "" {
			add("peer_tcp", addr)
			continue
		}
		if i < len(decision.PeerQUICCandidates) && decision.PeerQUICCandidates[i] != "" {
			add("peer_quic", decision.PeerQUICCandidates[i])
			continue
		}
		if i < len(decision.PeerUDPCandidates) && decision.PeerUDPCandidates[i] != "" {
			add("peer_udp", decision.PeerUDPCandidates[i])
		}
	}
	if len(out.Hops) == 0 {
		if strings.TrimSpace(decision.PeerQUIC) != "" {
			add("peer_quic", decision.PeerQUIC)
		}
		if len(out.Hops) < maxHops && strings.TrimSpace(decision.PeerUDP) != "" {
			add("peer_udp", decision.PeerUDP)
		}
		if len(out.Hops) < maxHops && strings.TrimSpace(decision.PeerAddr) != "" {
			add("peer_tcp", decision.PeerAddr)
		}
	}
	if len(out.Hops) == 0 && serverAddr != "" {
		if decision.RelayClass == PathClassServer {
			add("server_relay", serverAddr)
		} else {
			add("direct_server", serverAddr)
		}
	}
	if len(out.Hops) > maxHops {
		out.Hops = out.Hops[:maxHops]
	}
	return out
}

func relayHopLimitExceeded(opts *config.ProtectionOptions) bool {
	if opts == nil {
		return true
	}
	max := opts.RelayMaxHop
	if max <= 0 {
		max = config.DefaultMeshMaxPeerHops
	}
	return opts.RelayHop > max
}

func relayHopLimitReason(opts *config.ProtectionOptions) string {
	return fmt.Sprintf("relay hop limit exceeded (%d>%d)", opts.RelayHop, opts.RelayMaxHop)
}
