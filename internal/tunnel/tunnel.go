package tunnel

import (
	"bufio"
	"context"
	"crypto/x509"
	"fmt"
	"net"
	"strings"
	"syscall"
	"time"

	"dev.c0redev.volter/internal/clientlog"
	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/dexote"
	"dev.c0redev.volter/internal/obfuscate"
	"dev.c0redev.volter/internal/protocol"
	"dev.c0redev.volter/internal/sockprotect"
)

const volterWireHandshakeTimeout = 12 * time.Second

const DualPathRaceTimeout = 12 * time.Second

func Dial(serverAddrs []string, targetIP net.IP, targetPort uint16, token string, prot *config.ProtectionOptions, transport, quicServer, quicServerName string, quicSkipVerify bool, quicCertPinSHA256 string, quicTLSRoots *x509.CertPool, quicShared *QUICConn, tunPreferTCP bool) (net.Conn, error) {
	if !tunPreferTCP && UsesQUICTransport(transport, quicServer) {
		return dialQUIC(serverAddrs, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, targetIP, targetPort, token, prot, quicShared)
	}
	if len(serverAddrs) == 0 {
		return nil, fmt.Errorf("tcp dial: empty server addresses")
	}
	start := pickAddrIndex(serverAddrs, targetIP, targetPort)
	var lastErr error
	backoff := 200 * time.Millisecond
	clientlog.Trace("Dial start dst=%s:%d addrs=%v start=%d needHopAck=%v",
		targetIP.String(), targetPort, serverAddrs, start, needHopAck(prot))
	for round := 0; round < 3; round++ {
		for i := 0; i < len(serverAddrs); i++ {
			addr := serverAddrs[(start+i)%len(serverAddrs)]
			clientlog.Trace("Dial attempt round=%d i=%d addr=%s", round, i, addr)
			slot := SlotForProtection(prot)
			role, optsJSON := dexotePayload(token, prot)
			c, _, err := dialServerDexote(addr, token, prot, slot, role, optsJSON)
			if err != nil {
				clientlog.Trace("Dial dialServerDexote %s: %v", addr, err)
				lastErr = err
				continue
			}
			_ = c.SetDeadline(time.Now().Add(volterWireHandshakeTimeout))
			r, w := newDexoteRW(c, slot)
			if err := protocol.WriteTcpConnect(w, targetIP, targetPort); err != nil {
				_ = c.Close()
				clientlog.Trace("Dial WriteTcpConnect %s: %v", addr, err)
				lastErr = err
				continue
			}
			if needHopAck(prot) {
				clientlog.Trace("Dial reading HopAck from %s", addr)
				ack, err := protocol.ReadHopAck(r)
				if err != nil {
					_ = c.Close()
					clientlog.Trace("Dial HopAck read %s: %v", addr, err)
					lastErr = err
					continue
				}
				if ack.Status == 0 {
					_ = c.Close()
					lastErr = fmt.Errorf("hop ack rejected: %s", ack.Reason)
					clientlog.Trace("Dial HopAck rejected %s: %s", addr, ack.Reason)
					SetRouteTrace(targetIP.String(), strings.TrimSpace(prot.RouteMode), "", ack.Reason)
					continue
				}
				clientlog.Trace("Dial HopAck OK from %s (status=1)", addr)
			}
			_ = c.SetDeadline(time.Time{})
			setActiveVolterServerAddr(addr)
			clientlog.Trace("Dial success addr=%s", addr)
			return &tunnelConn{Conn: c, r: r}, nil
		}
		time.Sleep(backoff)
		if backoff < 1200*time.Millisecond {
			backoff *= 2
		}
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("tcp dial failed")
	}
	clientlog.Trace("Dial all attempts failed for %s:%d: %v", targetIP.String(), targetPort, lastErr)
	return nil, lastErr
}

func DialSingleTCP(addr string, targetIP net.IP, targetPort uint16, token string, prot *config.ProtectionOptions) (net.Conn, error) {
	slot := SlotForProtection(prot)
	role, optsJSON := dexotePayload(token, prot)
	c, _, err := dialServerDexote(addr, token, prot, slot, role, optsJSON)
	if err != nil {
		return nil, err
	}
	_ = c.SetDeadline(time.Now().Add(volterWireHandshakeTimeout))
	r, w := newDexoteRW(c, slot)
	if err := protocol.WriteTcpConnect(w, targetIP, targetPort); err != nil {
		_ = c.Close()
		return nil, err
	}
	if needHopAck(prot) {
		ack, err := protocol.ReadHopAck(r)
		if err != nil {
			_ = c.Close()
			return nil, err
		}
		if ack.Status == 0 {
			_ = c.Close()
			return nil, fmt.Errorf("hop ack rejected: %s", ack.Reason)
		}
	}
	_ = c.SetDeadline(time.Time{})
	return &tunnelConn{Conn: c, r: r}, nil
}

func UsesQUICTransport(transport, quicServer string) bool {
	if strings.EqualFold(transport, "tcp") {
		return false
	}
	if strings.EqualFold(transport, "quic") {
		return true
	}
	return strings.TrimSpace(quicServer) != ""
}

func ResolvedTransportLabel(transport, quicServer string) string {
	if UsesQUICTransport(transport, quicServer) {
		return "quic"
	}
	return "tcp"
}

func VolterTunnelTag(transport, quicServer string) string {
	if UsesQUICTransport(transport, quicServer) {
		return "QUIC"
	}
	return "TCP"
}

type tunnelConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *tunnelConn) Read(p []byte) (n int, err error) {
	return c.r.Read(p)
}

func dialServerRaw(addr string) (net.Conn, error) {
	d := net.Dialer{Timeout: volterWireHandshakeTimeout, KeepAlive: 30 * time.Second}
	if p := sockprotect.Protect; p != nil {
		d.Control = func(network, address string, c syscall.RawConn) error {
			var err error
			e := c.Control(func(fd uintptr) {
				err = p(fd)
			})
			if e != nil {
				return e
			}
			return err
		}
	}
	c, err := d.Dial("tcp", addr)
	if err != nil {
		return nil, err
	}
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	return c, nil
}

func dialQUIC(serverAddrs []string, quicServer, quicServerName string, quicSkipVerify bool, quicCertPinSHA256 string, quicTLSRoots *x509.CertPool, targetIP net.IP, targetPort uint16, token string, prot *config.ProtectionOptions, quicShared *QUICConn) (net.Conn, error) {
	ownsConn := quicShared == nil
	var conn *QUICConn
	var err error
	var closePC func()
	if ownsConn {
		addr, _, err := ResolveQUICDialAddr(serverAddrs, quicServer)
		if err != nil {
			return nil, err
		}
		conn, closePC, err = dialQUICConn(addr, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots)
		if err != nil {
			return nil, err
		}
	} else {
		conn = quicShared
	}
	if quicTraceOn() {
		clientlog.Trace("quic tun-tcp OpenStreamSync")
	}
	streamCtx, streamCancel := context.WithTimeout(context.Background(), volterWireHandshakeTimeout)
	stream, err := conn.OpenStreamSync(streamCtx)
	streamCancel()
	if err != nil {
		if quicTraceOn() {
			clientlog.Trace("quic tun-tcp OpenStreamSync err=%v", err)
		}
		if ownsConn {
			_ = conn.CloseWithError(0, "open stream failed")
			if closePC != nil {
				closePC()
			}
		}
		return nil, err
	}
	var sconn net.Conn
	if ownsConn {
		sconn = newQUICStreamConn(conn, stream, closePC)
	} else {
		sconn = &quicStreamOnlyConn{conn: conn, stream: stream}
	}
	_ = sconn.SetDeadline(time.Now().Add(volterWireHandshakeTimeout))
	slot := SlotForProtection(prot)
	role, optsJSON := dexotePayload(token, prot)
	pub := getDexoteServerPub()
	if len(pub) == 0 {
		_ = sconn.Close()
		return nil, fmt.Errorf("dexote: server pubkey not configured (set dexoteServerPub)")
	}
	keys, _, err := dexote.ClientHandshake(sconn, pub, slot, dexote.ClientHelloPayload{
		Role:  role,
		Token: token,
		Opts:  optsJSON,
	})
	if err != nil {
		_ = sconn.Close()
		return nil, fmt.Errorf("dexote quic handshake: %w", err)
	}
	wrapped := obfuscate.WrapAEADShaped(sconn, keys,
		dexote.NewPoly(keys.Secret, slot, "tx"),
		dexote.NewPoly(keys.Secret, slot, "rx"), dexotePadMax(prot),
		buildAEADShapeHook(prot, slot))
	r, w := newDexoteRW(wrapped, slot)
	if err := protocol.WriteTcpConnect(w, targetIP, targetPort); err != nil {
		_ = wrapped.Close()
		return nil, err
	}
	if needHopAck(prot) {
		ack, err := protocol.ReadHopAck(r)
		if err != nil {
			_ = wrapped.Close()
			return nil, err
		}
		if ack.Status == 0 {
			_ = wrapped.Close()
			return nil, fmt.Errorf("hop ack rejected: %s", ack.Reason)
		}
	}
	_ = wrapped.SetDeadline(time.Time{})
	if ownsConn {
		setActiveVolterServerAddr(PickServerAddrForQUIC(serverAddrs, quicServer))
	}
	return &tunnelConn{Conn: wrapped, r: r}, nil
}

func DialPeerRelayQUIC(addr, serverName string, quicSkipVerify bool, quicCertPinSHA256 string, quicTLSRoots *x509.CertPool, targetIP net.IP, targetPort uint16, token string, prot *config.ProtectionOptions) (net.Conn, error) {
	addr = strings.TrimSpace(addr)
	if addr == "" {
		return nil, fmt.Errorf("peer quic: empty addr")
	}
	conn, closePC, err := dialQUICConn(addr, serverName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots)
	if err != nil {
		return nil, err
	}
	if quicTraceOn() {
		clientlog.Trace("quic peer tun-tcp OpenStreamSync")
	}
	streamCtx, streamCancel := context.WithTimeout(context.Background(), volterWireHandshakeTimeout)
	stream, err := conn.OpenStreamSync(streamCtx)
	streamCancel()
	if err != nil {
		if quicTraceOn() {
			clientlog.Trace("quic peer OpenStreamSync err=%v", err)
		}
		_ = conn.CloseWithError(0, "open stream failed")
		if closePC != nil {
			closePC()
		}
		return nil, err
	}
	sconn := newQUICStreamConn(conn, stream, closePC)
	_ = sconn.SetDeadline(time.Now().Add(volterWireHandshakeTimeout))
	slot := SlotForProtection(prot)
	role, optsJSON := dexotePayload(token, prot)
	pub := getDexoteServerPub()
	if len(pub) == 0 {
		_ = sconn.Close()
		return nil, fmt.Errorf("dexote: server pubkey not configured (set dexoteServerPub)")
	}
	keys, _, err := dexote.ClientHandshake(sconn, pub, slot, dexote.ClientHelloPayload{
		Role:  role,
		Token: token,
		Opts:  optsJSON,
	})
	if err != nil {
		_ = sconn.Close()
		return nil, fmt.Errorf("dexote peer quic handshake: %w", err)
	}
	wrapped := obfuscate.WrapAEADShaped(sconn, keys,
		dexote.NewPoly(keys.Secret, slot, "tx"),
		dexote.NewPoly(keys.Secret, slot, "rx"), dexotePadMax(prot),
		buildAEADShapeHook(prot, slot))
	r, w := newDexoteRW(wrapped, slot)
	if err := protocol.WriteTcpConnect(w, targetIP, targetPort); err != nil {
		_ = wrapped.Close()
		return nil, err
	}
	if needHopAck(prot) {
		ack, err := protocol.ReadHopAck(r)
		if err != nil {
			_ = wrapped.Close()
			return nil, err
		}
		if ack.Status == 0 {
			_ = wrapped.Close()
			return nil, fmt.Errorf("hop ack rejected: %s", ack.Reason)
		}
	}
	_ = wrapped.SetDeadline(time.Time{})
	return &tunnelConn{Conn: wrapped, r: r}, nil
}

func needHopAck(prot *config.ProtectionOptions) bool {
	if prot == nil {
		return false
	}
	if prot.RoutePlannerV2 {
		clientlog.Trace("needHopAck: true (RoutePlannerV2)")
		return true
	}
	if strings.TrimSpace(prot.RouteID) != "" {
		clientlog.Trace("needHopAck: true (RouteID=%q)", strings.TrimSpace(prot.RouteID))
		return true
	}
	clientlog.Trace("needHopAck: false (no RoutePlannerV2, no RouteID)")
	return false
}

func pickAddr(addrs []string, ip net.IP, port uint16) string {
	return addrs[pickAddrIndex(addrs, ip, port)]
}

func pickAddrIndex(addrs []string, ip net.IP, port uint16) int {
	if len(addrs) == 1 {
		return 0
	}
	h := uint(0)
	for _, b := range []byte(ip.String()) {
		h = h*31 + uint(b)
	}
	h += uint(port)
	return int(h) % len(addrs)
}
