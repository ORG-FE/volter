package tunnel

import (
	"bufio"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"syscall"
	"time"

	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/ice"
	"dev.c0redev.volter/internal/peertransport"
	"dev.c0redev.volter/internal/protocol"
	"dev.c0redev.volter/internal/sockprotect"
)

const peerRelayHopHardLimit = 2

type PeerRelayUDPOptions struct {
	Addr          string
	Token         string
	MaxConcurrent int
	StunServers   []string
	OnSrflx       func(string)
}

type PeerUDPRelay struct {
	uc    *net.UDPConn
	token string
	sem   chan struct{}

	mu    sync.Mutex
	peers map[string]*peerUDPState
}

type peerUDPState struct {
	asm  peertransport.Assembler
	sess *peerUDPSession
}

type peerUDPSession struct {
	relay     *PeerUDPRelay
	key       string
	raddr     *net.UDPAddr
	in        chan []byte
	done      chan struct{}
	closeOnce sync.Once
	writeMu   sync.Mutex
	readBuf   []byte
	readOff   int
}

func ListenPeerRelayUDP(ctx context.Context, opt PeerRelayUDPOptions) (*PeerUDPRelay, error) {
	if opt.Addr == "" {
		return nil, errors.New("peer udp: empty listen addr")
	}
	if opt.Token == "" {
		return nil, errors.New("peer udp: empty token")
	}
	maxConcurrent := opt.MaxConcurrent
	if maxConcurrent <= 0 {
		maxConcurrent = 32
	}
	lc := net.ListenConfig{}
	if p := sockprotect.Protect; p != nil {
		lc.Control = func(network, address string, c syscall.RawConn) error {
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
	pc, err := lc.ListenPacket(ctx, "udp", opt.Addr)
	if err != nil {
		return nil, err
	}
	uc, ok := pc.(*net.UDPConn)
	if !ok {
		_ = pc.Close()
		return nil, errors.New("peer udp: listen not udp")
	}
	r := &PeerUDPRelay{
		uc:    uc,
		token: opt.Token,
		sem:   make(chan struct{}, maxConcurrent),
		peers: make(map[string]*peerUDPState),
	}
	if opt.OnSrflx != nil {
		sub, cancel := context.WithTimeout(ctx, 5*time.Second)
		if sr, err := ice.GatherSrflxOnUDP(sub, uc, opt.StunServers); err == nil {
			opt.OnSrflx(net.JoinHostPort(sr.IP.String(), strconv.Itoa(int(sr.Port))))
		}
		cancel()
	}
	go func() {
		<-ctx.Done()
		_ = r.Close()
	}()
	go r.readLoop()
	return r, nil
}

func (r *PeerUDPRelay) Addr() net.Addr {
	if r == nil || r.uc == nil {
		return nil
	}
	return r.uc.LocalAddr()
}

func (r *PeerUDPRelay) Close() error {
	if r == nil || r.uc == nil {
		return nil
	}
	return r.uc.Close()
}

func (r *PeerUDPRelay) readLoop() {
	buf := make([]byte, 65536)
	for {
		n, raddr, err := r.uc.ReadFromUDP(buf)
		if err != nil {
			return
		}
		r.dispatch(raddr, buf[:n])
	}
}

func (r *PeerUDPRelay) dispatch(raddr *net.UDPAddr, pkt []byte) {
	if raddr == nil {
		return
	}
	key := raddr.String()
	r.mu.Lock()
	st := r.peers[key]
	if st == nil {
		st = &peerUDPState{}
		r.peers[key] = st
	}
	msg, ok := st.asm.Feed(pkt)
	if !ok {
		r.mu.Unlock()
		return
	}
	if st.sess == nil {
		select {
		case r.sem <- struct{}{}:
		default:
			delete(r.peers, key)
			r.mu.Unlock()
			return
		}
		addrCopy := *raddr
		s := &peerUDPSession{
			relay:   r,
			key:     key,
			raddr:   &addrCopy,
			in:      make(chan []byte, 64),
			done:    make(chan struct{}),
			readBuf: msg,
		}
		st.sess = s
		r.mu.Unlock()
		go r.handleSession(s)
		return
	}
	closed := false
	select {
	case st.sess.in <- msg:
	default:
		closed = true
	}
	s := st.sess
	r.mu.Unlock()
	if closed {
		_ = s.Close()
	}
}

func (r *PeerUDPRelay) handleSession(s *peerUDPSession) {
	defer s.Close()
	br := bufio.NewReaderSize(s, protocol.BufSizeForConn(0))
	hs, optsJSON, err := protocol.ReadHandshakeAfterSkipWithOpts(br)
	if err != nil || hs.Token != r.token || hs.Role != protocol.RoleRelayTCP() {
		return
	}
	var opts config.ProtectionOptions
	if len(optsJSON) > 0 {
		if err := json.Unmarshal(optsJSON, &opts); err != nil {
			return
		}
	}
	if !allowPeerRelayOptions(&opts, r.token) {
		return
	}
	tc, err := protocol.ReadTcpConnect(br)
	if err != nil {
		return
	}
	target, err := dialPeerRelayTarget(tc.IP, tc.Port)
	if err != nil {
		return
	}
	defer target.Close()

	bucket := NewByteBucket(opts.RelayBudgetKbps)
	errc := make(chan error, 2)
	go func() { errc <- copyRelay(target, br, bucket) }()
	go func() { errc <- copyRelay(s, target, bucket) }()
	<-errc
}

func allowPeerRelayOptions(opts *config.ProtectionOptions, token string) bool {
	if opts == nil || opts.RelayHop <= 0 {
		return false
	}
	maxHop := opts.RelayMaxHop
	if maxHop <= 0 || maxHop > peerRelayHopHardLimit {
		maxHop = peerRelayHopHardLimit
	}
	if opts.RelayHop >= maxHop {
		return false
	}
	if opts.PeerID == "" || opts.RelayNonce == "" || opts.RelaySig == "" {
		return false
	}
	mac := hmac.New(sha256.New, []byte(token))
	_, _ = mac.Write([]byte(opts.PeerID))
	_, _ = mac.Write([]byte("|"))
	_, _ = mac.Write([]byte(opts.RelayNonce))
	want := mac.Sum(nil)
	got, err := base64.RawStdEncoding.DecodeString(opts.RelaySig)
	if err != nil {
		got, err = base64.StdEncoding.DecodeString(opts.RelaySig)
	}
	return err == nil && hmac.Equal(got, want)
}

func dialPeerRelayTarget(ip net.IP, port uint16) (net.Conn, error) {
	if ip == nil || port == 0 {
		return nil, errors.New("peer udp: bad target")
	}
	d := net.Dialer{Timeout: 22 * time.Second, KeepAlive: 30 * time.Second}
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
	return d.Dial("tcp", net.JoinHostPort(ip.String(), strconv.Itoa(int(port))))
}

func copyRelay(dst io.Writer, src io.Reader, bucket *ByteBucket) error {
	buf := make([]byte, protocol.CopyBufSize(0))
	for {
		n, er := src.Read(buf)
		if n > 0 {
			bucket.WaitTake(n)
			if _, ew := dst.Write(buf[:n]); ew != nil {
				return ew
			}
		}
		if er != nil {
			return er
		}
	}
}

func (s *peerUDPSession) Read(p []byte) (int, error) {
	for {
		if s.readOff < len(s.readBuf) {
			n := copy(p, s.readBuf[s.readOff:])
			s.readOff += n
			if s.readOff >= len(s.readBuf) {
				s.readBuf = nil
				s.readOff = 0
			}
			return n, nil
		}
		select {
		case <-s.done:
			return 0, io.EOF
		case msg, ok := <-s.in:
			if !ok {
				return 0, io.EOF
			}
			s.readBuf = msg
			s.readOff = 0
		}
	}
}

func (s *peerUDPSession) Write(p []byte) (int, error) {
	select {
	case <-s.done:
		return 0, io.ErrClosedPipe
	default:
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	if err := peertransport.WriteVP02MessageTo(s.relay.uc, s.raddr, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (s *peerUDPSession) Close() error {
	s.closeOnce.Do(func() {
		close(s.done)
		s.relay.mu.Lock()
		if st := s.relay.peers[s.key]; st != nil && st.sess == s {
			delete(s.relay.peers, s.key)
			close(s.in)
			select {
			case <-s.relay.sem:
			default:
			}
		}
		s.relay.mu.Unlock()
	})
	return nil
}

func (s *peerUDPSession) LocalAddr() net.Addr              { return s.relay.uc.LocalAddr() }
func (s *peerUDPSession) RemoteAddr() net.Addr             { return s.raddr }
func (s *peerUDPSession) SetDeadline(time.Time) error      { return nil }
func (s *peerUDPSession) SetReadDeadline(time.Time) error  { return nil }
func (s *peerUDPSession) SetWriteDeadline(time.Time) error { return nil }

func (r *PeerUDPRelay) String() string {
	if r == nil || r.Addr() == nil {
		return ""
	}
	return fmt.Sprint(r.Addr())
}
