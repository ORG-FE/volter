package tunnel

import (
	"bufio"
	"crypto/rand"
	"crypto/x509"
	"encoding/json"
	"math/big"
	"net"
	"strings"

	"dev.c0redev.volter/internal/clientlog"
	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/protocol"
)

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
	if err = protocol.WritePreamble(w, kind, jc, jmin, jmax, flush, func() { _ = w.Flush() }); err != nil {
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
	return WriteUDPChannelPreambleSlot(w, channelID, token, prot, protocol.TimeSlot())
}

func tcpRelayPreamble(w *bufio.Writer, token string, prot *config.ProtectionOptions, slot int64) error {
	_, prefixLen, jc, jmin, jmax, jstyle, flush := streamObf(prot, slot, false)
	kind := resolvePreambleKind(prot, slot, token, jstyle)
	if err := protocol.WritePreamble(w, kind, jc, jmin, jmax, flush, func() { _ = w.Flush() }); err != nil {
		return err
	}
	if !strings.EqualFold(flush, "perChunk") {
		_ = w.Flush()
	}
	var optsJSON []byte
	if prot != nil {
		optsJSON, _ = json.Marshal(prot)
	}
	return protocol.WriteHandshakeWithPrefixAndOptsSlot(w, protocol.RoleTCP(), 0, token, prefixLen, optsJSON, slot)
}

func DialTunFlow(addrs []string, dst net.IP, dstPort uint16, token string, prot *config.ProtectionOptions, transport, quicServer, quicServerName string, quicSkipVerify bool, quicCertPinSHA256 string, quicTLSRoots *x509.CertPool, quicShared *QUICConn, dual bool, sel *DualPathSelector) (net.Conn, bool, bool, error) {
	preferTCP := false
	if dual && quicShared != nil && UsesQUICTransport(transport, quicServer) {
		if sel != nil {
			preferTCP = !sel.PreferQUIC()
		}
	}
	if preferTCP {
		c, err := Dial(addrs, dst, dstPort, token, prot, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, true)
		return c, false, true, err
	}
	c, err := Dial(addrs, dst, dstPort, token, prot, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, false)
	if err != nil && dual && quicShared != nil {
		if sel != nil {
			sel.RecordQuicOutcome(false)
		}
		quicErr := err
		if quicTraceOn() {
			clientlog.Trace("tun tcp quic dial failed, fallback tcp: %v", quicErr)
		}
		clientlog.Warn("vpn: tun-tcp QUIC path failed, fallback TCP: %v", quicErr)
		c, err = Dial(addrs, dst, dstPort, token, prot, transport, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, quicShared, true)
		return c, true, false, err
	}
	if err == nil && sel != nil {
		sel.RecordQuicOutcome(true)
	}
	return c, false, false, err
}
