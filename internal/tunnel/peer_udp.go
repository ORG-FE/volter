package tunnel

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"net"
	"strings"
	"syscall"
	"time"

	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/peertransport"
	"dev.c0redev.volter/internal/protocol"
	"dev.c0redev.volter/internal/sockprotect"
)

type udpPeerConn struct {
	c         *net.UDPConn
	pktBuf    []byte
	readCarry []byte
	readOff   int
	asm       peertransport.Assembler
}

func (u *udpPeerConn) Read(p []byte) (int, error) {
	if u.readOff < len(u.readCarry) {
		n := copy(p, u.readCarry[u.readOff:])
		u.readOff += n
		if u.readOff >= len(u.readCarry) {
			u.readCarry = nil
			u.readOff = 0
		}
		return n, nil
	}
	msg, err := peertransport.ReadVP02Message(u.c, u.pktBuf, &u.asm)
	if err != nil {
		return 0, err
	}
	u.readCarry = msg
	u.readOff = 0
	n := copy(p, u.readCarry)
	u.readOff = n
	return n, nil
}

func (u *udpPeerConn) Write(p []byte) (int, error) {
	if err := peertransport.WriteVP02Message(u.c, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (u *udpPeerConn) Close() error                       { return u.c.Close() }
func (u *udpPeerConn) LocalAddr() net.Addr                { return u.c.LocalAddr() }
func (u *udpPeerConn) RemoteAddr() net.Addr               { return u.c.RemoteAddr() }
func (u *udpPeerConn) SetDeadline(t time.Time) error      { return u.c.SetDeadline(t) }
func (u *udpPeerConn) SetReadDeadline(t time.Time) error  { return u.c.SetReadDeadline(t) }
func (u *udpPeerConn) SetWriteDeadline(t time.Time) error { return u.c.SetWriteDeadline(t) }
func dialPeerUDP(addr string) (*net.UDPConn, error) {
	ra, err := net.ResolveUDPAddr("udp", addr)
	if err != nil {
		return nil, err
	}
	d := net.Dialer{Timeout: volterWireHandshakeTimeout}
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
	conn, err := d.Dial("udp", ra.String())
	if err != nil {
		return nil, err
	}
	uc, ok := conn.(*net.UDPConn)
	if !ok {
		_ = conn.Close()
		return nil, errors.New("tunnel: dial udp conn")
	}
	return uc, nil
}

func DialPeerRelayUDP(addr string, targetIP net.IP, targetPort uint16, token string, prot *config.ProtectionOptions) (net.Conn, error) {
	uc, err := dialPeerUDP(addr)
	if err != nil {
		return nil, err
	}
	_ = uc.SetDeadline(time.Now().Add(volterWireHandshakeTimeout))
	slot := SlotForProtection(prot)
	bufSize := protocol.BufSizeForConn(slot)
	var raw bytes.Buffer
	w := bufio.NewWriter(&raw)
	if err := tcpRelayPreamble(w, token, prot, slot); err != nil {
		_ = uc.Close()
		return nil, err
	}
	if err := protocol.WriteTcpConnect(w, targetIP, targetPort); err != nil {
		_ = uc.Close()
		return nil, err
	}
	if err := w.Flush(); err != nil {
		_ = uc.Close()
		return nil, err
	}
	if err := peertransport.WriteVP02Message(uc, raw.Bytes()); err != nil {
		_ = uc.Close()
		return nil, err
	}
	_ = uc.SetDeadline(time.Time{})
	base := &udpPeerConn{c: uc, pktBuf: make([]byte, 65536)}
	r := bufio.NewReaderSize(base, bufSize)
	if needHopAck(prot) {
		ack, err := protocol.ReadHopAck(r)
		if err != nil {
			_ = uc.Close()
			return nil, err
		}
		if ack.Status == 0 {
			_ = uc.Close()
			SetRouteTrace(targetIP.String(), strings.TrimSpace(prot.RouteMode), "", ack.Reason)
			return nil, fmt.Errorf("hop ack rejected: %s", ack.Reason)
		}
	}
	return &tunnelConn{Conn: base, r: r}, nil
}
