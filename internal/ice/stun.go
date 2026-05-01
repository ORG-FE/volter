package ice

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"syscall"
	"time"

	"dev.c0redev.volter/internal/sockprotect"
)

const stunMagicCookie = 0x2112A442

var (
	DefaultSTUNServers = []string{
		"stun.rtc.yandex.net:3478",
		"stun.l.google.com:19302",
	}
)

type SrflxResult struct {
	IP     net.IP
	Port   uint16
	RTT    time.Duration
	Server string
	Kind   CandidateKind
}

func GatherSrflx(ctx context.Context, servers []string) (*SrflxResult, error) {
	if len(servers) == 0 {
		servers = DefaultSTUNServers
	}
	var lastErr error
	for _, hostPort := range servers {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		r, err := gatherOneUDP(ctx, hostPort)
		if err != nil {
			r, err = gatherOneTCP(ctx, hostPort)
		}
		if err == nil {
			r.Kind = CandidateSrflx
			return r, nil
		}
		lastErr = err
	}
	if lastErr == nil {
		lastErr = errors.New("ice: no stun servers")
	}
	return nil, lastErr
}

func GatherSrflxOnUDP(ctx context.Context, uc *net.UDPConn, servers []string) (*SrflxResult, error) {
	if uc == nil {
		return nil, errors.New("ice: nil udp conn")
	}
	if len(servers) == 0 {
		servers = DefaultSTUNServers
	}
	var lastErr error
	for _, hostPort := range servers {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		network, clean := stunServerSpec(hostPort)
		if network == "tcp" {
			lastErr = errors.New("ice: tcp-only stun is unsupported for shared udp socket")
			continue
		}
		r, err := gatherOneOnUDP(ctx, uc, clean)
		if err == nil {
			r.Kind = CandidateSrflx
			return r, nil
		}
		lastErr = err
	}
	if lastErr == nil {
		lastErr = errors.New("ice: no stun servers")
	}
	return nil, lastErr
}

func gatherOneUDP(ctx context.Context, hostPort string) (*SrflxResult, error) {
	network, clean := stunServerSpec(hostPort)
	if network == "tcp" {
		return gatherOneTCP(ctx, clean)
	}
	addr, err := net.ResolveUDPAddr("udp", clean)
	if err != nil {
		return nil, err
	}
	d := net.Dialer{}
	if p := sockprotect.Protect; p != nil {
		d.Control = func(network, address string, c syscall.RawConn) error {
			var ctrlErr error
			if err := c.Control(func(fd uintptr) {
				ctrlErr = p(fd)
			}); err != nil {
				return err
			}
			return ctrlErr
		}
	}
	conn, err := d.DialContext(ctx, "udp", addr.String())
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	uc, ok := conn.(*net.UDPConn)
	if !ok {
		return nil, errors.New("ice: not udp conn")
	}

	var txID [12]byte
	if _, err := rand.Read(txID[:]); err != nil {
		return nil, err
	}
	req := buildBindingRequest(txID)

	deadline, hasDL := ctx.Deadline()
	if !hasDL {
		deadline = time.Now().Add(4 * time.Second)
	}
	_ = uc.SetDeadline(deadline)

	t0 := time.Now()
	if _, err := uc.Write(req); err != nil {
		return nil, err
	}
	buf := make([]byte, 2048)
	n, err := uc.Read(buf)
	if err != nil {
		return nil, err
	}
	rtt := time.Since(t0)

	ip, port, err := parseBindingResponse(buf[:n], txID)
	if err != nil {
		return nil, err
	}
	return &SrflxResult{IP: ip, Port: port, RTT: rtt, Server: clean + "/udp"}, nil
}

func gatherOneTCP(ctx context.Context, hostPort string) (*SrflxResult, error) {
	_, clean := stunServerSpec(hostPort)
	d := net.Dialer{}
	if p := sockprotect.Protect; p != nil {
		d.Control = func(network, address string, c syscall.RawConn) error {
			var ctrlErr error
			if err := c.Control(func(fd uintptr) {
				ctrlErr = p(fd)
			}); err != nil {
				return err
			}
			return ctrlErr
		}
	}
	conn, err := d.DialContext(ctx, "tcp", clean)
	if err != nil {
		return nil, err
	}
	defer conn.Close()

	var txID [12]byte
	if _, err := rand.Read(txID[:]); err != nil {
		return nil, err
	}
	req := buildBindingRequest(txID)
	deadline, hasDL := ctx.Deadline()
	if !hasDL {
		deadline = time.Now().Add(5 * time.Second)
	}
	_ = conn.SetDeadline(deadline)

	t0 := time.Now()
	if _, err := conn.Write(req); err != nil {
		return nil, err
	}
	hdr := make([]byte, 20)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		return nil, err
	}
	ln := int(binary.BigEndian.Uint16(hdr[2:4]))
	if ln < 0 || ln > 64*1024 {
		return nil, errors.New("stun tcp: bad len")
	}
	body := make([]byte, ln)
	if _, err := io.ReadFull(conn, body); err != nil {
		return nil, err
	}
	pkt := append(hdr, body...)
	rtt := time.Since(t0)
	ip, port, err := parseBindingResponse(pkt, txID)
	if err != nil {
		return nil, err
	}
	return &SrflxResult{IP: ip, Port: port, RTT: rtt, Server: clean + "/tcp"}, nil
}

func gatherOneOnUDP(ctx context.Context, uc *net.UDPConn, hostPort string) (*SrflxResult, error) {
	addr, err := net.ResolveUDPAddr("udp", hostPort)
	if err != nil {
		return nil, err
	}
	var txID [12]byte
	if _, err := rand.Read(txID[:]); err != nil {
		return nil, err
	}
	req := buildBindingRequest(txID)
	deadline, hasDL := ctx.Deadline()
	if !hasDL {
		deadline = time.Now().Add(4 * time.Second)
	}
	_ = uc.SetDeadline(deadline)
	defer func() { _ = uc.SetDeadline(time.Time{}) }()

	t0 := time.Now()
	if _, err := uc.WriteToUDP(req, addr); err != nil {
		return nil, err
	}
	buf := make([]byte, 2048)
	for {
		n, raddr, err := uc.ReadFromUDP(buf)
		if err != nil {
			return nil, err
		}
		if raddr == nil || !raddr.IP.Equal(addr.IP) || raddr.Port != addr.Port {
			continue
		}
		rtt := time.Since(t0)
		ip, port, err := parseBindingResponse(buf[:n], txID)
		if err != nil {
			return nil, err
		}
		return &SrflxResult{IP: ip, Port: port, RTT: rtt, Server: hostPort}, nil
	}
}

func stunServerSpec(hostPort string) (network string, addr string) {
	s := strings.TrimSpace(hostPort)
	network = "udp"
	if strings.HasPrefix(s, "udp://") {
		return "udp", strings.TrimPrefix(s, "udp://")
	}
	if strings.HasPrefix(s, "tcp://") {
		return "tcp", strings.TrimPrefix(s, "tcp://")
	}
	return network, s
}

func buildBindingRequest(txID [12]byte) []byte {
	out := make([]byte, 20)
	binary.BigEndian.PutUint16(out[0:2], 0x0001)
	binary.BigEndian.PutUint16(out[2:4], 0)
	binary.BigEndian.PutUint32(out[4:8], stunMagicCookie)
	copy(out[8:20], txID[:])
	return out
}

func parseBindingResponse(pkt []byte, wantTx [12]byte) (net.IP, uint16, error) {
	if len(pkt) < 20 {
		return nil, 0, errors.New("stun: packet too short")
	}
	typ := binary.BigEndian.Uint16(pkt[0:2])
	if typ&0x0110 != 0x0100 {
		return nil, 0, fmt.Errorf("stun: not success response %04x", typ)
	}
	length := int(binary.BigEndian.Uint16(pkt[2:4]))
	if len(pkt) < 20+length {
		return nil, 0, errors.New("stun: truncated body")
	}
	if binary.BigEndian.Uint32(pkt[4:8]) != stunMagicCookie {
		return nil, 0, errors.New("stun: bad magic cookie")
	}
	var tx [12]byte
	copy(tx[:], pkt[8:20])
	if tx != wantTx {
		return nil, 0, errors.New("stun: transaction mismatch")
	}
	pos := 20
	end := 20 + length
	for pos+4 <= end {
		at := binary.BigEndian.Uint16(pkt[pos : pos+2])
		al := int(binary.BigEndian.Uint16(pkt[pos+2 : pos+4]))
		pos += 4
		pad := (al + 3) &^ 3
		if pos+al > end {
			return nil, 0, errors.New("stun: bad attribute length")
		}
		val := pkt[pos : pos+al]
		pos += pad
		switch at {
		case 0x0020:
			if ip, port := decodeXorMapped(val, wantTx); ip != nil {
				return ip, port, nil
			}
		case 0x0001:
			if ip, port := decodeMapped(val); ip != nil {
				return ip, port, nil
			}
		}
	}
	return nil, 0, errors.New("stun: no mapped address")
}

func decodeXorMapped(v []byte, txID [12]byte) (net.IP, uint16) {
	if len(v) < 8 {
		return nil, 0
	}
	_ = v[0]
	fam := v[1]
	xport := binary.BigEndian.Uint16(v[2:4]) ^ uint16(stunMagicCookie>>16)
	switch fam {
	case 1:
		if len(v) < 8 {
			return nil, 0
		}
		x := binary.BigEndian.Uint32(v[4:8]) ^ uint32(stunMagicCookie)
		ip := make(net.IP, 4)
		binary.BigEndian.PutUint32(ip, x)
		return ip, xport
	case 2:
		if len(v) < 20 {
			return nil, 0
		}
		var key [16]byte
		binary.BigEndian.PutUint32(key[0:4], stunMagicCookie)
		copy(key[4:16], txID[:])
		ip := make(net.IP, 16)
		for i := 0; i < 16; i++ {
			ip[i] = v[4+i] ^ key[i]
		}
		return ip, xport
	default:
		return nil, 0
	}
}

func decodeMapped(v []byte) (net.IP, uint16) {
	if len(v) < 8 {
		return nil, 0
	}
	_ = v[0]
	fam := v[1]
	port := binary.BigEndian.Uint16(v[2:4])
	switch fam {
	case 1:
		if len(v) < 8 {
			return nil, 0
		}
		return net.IPv4(v[4], v[5], v[6], v[7]), port
	case 2:
		if len(v) < 20 {
			return nil, 0
		}
		ip := make(net.IP, 16)
		copy(ip, v[4:20])
		return ip, port
	default:
		return nil, 0
	}
}
