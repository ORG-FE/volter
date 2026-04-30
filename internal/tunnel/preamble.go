package tunnel

import (
	"bufio"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/big"
	"net"
	"strings"
	"time"

	"dev.c0redev.volter/internal/clientlog"
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
		eff := relayOptsForHandshake(prot, token)
		optsJSON, _ = json.Marshal(eff)
		if eff.RelayHop > 0 || eff.RelayMaxHop > 0 || eff.PeerID != "" {
			role = protocol.RoleRelayTCP()
		}
	}
	return protocol.WriteHandshakeWithPrefixAndOptsSlot(w, role, 0, token, prefixLen, optsJSON, slot)
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
			cp.RelayNonce = base64.RawStdEncoding.EncodeToString(rb)
		}
	}
	if cp.PeerID == "" {
		sum := sha256.Sum256([]byte(token))
		cp.PeerID = "p-" + base64.RawStdEncoding.EncodeToString(sum[:6])
	}
	if cp.RelaySig == "" {
		mac := hmac.New(sha256.New, []byte(token))
		_, _ = mac.Write([]byte(cp.PeerID))
		_, _ = mac.Write([]byte("|"))
		_, _ = mac.Write([]byte(cp.RelayNonce))
		cp.RelaySig = base64.RawStdEncoding.EncodeToString(mac.Sum(nil))
	}
	return &cp
}

func DialTunFlow(addrs []string, dst net.IP, dstPort uint16, token string, prot *config.ProtectionOptions, transport, quicServer, quicServerName string, quicSkipVerify bool, quicCertPinSHA256 string, quicTLSRoots *x509.CertPool, quicShared *QUICConn, dual bool, sel *DualPathSelector, pm *PathManager, allowPeerPath bool, relay *config.RelayOptions) (net.Conn, bool, bool, error) {
	flowStart := time.Now()
	preferTCP := false
	decision := PathDecision{RelayClass: PathClassDirect, PathTTL: 1}
	quicEnabled := UsesQUICTransport(transport, quicServer) && quicShared != nil
	if pm != nil {
		decision = pm.Decide(dst, dual, quicEnabled, allowPeerPath)
		if decision.PreferTCP {
			preferTCP = true
		}
	}
	if relay != nil && relay.PeerRelayUseQUIC && strings.TrimSpace(decision.PeerQUIC) != "" {
		protR := RelayProtForPeerHop(prot, relay)
		sni := strings.TrimSpace(relay.PeerQuicServerName)
		c, err := DialPeerRelayQUIC(decision.PeerQUIC, sni, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, dst, dstPort, token, protR)
		if err == nil {
			if pm != nil {
				pm.Record(dst, true, decision.RelayClass, decision.PathTTL)
			}
			telemetry.RecordPath(telemetry.SwitchRelay, fmt.Sprintf("peer quic %s", decision.PeerQUIC))
			return c, false, false, nil
		}
		clientlog.Warn("vpn: peer quic %s: %v", decision.PeerQUIC, err)
		decision.PeerQUIC = ""
	}
	if relay != nil && relay.PeerRelayUseUDP {
		var udpCands []string
		if len(decision.PeerUDPCandidates) > 0 {
			udpCands = append([]string(nil), decision.PeerUDPCandidates...)
		} else if strings.TrimSpace(decision.PeerUDP) != "" {
			udpCands = []string{strings.TrimSpace(decision.PeerUDP)}
		}
		for _, ua := range udpCands {
			ua := strings.TrimSpace(ua)
			if ua == "" {
				continue
			}
			protR := RelayProtForPeerHop(prot, relay)
			c, err := DialPeerRelayUDP(ua, dst, dstPort, token, protR)
			if err == nil {
				if pm != nil {
					pm.Record(dst, true, decision.RelayClass, decision.PathTTL)
				}
				telemetry.RecordPath(telemetry.SwitchRelay, fmt.Sprintf("peer udp %s", ua))
				return c, false, true, nil
			}
			clientlog.Warn("vpn: peer udp %s: %v", ua, err)
		}
		decision.PeerUDP = ""
		decision.PeerUDPCandidates = nil
	}
	if decision.PeerAddr != "" && relay != nil {
		protR := RelayProtForPeerHop(prot, relay)
		c, err := DialSingleTCP(decision.PeerAddr, dst, dstPort, token, protR)
		if err == nil {
			if pm != nil {
				pm.Record(dst, true, decision.RelayClass, decision.PathTTL)
			}
			telemetry.RecordPath(telemetry.SwitchRelay, fmt.Sprintf("peer tcp %s", decision.PeerAddr))
			return c, false, true, nil
		}
		clientlog.Warn("vpn: peer relay %s: %v", decision.PeerAddr, err)
		decision.RelayClass = PathClassDirect
		decision.PeerAddr = ""
	}
	if dual && quicShared != nil && UsesQUICTransport(transport, quicServer) {
		if sel != nil {
			preferTCP = !sel.PreferQUIC()
		}
	}
	if preferTCP {
		if pm != nil && decision.PreferTCP && decision.PeerAddr == "" {
			clientlog.Info("vpn: path manager prefers TCP for %s", dst.String())
		}
		c, err := Dial(addrs, dst, dstPort, token, prot, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, true)
		if pm != nil {
			pm.Record(dst, err == nil, decision.RelayClass, decision.PathTTL)
		}
		return c, false, true, err
	}
	c, err := Dial(addrs, dst, dstPort, token, prot, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, false)
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
		c, err = Dial(addrs, dst, dstPort, token, prot, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, true)
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
