package tunnel

import (
	"bufio"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net"
	"strings"
	"time"

	"dev.c0redev.volter/internal/clientlog"
	"dev.c0redev.volter/internal/clusteraddr"
	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/protocol"
	"dev.c0redev.volter/internal/telemetry"
)

func wrapFlushJitter(prot *config.ProtectionOptions, flush func()) func() {
	if flush == nil {
		return nil
	}
	max := 0
	if prot != nil {
		max = prot.FlushJitterMaxMs
	}
	if max <= 0 {
		return flush
	}
	return func() {
		time.Sleep(time.Duration(randInt(0, max)) * time.Millisecond)
		flush()
	}
}

func randInt(min, max int) int {
	if max <= min {
		return min
	}
	n, err := rand.Int(rand.Reader, big.NewInt(int64(max-min+1)))
	if err != nil {
		return min
	}
	return min + int(n.Int64())
}

func resolvePreambleKind(prot *config.ProtectionOptions, slot int64, token, junkStyle string) string {
	var preambleProfile string
	var preambleRotate, enhanced bool
	var probeObfs byte
	if prot != nil {
		preambleProfile = prot.PreambleProfile
		preambleRotate = prot.PreambleRotate
		probeObfs = prot.ProbeObfsProfileID
		enhanced = strings.EqualFold(prot.Obfuscation, "enhanced")
	}
	return protocol.ResolvePreambleKind(junkStyle, preambleProfile, preambleRotate, enhanced, slot, token, probeObfs)
}

func streamObf(prot *config.ProtectionOptions, slot int64, udpMaxPad bool) (maxPad, prefixLen int, junkCount, junkMin, junkMax int, junkStyle, flushPolicy string) {
	maxPad = 32
	if udpMaxPad {
		if prot != nil && prot.PadS4 > 0 && prot.PadS4 <= 64 {
			maxPad = prot.PadS4
		}
		maxPadHi := maxPad + 16
		if maxPadHi > 64 {
			maxPadHi = 64
		}
		maxPad = randInt(maxPad, maxPadHi)
	}
	prefixLen = 0
	junkCount, junkMin, junkMax = 0, 64, 1024
	if prot != nil {
		prefixLen = prot.PadS1 + prot.PadS2 + prot.PadS3
		if prefixLen > 64 {
			prefixLen = 64
		}
		prefixLen += int(slot % 8)
		if prefixLen > 64 {
			prefixLen = 64
		}
		if prot.JunkCount > 0 {
			junkCount = prot.JunkCount
			if prot.JunkMin > 0 {
				junkMin = prot.JunkMin
			}
			if prot.JunkMax > junkMin {
				junkMax = prot.JunkMax
			}
		}
		if strings.EqualFold(prot.Obfuscation, "enhanced") && junkCount > 0 {
			junkCount += 3
			if junkCount > 12 {
				junkCount = 12
			}
		}
		junkStyle, flushPolicy = prot.JunkStyle, prot.FlushPolicy
	}
	if junkCount == 0 {
		junkCount, junkMin, junkMax = 2, 64, 512
	}
	cMin, cMax := junkCount-1, junkCount+2
	if cMin < 1 {
		cMin = 1
	}
	if cMax > 16 {
		cMax = 16
	}
	junkCount = randInt(cMin, cMax)
	jMinLo, jMinHi := junkMin, junkMin+128
	if jMinHi > 1024 {
		jMinHi = 1024
	}
	junkMin = randInt(jMinLo, jMinHi)
	jMaxLo, jMaxHi := junkMax, junkMax+384
	if jMaxLo < junkMin {
		jMaxLo = junkMin
	}
	if jMaxHi > 2048 {
		jMaxHi = 2048
	}
	if jMaxHi < jMaxLo {
		jMaxHi = jMaxLo
	}
	junkMax = randInt(jMaxLo, jMaxHi)
	return
}

func WriteUDPChannelPreambleSlot(w *bufio.Writer, channelID byte, token string, prot *config.ProtectionOptions, slot int64) (maxPad int, err error) {
	maxPad, prefixLen, jc, jmin, jmax, jstyle, flush := streamObf(prot, slot, true)
	kind := resolvePreambleKind(prot, slot, token, jstyle)
	if err = protocol.WritePreamble(w, kind, jc, jmin, jmax, flush, wrapFlushJitter(prot, func() { _ = w.Flush() })); err != nil {
		return 0, err
	}
	if !strings.EqualFold(flush, "perChunk") {
		_ = w.Flush()
	}
	var optsJSON []byte
	if prot != nil {
		enrichSessionOptions(prot, token)
		optsJSON, _ = json.Marshal(prot)
	}
	if err = protocol.WriteHandshakeWithPrefixAndOptsSlot(w, protocol.RoleUDP(), channelID, token, prefixLen, optsJSON, slot); err != nil {
		return 0, err
	}
	return maxPad, nil
}

func WriteUDPChannelPreamble(w *bufio.Writer, channelID byte, token string, prot *config.ProtectionOptions) (maxPad int, err error) {
	return WriteUDPChannelPreambleSlot(w, channelID, token, prot, SlotForProtection(prot))
}

func tcpRelayPreamble(w *bufio.Writer, token string, prot *config.ProtectionOptions, slot int64) error {
	_, prefixLen, jc, jmin, jmax, jstyle, flush := streamObf(prot, slot, false)
	prefixLen += randInt(8, 24)
	if prefixLen > 64 {
		prefixLen = 64
	}
	jc += randInt(2, 5)
	if jc > 16 {
		jc = 16
	}
	if jmin < 128 {
		jmin = 128
	}
	jmin += randInt(0, 128)
	if jmin > 1024 {
		jmin = 1024
	}
	if jmax < jmin+256 {
		jmax = jmin + 256
	}
	jmax += randInt(64, 512)
	if jmax > 2048 {
		jmax = 2048
	}
	kind := resolvePreambleKind(prot, slot, token, jstyle)
	if err := protocol.WritePreamble(w, kind, jc, jmin, jmax, flush, wrapFlushJitter(prot, func() { _ = w.Flush() })); err != nil {
		return err
	}
	if !strings.EqualFold(flush, "perChunk") {
		_ = w.Flush()
	}
	var optsJSON []byte
	role := protocol.RoleTCP()
	if prot != nil {
		enrichSessionOptions(prot, token)
		eff := relayOptsForHandshake(prot, token)
		optsJSON, _ = json.Marshal(eff)
		clusterExit := strings.TrimSpace(prot.ClusterPreferredServer) != ""
		hasPeerRelay := strings.TrimSpace(eff.PeerID) != ""
		needRelay := (eff.RelayHop > 0 || eff.RelayMaxHop > 0) && !clusterExit && hasPeerRelay
		if needRelay {
			role = protocol.RoleRelayTCP()
		}
		clientlog.Trace("handshake role=%d relayHop=%d relayMaxHop=%d peerId=%q clusterExit=%v hasPeerRelay=%v needRelay=%v",
			role, eff.RelayHop, eff.RelayMaxHop, eff.PeerID, clusterExit, hasPeerRelay, needRelay)
		clientlog.Trace("handshake optsJSON: %s", string(optsJSON))
	}
	return protocol.WriteHandshakeWithPrefixAndOptsSlot(w, role, 0, token, prefixLen, optsJSON, slot)
}

func enrichSessionOptions(prot *config.ProtectionOptions, token string) {
	if prot == nil {
		return
	}
	if strings.TrimSpace(prot.SessionID) == "" {
		rb := make([]byte, 12)
		if _, err := rand.Read(rb); err == nil {
			prot.SessionID = "s-" + hex.EncodeToString(rb)
		}
	}
	if strings.TrimSpace(prot.ResumeToken) == "" {
		sum := sha256.Sum256([]byte(strings.TrimSpace(token) + "|" + strings.TrimSpace(prot.SessionID)))
		prot.ResumeToken = base64.RawStdEncoding.EncodeToString(sum[:16])
	}
}

func relayOptsForHandshake(src *config.ProtectionOptions, token string) *config.ProtectionOptions {
	if src == nil {
		return nil
	}
	cp := *src
	activeRelay := cp.RelayHop > 0 || cp.RelayMaxHop > 0 || strings.TrimSpace(cp.PeerID) != ""
	if !activeRelay {
		return &cp
	}
	if cp.RelayHop <= 0 {
		cp.RelayHop = 1
	}
	if cp.RelayMaxHop <= 0 {
		cp.RelayMaxHop = 2
	}
	if cp.RelayNonce == "" {
		rb := make([]byte, 12)
		if _, err := rand.Read(rb); err == nil {
			cp.RelayNonce = base64.RawURLEncoding.EncodeToString(rb)
		}
	}
	if cp.PeerID == "" {
		sum := sha256.Sum256([]byte(token))
		cp.PeerID = "p-" + base64.RawURLEncoding.EncodeToString(sum[:6])
	}
	if cp.RelaySig == "" {
		mac := hmac.New(sha256.New, []byte(token))
		_, _ = mac.Write([]byte(cp.PeerID))
		_, _ = mac.Write([]byte("|"))
		_, _ = mac.Write([]byte(cp.RelayNonce))
		cp.RelaySig = base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	}
	return &cp
}

func routeModeOf(prot *config.ProtectionOptions) string {
	if m := liveRouteModeValue(); m != "" {
		switch m {
		case "direct", "peer_relay", "server_relay", "auto":
			return m
		}
	}
	if prot == nil {
		return "auto"
	}
	mode := strings.ToLower(strings.TrimSpace(prot.RouteMode))
	switch mode {
	case "direct", "peer_relay", "server_relay":
		return mode
	default:
		return "auto"
	}
}

func protForDirectRoute(base *config.ProtectionOptions) *config.ProtectionOptions {
	if base == nil {
		return nil
	}
	cp := *base
	cp.RelayHop = 0
	cp.RelayMaxHop = 0
	cp.PeerID = ""
	cp.RelayNonce = ""
	cp.RelaySig = ""
	cp.RoutePlannerV2 = false
	cp.ClusterPreferredServer = ""
	return &cp
}

func protForServerRelayRoute(base *config.ProtectionOptions, relay *config.RelayOptions) *config.ProtectionOptions {
	if base == nil {
		base = &config.ProtectionOptions{}
	}
	cp := *base
	if strings.TrimSpace(cp.ClusterPreferredServer) != "" {
		cp.PeerID = ""
		cp.RelayNonce = ""
		cp.RelaySig = ""
		cp.RoutePlannerV2 = false
		clientlog.Trace("cluster exit prot: peerId/relayNonce/sig/rpv2 cleared, preferred=%s", strings.TrimSpace(cp.ClusterPreferredServer))
	} else {
		clientlog.Trace("server relay prot: relayHop=%d relayMaxHop=%d rpv2=%v", cp.RelayHop, cp.RelayMaxHop, cp.RoutePlannerV2)
	}
	if cp.RelayHop <= 0 {
		cp.RelayHop = 1
	}
	if cp.RelayMaxHop <= 0 {
		cp.RelayMaxHop = 2
	}
	if relay != nil {
		if relay.BudgetKbps > 0 && cp.RelayBudgetKbps <= 0 {
			cp.RelayBudgetKbps = relay.BudgetKbps
		}
	}
	return &cp
}

func DialTunFlow(addrs []string, dst net.IP, dstPort uint16, token string, prot *config.ProtectionOptions, transport, quicServer, quicServerName string, quicSkipVerify bool, quicCertPinSHA256 string, quicTLSRoots *x509.CertPool, quicShared *QUICConn, dual bool, sel *DualPathSelector, pm *PathManager, allowPeerPath bool, relay *config.RelayOptions) (net.Conn, bool, bool, error) {
	if prot != nil && strings.TrimSpace(prot.ClusterPreferredServer) != "" {
		canonical := clusteraddr.CanonicalHostPort(strings.TrimSpace(prot.ClusterPreferredServer))
		// if preferred server matches one of the entry server addresses,
		// clear it and treat as direct — no bridge-to-self needed
		if canonical != "" && clusteraddr.MatchPreferred(addrs, canonical) >= 0 {
			clientlog.Trace("DialTunFlow clusterPreferredServer %q matches entry server %v, using direct mode", canonical, addrs)
			p := *prot
			p.ClusterPreferredServer = ""
			prot = &p
		} else {
			p := *prot
			p.ClusterPreferredServer = canonical
			prot = &p
		}
	}
	flowStart := time.Now()
	preferTCP := false
	decision := PathDecision{RelayClass: PathClassDirect, PathTTL: 1}
	dialProt := prot
	mode := routeModeOf(prot)
	SetRouteTrace(dst.String(), mode, "", "")
	quicEnabled := UsesQUICTransport(transport, quicServer) && quicShared != nil
	clusterExit := prot != nil && strings.TrimSpace(prot.ClusterPreferredServer) != ""
	clientlog.Trace("DialTunFlow dst=%s:%d mode=%s clusterExit=%v allowPeerPath=%v rpv2=%v relayHop=%d",
		dst.String(), dstPort, mode, clusterExit, allowPeerPath,
		prot != nil && prot.RoutePlannerV2,
		func() int { if prot != nil { return prot.RelayHop }; return 0 }())
	if clusterExit {
		quicEnabled = false
		preferTCP = true
	}
	switch mode {
	case "direct":
		allowPeerPath = false
		decision = PathDecision{RelayClass: PathClassDirect, PathTTL: 1}
		dialProt = protForDirectRoute(prot)
		clientlog.Trace("mode=direct: allowPeerPath=false relayClass=direct")
	case "server_relay":
		allowPeerPath = false
		if clusterExit {
			decision = PathDecision{PreferTCP: true, RelayClass: PathClassServer, PathTTL: 2}
			preferTCP = true
			dialProt = protForServerRelayRoute(prot, relay)
			clientlog.Trace("mode=server_relay with exit: relayClass=server pathTTL=2 clusterPreferred=%q", strings.TrimSpace(prot.ClusterPreferredServer))
		} else {
			decision = PathDecision{RelayClass: PathClassDirect, PathTTL: 1}
			dialProt = protForDirectRoute(prot)
			clientlog.Trace("mode=server_relay no preferred: fallback to direct")
		}
	case "peer_relay":
		allowPeerPath = !clusterExit
		clientlog.Trace("mode=peer_relay: allowPeerPath=%v", allowPeerPath)
		if pm != nil {
			if clusterExit {
				decision = pm.Decide(dst, dual, quicEnabled, false, false)
			} else {
				decision = pm.Decide(dst, dual, quicEnabled, true, true)
			}
		}
	default:
		clientlog.Trace("mode=auto: allowPeerPath=%v clusterExit=%v", allowPeerPath, clusterExit)
		if pm != nil {
			ap := allowPeerPath
			if clusterExit {
				ap = false
			}
			decision = pm.Decide(dst, dual, quicEnabled, ap, false)
		}
	}
	if clusterExit && mode != "direct" {
		allowPeerPath = false
		decision = PathDecision{PreferTCP: true, RelayClass: PathClassServer, PathTTL: 2}
		dialProt = protForServerRelayRoute(prot, relay)
		preferTCP = true
		quicEnabled = false
		clientlog.Trace("clusterExit override: relayClass=server pathTTL=2 preferTCP=true preferServer=%q", strings.TrimSpace(prot.ClusterPreferredServer))
	}
	if decision.PreferTCP {
		preferTCP = true
	}
	if mode == "direct" {
		fb := protForDirectRoute(dialProt)
		if preferTCP || !UsesQUICTransport(transport, quicServer) || quicShared == nil {
			c, err := Dial(addrs, dst, dstPort, token, fb, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, true)
			if pm != nil {
				pm.Record(dst, err == nil, PathClassDirect, 1)
			}
			return c, false, true, err
		}
		c, err := Dial(addrs, dst, dstPort, token, fb, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, false)
		if pm != nil {
			pm.Record(dst, err == nil, PathClassDirect, 1)
		}
		return c, false, false, err
	}
	if prot == nil || !prot.RoutePlannerV2 {
		if len(decision.PeerQUICCandidates) > 1 {
			decision.PeerQUICCandidates = decision.PeerQUICCandidates[:1]
		}
		if len(decision.PeerUDPCandidates) > 1 {
			decision.PeerUDPCandidates = decision.PeerUDPCandidates[:1]
		}
		if len(decision.PeerTCPCandidates) > 1 {
			decision.PeerTCPCandidates = decision.PeerTCPCandidates[:1]
		}
	}
	plan := BuildRoutePlan(dst.String(), firstServerAddr(addrs), decision)
	if len(plan.Hops) > 0 {
		var parts []string
		for _, h := range plan.Hops {
			parts = append(parts, h.Kind+"="+h.Addr)
		}
		routePlan := strings.Join(parts, " -> ")
		SetRouteTrace(dst.String(), routePlan, "", "")
		clientlog.Trace("route plan: %s hops=%d", routePlan, len(plan.Hops))
	} else {
		clientlog.Trace("route plan: empty (fallback to direct dial)")
	}
	chainProt := AttachRouteHops(dialProt, plan)
	if len(plan.Hops) > 0 && chainProt != nil {
		h := plan.Hops[0]
		protR := RelayProtForPeerHop(chainProt, relay)
		if protR.HopIndex <= 0 {
			protR.HopIndex = 1
		}
		clientlog.Trace("route hop attempt: kind=%s addr=%s hopIndex=%d", h.Kind, h.Addr, protR.HopIndex)
		c, usedTCP, err := dialRouteHopConn(h.Kind, h.Addr, dst, dstPort, token, protR, addrs, relay, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared)
		if err == nil {
			SetRouteTrace(dst.String(), mode, fmt.Sprintf("%s:%s", h.Kind, h.Addr), "")
			if pm != nil {
				pm.Record(dst, true, decision.RelayClass, decision.PathTTL)
			}
			telemetry.RecordPath(telemetry.SwitchRelay, fmt.Sprintf("route %s:%s hop=%d", h.Kind, h.Addr, protR.HopIndex))
			return c, false, usedTCP, nil
		}
		clientlog.Warn("vpn: route hop %s %s: %v", h.Kind, h.Addr, err)
		SetRouteTrace(dst.String(), mode, fmt.Sprintf("%s:%s", h.Kind, h.Addr), err.Error())
	}
	if relay != nil && relay.PeerRelayUseQUIC && strings.TrimSpace(decision.PeerQUIC) != "" {
		quicCands := append([]string(nil), decision.PeerQUICCandidates...)
		if len(quicCands) == 0 {
			quicCands = []string{decision.PeerQUIC}
		}
		for i, q := range quicCands {
			protR := RelayProtForPeerHop(dialProt, relay)
			protR.HopIndex = i + 1
			sni := strings.TrimSpace(relay.PeerQuicServerName)
			c, err := DialPeerRelayQUIC(q, sni, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, dst, dstPort, token, protR)
			if err == nil {
				SetRouteTrace(dst.String(), mode, fmt.Sprintf("peer_quic:%s", q), "")
				if pm != nil {
					pm.Record(dst, true, decision.RelayClass, decision.PathTTL)
				}
				telemetry.RecordPath(telemetry.SwitchRelay, fmt.Sprintf("peer quic %s hop=%d", q, protR.HopIndex))
				return c, false, false, nil
			}
			clientlog.Warn("vpn: peer quic %s: %v", q, err)
			SetRouteTrace(dst.String(), mode, fmt.Sprintf("peer_quic:%s", q), err.Error())
		}
		decision.PeerQUIC = ""
	}
	if relay != nil && relay.PeerRelayUseUDP {
		var udpCands []string
		if len(decision.PeerUDPCandidates) > 0 {
			udpCands = append([]string(nil), decision.PeerUDPCandidates...)
		} else if strings.TrimSpace(decision.PeerUDP) != "" {
			udpCands = []string{strings.TrimSpace(decision.PeerUDP)}
		}
		for i, ua := range udpCands {
			ua := strings.TrimSpace(ua)
			if ua == "" {
				continue
			}
			protR := RelayProtForPeerHop(dialProt, relay)
			protR.HopIndex = i + 1
			c, err := DialPeerRelayUDP(ua, dst, dstPort, token, protR)
			if err == nil {
				SetRouteTrace(dst.String(), mode, fmt.Sprintf("peer_udp:%s", ua), "")
				if pm != nil {
					pm.Record(dst, true, decision.RelayClass, decision.PathTTL)
				}
				telemetry.RecordPath(telemetry.SwitchRelay, fmt.Sprintf("peer udp %s hop=%d", ua, protR.HopIndex))
				return c, false, true, nil
			}
			clientlog.Warn("vpn: peer udp %s: %v", ua, err)
			SetRouteTrace(dst.String(), mode, fmt.Sprintf("peer_udp:%s", ua), err.Error())
		}
		decision.PeerUDP = ""
		decision.PeerUDPCandidates = nil
	}
	if decision.PeerAddr != "" && relay != nil && (relay.PeerRelayUseTCP == nil || *relay.PeerRelayUseTCP) {
		tcpCands := append([]string(nil), decision.PeerTCPCandidates...)
		if len(tcpCands) == 0 {
			tcpCands = []string{decision.PeerAddr}
		}
		for i, tp := range tcpCands {
			protR := RelayProtForPeerHop(dialProt, relay)
			protR.HopIndex = i + 1
			c, err := DialSingleTCP(tp, dst, dstPort, token, protR)
			if err == nil {
				SetRouteTrace(dst.String(), mode, fmt.Sprintf("peer_tcp:%s", tp), "")
				if pm != nil {
					pm.Record(dst, true, decision.RelayClass, decision.PathTTL)
				}
				telemetry.RecordPath(telemetry.SwitchRelay, fmt.Sprintf("peer tcp %s hop=%d", tp, protR.HopIndex))
				return c, false, true, nil
			}
			clientlog.Warn("vpn: peer relay %s: %v", tp, err)
			SetRouteTrace(dst.String(), mode, fmt.Sprintf("peer_tcp:%s", tp), err.Error())
		}
		decision.RelayClass = PathClassDirect
		decision.PeerAddr = ""
	}
	if dual && quicShared != nil && UsesQUICTransport(transport, quicServer) {
		if sel != nil {
			preferTCP = !sel.PreferQUIC()
		}
		raceStart := time.Now()
		c, quicFailed, tcpUsed, err := dialDualRace(addrs, dst, dstPort, token, dialProt, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, preferTCP)
		if err == nil {
			w := "quic"
			if tcpUsed {
				w = "tcp"
			}
			telemetry.RecordPath(telemetry.SwitchTransport, fmt.Sprintf("dual race first %s %s:%d in %s", w, dst.String(), dstPort, time.Since(raceStart).Round(time.Millisecond)))
		} else if errors.Is(err, context.DeadlineExceeded) {
			telemetry.RecordPath(telemetry.SwitchTransport, fmt.Sprintf("dual race %s timeout %s:%d", DualPathRaceTimeout.String(), dst.String(), dstPort))
		}
		if sel != nil {
			if tcpUsed {
				sel.RecordQuicOutcome(false)
			} else {
				sel.RecordQuicOutcome(true)
			}
			telemetry.SetDpiQuicGoodnessEWMA(sel.QuicGoodnessEWMA())
		}
		if pm != nil {
			pm.Record(dst, err == nil, decision.RelayClass, decision.PathTTL)
		}
		if err == nil {
			return c, quicFailed, tcpUsed, nil
		}
	}
	if preferTCP {
		if pm != nil && decision.PreferTCP && decision.PeerAddr == "" {
			clientlog.Info("vpn: path manager prefers TCP for %s", dst.String())
		}
		fb := fallbackDialProt(dialProt, relay, clusterExit, allowPeerPath)
		c, err := Dial(addrs, dst, dstPort, token, fb, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, true)
		if pm != nil {
			pm.Record(dst, err == nil, decision.RelayClass, decision.PathTTL)
		}
		return c, false, true, err
	}
	fb := fallbackDialProt(dialProt, relay, clusterExit, allowPeerPath)
	c, err := Dial(addrs, dst, dstPort, token, fb, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, false)
	if err != nil && dual && quicShared != nil {
		if sel != nil {
			sel.RecordQuicOutcome(false)
			telemetry.SetDpiQuicGoodnessEWMA(sel.QuicGoodnessEWMA())
		}
		quicErr := err
		if quicTraceOn() {
			clientlog.Trace("tun tcp quic dial failed, fallback tcp: %v", quicErr)
		}
		clientlog.Warn("vpn: tun-tcp QUIC path failed, fallback TCP: %v", quicErr)
		telemetry.NoteFailoverLatency(time.Since(flowStart))
		telemetry.NoteTransportFallback()
		telemetry.RecordPath(telemetry.SwitchTransport, fmt.Sprintf("quic fallback tcp %s:%d %v", dst.String(), dstPort, quicErr))
		c, err = Dial(addrs, dst, dstPort, token, fb, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, true)
		if pm != nil {
			pm.Record(dst, err == nil, decision.RelayClass, decision.PathTTL)
		}
		return c, true, false, err
	}
	if err == nil && sel != nil {
		sel.RecordQuicOutcome(true)
		telemetry.SetDpiQuicGoodnessEWMA(sel.QuicGoodnessEWMA())
	}
	if pm != nil {
		pm.Record(dst, err == nil, decision.RelayClass, decision.PathTTL)
	}
	return c, false, false, err
}

type dualDialRes struct {
	conn     net.Conn
	forceTCP bool
	err      error
}

func dialDualRace(addrs []string, dst net.IP, dstPort uint16, token string, prot *config.ProtectionOptions, transport, quicServer, quicServerName string, quicSkipVerify bool, quicCertPinSHA256 string, quicTLSRoots *x509.CertPool, quicShared *QUICConn, preferTCP bool) (net.Conn, bool, bool, error) {
	ctx, cancel := context.WithTimeout(context.Background(), DualPathRaceTimeout)
	defer cancel()
	resCh := make(chan dualDialRes, 2)
	startDial := func(forceTCP bool, delay time.Duration) {
		go func() {
			if delay > 0 {
				select {
				case <-ctx.Done():
					return
				case <-time.After(delay):
				}
			}
			c, err := Dial(addrs, dst, dstPort, token, prot, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, forceTCP)
			select {
			case resCh <- dualDialRes{conn: c, forceTCP: forceTCP, err: err}:
			case <-ctx.Done():
				if c != nil {
					_ = c.Close()
				}
			}
		}()
	}
	if preferTCP {
		startDial(true, 0)
		startDial(false, 120*time.Millisecond)
	} else {
		startDial(false, 0)
		startDial(true, 120*time.Millisecond)
	}
	var firstErr error
	for i := 0; i < 2; i++ {
		select {
		case <-ctx.Done():
			if firstErr == nil {
				return nil, true, preferTCP, context.DeadlineExceeded
			}
			return nil, true, preferTCP, firstErr
		case r := <-resCh:
			if r.err == nil && r.conn != nil {
				cancel()
				return r.conn, r.forceTCP, r.forceTCP, nil
			}
			if firstErr == nil {
				firstErr = r.err
			}
		}
	}
	if firstErr == nil {
		firstErr = context.DeadlineExceeded
	}
	return nil, true, preferTCP, firstErr
}
