package tunnel

import (
	"bufio"
	"context"
	"crypto/x509"
	"fmt"
	"math/rand"
	"net"
	"strings"
	"syscall"
	"time"

	"dev.c0redev.volter/internal/clientlog"
	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/obfuscate"
	"dev.c0redev.volter/internal/protocol"
	"dev.c0redev.volter/internal/sockprotect"
)

const routeHandshakeTimeout = 3 * time.Second

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
	for round := 0; round < 3; round++ {
		for i := 0; i < len(serverAddrs); i++ {
			if round == 0 && i == 0 {
				time.Sleep(time.Duration(rand.Int63n(56)) * time.Millisecond)
			}
			addr := serverAddrs[(start+i)%len(serverAddrs)]
			c, err := dialServer(addr, token)
			if err != nil {
				lastErr = err
				continue
			}
			_ = c.SetDeadline(time.Now().Add(routeHandshakeTimeout))
			slot := SlotForProtection(prot)
			bufSize := protocol.BufSizeForConn(slot)
			r := bufio.NewReaderSize(c, bufSize)
			w := bufio.NewWriterSize(c, bufSize)
			if err := tcpRelayPreamble(w, token, prot, slot); err != nil {
				_ = c.Close()
				lastErr = err
				continue
			}
			if err := protocol.WriteTcpConnect(w, targetIP, targetPort); err != nil {
				_ = c.Close()
				lastErr = err
				continue
			}
			if needHopAck(prot) {
				ack, err := protocol.ReadHopAck(r)
				if err != nil {
					_ = c.Close()
					lastErr = err
					continue
				}
				if ack.Status == 0 {
					_ = c.Close()
					lastErr = fmt.Errorf("hop ack rejected: %s", ack.Reason)
					SetRouteTrace(targetIP.String(), strings.TrimSpace(prot.RouteMode), "", ack.Reason)
					continue
				}
			}
			_ = c.SetDeadline(time.Time{})
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
	return nil, lastErr
}

func DialSingleTCP(addr string, targetIP net.IP, targetPort uint16, token string, prot *config.ProtectionOptions) (net.Conn, error) {
	c, err := dialServer(addr, token)
	if err != nil {
		return nil, err
	}
	_ = c.SetDeadline(time.Now().Add(routeHandshakeTimeout))
	slot := SlotForProtection(prot)
	bufSize := protocol.BufSizeForConn(slot)
	r := bufio.NewReaderSize(c, bufSize)
	w := bufio.NewWriterSize(c, bufSize)
	if err := tcpRelayPreamble(w, token, prot, slot); err != nil {
		_ = c.Close()
		return nil, err
	}
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

func dialServer(addr, token string) (net.Conn, error) {
	d := net.Dialer{Timeout: routeHandshakeTimeout, KeepAlive: 30 * time.Second}
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
	return obfuscate.WrapConn(c, token), nil
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
	streamCtx, streamCancel := context.WithTimeout(context.Background(), routeHandshakeTimeout)
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
	slot := SlotForProtection(prot)
	w := bufio.NewWriterSize(sconn, protocol.BufSizeForConn(slot))
	r := bufio.NewReaderSize(sconn, protocol.BufSizeForConn(slot))
	if err := tcpRelayPreamble(w, token, prot, slot); err != nil {
		_ = sconn.Close()
		return nil, err
	}
	if err := protocol.WriteTcpConnect(w, targetIP, targetPort); err != nil {
		_ = sconn.Close()
		return nil, err
	}
	if needHopAck(prot) {
		ack, err := protocol.ReadHopAck(r)
		if err != nil {
			_ = sconn.Close()
			return nil, err
		}
		if ack.Status == 0 {
			_ = sconn.Close()
			return nil, fmt.Errorf("hop ack rejected: %s", ack.Reason)
		}
	}
	return &tunnelConn{Conn: sconn, r: r}, nil
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
	streamCtx, streamCancel := context.WithTimeout(context.Background(), routeHandshakeTimeout)
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
	_ = sconn.SetDeadline(time.Now().Add(routeHandshakeTimeout))
	slot := SlotForProtection(prot)
	w := bufio.NewWriterSize(sconn, protocol.BufSizeForConn(slot))
	r := bufio.NewReaderSize(sconn, protocol.BufSizeForConn(slot))
	if err := tcpRelayPreamble(w, token, prot, slot); err != nil {
		_ = sconn.Close()
		return nil, err
	}
	if err := protocol.WriteTcpConnect(w, targetIP, targetPort); err != nil {
		_ = sconn.Close()
		return nil, err
	}
	if needHopAck(prot) {
		ack, err := protocol.ReadHopAck(r)
		if err != nil {
			_ = sconn.Close()
			return nil, err
		}
		if ack.Status == 0 {
			_ = sconn.Close()
			return nil, fmt.Errorf("hop ack rejected: %s", ack.Reason)
		}
	}
	_ = sconn.SetDeadline(time.Time{})
	return &tunnelConn{Conn: sconn, r: r}, nil
}

func needHopAck(prot *config.ProtectionOptions) bool {
	if prot == nil {
		return false
	}
	if prot.RelayHop > 0 {
		return true
	}
	mode := strings.TrimSpace(strings.ToLower(prot.RouteMode))
	return mode == "server_relay" || mode == "peer_relay"
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
