package tunnel

import (
	"crypto/x509"
	"errors"
	"fmt"
	"net"
	"strings"

	"dev.c0redev.volter/internal/clientlog"
	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/protocol"
)

func runPeerRelaySession(s *peerUDPSession, opts *config.ProtectionOptions, tc *protocol.TcpConnect, token string, budget *ByteBucket) error {
	if relayHopLimitExceeded(opts) {
		return errors.New(relayHopLimitReason(opts))
	}
	kind, addr, hasNext := NextRelayHop(opts.RelayRouteHops, opts.HopIndex)
	if !hasNext {
		target, err := dialPeerRelayTarget(tc.IP, tc.Port)
		if err != nil {
			return err
		}
		defer target.Close()
		errc := make(chan error, 2)
		go func() { errc <- copyRelay(target, s, budget) }()
		go func() { errc <- copyRelay(s, target, budget) }()
		return <-errc
	}
	fwd := ProtForRelayForward(opts, token)
	if fwd == nil {
		return errors.New("peer relay: bad forward opts")
	}
	clientlog.Info("peer relay forward hop=%d -> %s %s", fwd.HopIndex, kind, addr)
	nextConn, err := dialRelayHop(kind, addr, tc.IP, tc.Port, token, fwd)
	if err != nil {
		return err
	}
	defer nextConn.Close()
	errc := make(chan error, 2)
	go func() { errc <- copyRelay(nextConn, s, budget) }()
	go func() { errc <- copyRelay(s, nextConn, budget) }()
	return <-errc
}

func dialRouteHopConn(kind, addr string, dstIP net.IP, dstPort uint16, token string, prot *config.ProtectionOptions, transport, quicServer, quicServerName string, quicSkipVerify bool, quicCertPinSHA256 string, quicTLSRoots *x509.CertPool, quicShared *QUICConn) (net.Conn, bool, error) {
	c, err := dialRelayHop(kind, addr, dstIP, dstPort, token, prot)
	if err != nil {
		return nil, false, err
	}
	return c, strings.HasPrefix(strings.ToLower(kind), "peer_tcp") || strings.Contains(kind, "server"), nil
}

func fallbackDialProt(dialProt *config.ProtectionOptions, relay *config.RelayOptions, clusterExit, allowPeerPath bool) *config.ProtectionOptions {
	if clusterExit {
		return protForServerRelayRoute(dialProt, relay)
	}
	if !allowPeerPath {
		return protForDirectRoute(dialProt)
	}
	cp := protForDirectRoute(dialProt)
	if cp == nil {
		return nil
	}
	return cp
}

func dialRelayHop(kind, addr string, dstIP net.IP, dstPort uint16, token string, prot *config.ProtectionOptions) (net.Conn, error) {
	switch strings.ToLower(strings.TrimSpace(kind)) {
	case "peer_udp":
		return DialPeerRelayUDP(addr, dstIP, dstPort, token, prot)
	case "peer_quic":
		return DialPeerRelayQUIC(addr, "", false, "", nil, dstIP, dstPort, token, prot)
	case "peer_tcp":
		return DialSingleTCP(addr, dstIP, dstPort, token, prot)
	case "server_relay", "direct_server":
		srv := []string{addr}
		dialProt := protForServerRelayRoute(prot, nil)
		return Dial(srv, dstIP, dstPort, token, dialProt, "", "", "", false, "", nil, nil, true)
	default:
		return nil, fmt.Errorf("peer relay: unknown hop kind %q", kind)
	}
}
