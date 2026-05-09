package tunnel

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"net"
	"sync"
	"syscall"
	"time"

	"dev.c0redev.volter/internal/ice"
	"dev.c0redev.volter/internal/sockprotect"
)

func protectControl(p func(uintptr) error) func(string, string, syscall.RawConn) error {
	return func(network, address string, c syscall.RawConn) error {
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

type PeerDatagram struct {
	From    *net.UDPAddr
	Payload []byte
	At      time.Time
}

type PeerSocket struct {
	uc          *net.UDPConn
	mu          sync.Mutex
	stunWait    map[[12]byte]chan PeerDatagram
	vp02Handler func(*net.UDPAddr, []byte)
}

func NewPeerSocketForTest() *PeerSocket {
	return &PeerSocket{stunWait: make(map[[12]byte]chan PeerDatagram)}
}

func ListenPeerSocket(ctx context.Context, addr string) (*PeerSocket, error) {
	var lc net.ListenConfig
	if p := sockprotect.Protect; p != nil {
		lc.Control = protectControl(p)
	}
	pc, err := lc.ListenPacket(ctx, "udp", addr)
	if err != nil {
		return nil, err
	}
	uc, ok := pc.(*net.UDPConn)
	if !ok {
		_ = pc.Close()
		return nil, errors.New("peer socket: listen not udp")
	}
	s := &PeerSocket{uc: uc, stunWait: make(map[[12]byte]chan PeerDatagram)}
	go func() {
		<-ctx.Done()
		_ = s.Close()
	}()
	go s.readLoop()
	return s, nil
}

func (s *PeerSocket) Addr() net.Addr {
	if s == nil || s.uc == nil {
		return nil
	}
	return s.uc.LocalAddr()
}

func (s *PeerSocket) Close() error {
	if s == nil || s.uc == nil {
		return nil
	}
	return s.uc.Close()
}

func (s *PeerSocket) WriteTo(p []byte, addr *net.UDPAddr) (int, error) {
	if s == nil || s.uc == nil {
		return 0, net.ErrClosed
	}
	return s.uc.WriteToUDP(p, addr)
}

func (s *PeerSocket) RegisterSTUN(tx [12]byte) (<-chan PeerDatagram, func()) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.stunWait == nil {
		s.stunWait = make(map[[12]byte]chan PeerDatagram)
	}
	ch := make(chan PeerDatagram, 1)
	s.stunWait[tx] = ch
	cancel := func() {
		s.mu.Lock()
		delete(s.stunWait, tx)
		s.mu.Unlock()
	}
	return ch, cancel
}

func (s *PeerSocket) SetVP02Handler(fn func(*net.UDPAddr, []byte)) {
	s.mu.Lock()
	s.vp02Handler = fn
	s.mu.Unlock()
}

func (s *PeerSocket) dispatchPacket(from *net.UDPAddr, pkt []byte) {
	if tx, ok := ice.IsBindingResponse(pkt); ok {
		s.mu.Lock()
		ch := s.stunWait[tx]
		if ch != nil {
			delete(s.stunWait, tx)
		}
		s.mu.Unlock()
		if ch != nil {
			ch <- PeerDatagram{From: from, Payload: append([]byte(nil), pkt...), At: time.Now()}
		}
		return
	}
	if len(pkt) >= 4 && string(pkt[:4]) == "VP02" {
		s.mu.Lock()
		fn := s.vp02Handler
		s.mu.Unlock()
		if fn != nil {
			fn(from, append([]byte(nil), pkt...))
		}
	}
}

func (s *PeerSocket) readLoop() {
	buf := make([]byte, 65536)
	for {
		n, addr, err := s.uc.ReadFromUDP(buf)
		if err != nil {
			return
		}
		s.dispatchPacket(addr, buf[:n])
	}
}

func GatherSrflxOnPeerSocket(ctx context.Context, ps *PeerSocket, servers []string) (*ice.SrflxResult, error) {
	if ps == nil {
		return nil, errors.New("peer socket: nil")
	}
	if len(servers) == 0 {
		servers = ice.DefaultSTUNServers
	}
	var lastErr error
	for _, hp := range servers {
		addr, err := net.ResolveUDPAddr("udp", hp)
		if err != nil {
			lastErr = err
			continue
		}
		var tx [12]byte
		if _, err := rand.Read(tx[:]); err != nil {
			return nil, err
		}
		ch, cancel := ps.RegisterSTUN(tx)
		req := buildSTUNBindingRequest(tx)
		t0 := time.Now()
		if _, err := ps.WriteTo(req, addr); err != nil {
			cancel()
			lastErr = err
			continue
		}
		select {
		case <-ctx.Done():
			cancel()
			return nil, ctx.Err()
		case dg := <-ch:
			cancel()
			ip, port, err := ice.ParseBindingResponseForPeerSocket(dg.Payload, tx)
			if err != nil {
				lastErr = err
				continue
			}
			return &ice.SrflxResult{IP: ip, Port: port, RTT: time.Since(t0), Server: hp, Kind: ice.CandidateSrflx}, nil
		}
	}
	if lastErr == nil {
		lastErr = errors.New("peer socket: no stun servers")
	}
	return nil, lastErr
}

func buildSTUNBindingRequest(tx [12]byte) []byte {
	out := make([]byte, 20)
	binary.BigEndian.PutUint16(out[0:2], 0x0001)
	binary.BigEndian.PutUint32(out[4:8], 0x2112A442)
	copy(out[8:20], tx[:])
	return out
}
