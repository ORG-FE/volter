package vpn

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"math"
	frand "math/rand/v2"
	"net"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"dev.c0redev.volter/internal/clientlog"
	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/ice"
	"dev.c0redev.volter/internal/obfuscate"
	"dev.c0redev.volter/internal/protocol"
	"dev.c0redev.volter/internal/sockprotect"
	"dev.c0redev.volter/internal/telemetry"
	"dev.c0redev.volter/internal/tunnel"

	core "github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"github.com/xjasonlyu/tun2socks/v2/core/device"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip"
)

func clusterPollHeaderKey(opt Options) string {
	if opt.Protection != nil {
		if k := strings.TrimSpace(opt.Protection.ClusterHTTPKey); k != "" {
			return k
		}
	}
	return strings.TrimSpace(opt.Token)
}

func orderedServerAddrs(addrs []string, prot *config.ProtectionOptions) []string {
	if len(addrs) <= 1 || prot == nil {
		return addrs
	}
	want := strings.TrimSpace(prot.ClusterPreferredServer)
	if want == "" {
		return addrs
	}
	match := -1
	for i, a := range addrs {
		if strings.EqualFold(strings.TrimSpace(a), want) {
			match = i
			break
		}
	}
	if match <= 0 {
		return addrs
	}
	out := make([]string, 0, len(addrs))
	out = append(out, addrs[match])
	out = append(out, addrs[:match]...)
	out = append(out, addrs[match+1:]...)
	return out
}

type Options struct {
	TunFD             int
	MTU               int
	Token             string
	ServerAddrs       []string
	Ready             func()
	Device            device.Device
	CreateDevice      func() (device.Device, func(), error)
	Protection        *config.ProtectionOptions
	Transport         string
	QuicServer        string
	QuicServerName    string
	QuicSkipVerify    bool
	QuicCertPinSHA256 string
	QuicTLSRoots      *x509.CertPool
	QuicTraceLog      bool
	DualTransport     bool
	PathManager       *tunnel.PathManager
	Relay             *config.RelayOptions

	WatchdogInterval          time.Duration
	WatchdogServerPingTimeout time.Duration
	OnWatchdogFail            func()
}

func Run(ctx context.Context, opt Options) error {
	if len(opt.ServerAddrs) == 0 {
		return errors.New("server addrs empty")
	}
	opt.ServerAddrs = orderedServerAddrs(opt.ServerAddrs, opt.Protection)
	ck := clusterPollHeaderKey(opt)
	mapPath, sessPath, clientsPath := clusterPollPaths(opt.Protection)
	for _, addr := range opt.ServerAddrs {
		a := strings.TrimSpace(addr)
		if a == "" {
			continue
		}
		go runClusterMapPoll(ctx, a, ck, mapPath)
		go runClusterSessionsPoll(ctx, a, ck, sessPath)
		go runClusterClientsPoll(ctx, a, ck, clientsPath)
	}
	telemetry.NoteVPNStart()
	readyCb := opt.Ready
	tunnel.SetQUICTrace(opt.QuicTraceLog)
	clientlog.Info("vpn: starting, servers=%v", opt.ServerAddrs)
	clientlog.OK("vpn: volter link %s | %s",
		tunnel.VolterTunnelTag(opt.Transport, opt.QuicServer), volterTunnelCfg(opt.Transport, opt.QuicServer))
	if tunnel.UsesQUICTransport(opt.Transport, opt.QuicServer) {
		if ep, derived, err := tunnel.ResolveQUICDialAddr(opt.ServerAddrs, opt.QuicServer); err == nil {
			if derived {
				clientlog.Info("vpn: QUIC dial %s (host from tcp + udp port %s, set quicServer if different)", ep, tunnel.DefaultQUICPort)
			} else {
				clientlog.Info("vpn: QUIC dial %s", ep)
			}
		}
	}

	udpMux, err := newUDPMux(opt.ServerAddrs, opt.Token, 4, opt.Protection, opt.Transport, opt.QuicServer, opt.QuicServerName, opt.QuicSkipVerify, opt.QuicCertPinSHA256, opt.QuicTLSRoots)
	if err != nil {
		return err
	}

	if emergencyPolicyConfigured(opt.Relay) {
		ectx, ecancel := context.WithTimeout(ctx, 15*time.Second)
		applyEmergencyPolicyOnce(ectx, opt.Relay)
		ecancel()
		go runEmergencyPolicyPoll(ctx, opt.Relay)
	}
	if opt.PathManager != nil && opt.Relay != nil && !emergencyPeerRelayBlocked() {
		r := opt.Relay
		if r.GossipEnabled || r.PeerPathFromDiscovery ||
			r.PeerRelayUseUDP || r.DhtPublishSrflx || r.SymmetricNatHolePunch ||
			len(r.StunServers) > 0 || len(r.TurnURLs) > 0 ||
			len(r.GossipPeers) > 0 || len(r.DHTFindURLs) > 0 ||
			strings.TrimSpace(r.DhtRpcListenUDP) != "" || len(r.DhtRpcSeedPeers) > 0 {
			go probeICEForRelay(opt.PathManager, opt.Relay)
		}
	}
	if opt.Relay != nil && strings.TrimSpace(opt.Relay.BootstrapPubKey) != "" &&
		(strings.TrimSpace(opt.Relay.DiscoveryURL) != "" || strings.TrimSpace(opt.Relay.DiscoverySigned) != "") {
		go runRelayBootstrapVerify(ctx, opt.Relay)
	}
	if opt.Relay != nil && (len(opt.Relay.GossipPeers) > 0 || len(opt.Relay.DHTFindURLs) > 0) {
		go runGossipMesh(ctx, opt.Relay)
	}
	if opt.Relay != nil && strings.TrimSpace(opt.Relay.PeerID) != "" &&
		(len(dhtRPCSeeds(opt.Relay)) > 0 || opt.Relay.PeerPathFromDiscovery || opt.Relay.GossipEnabled || len(opt.Relay.GossipPeers) > 0) {
		go runStoreForwardControlPlane(ctx, opt.Relay)
	}
	if opt.Relay != nil && (strings.TrimSpace(opt.Relay.DhtRpcListenUDP) != "" ||
		len(dhtRPCSeeds(opt.Relay)) > 0 || opt.Relay.PeerPathFromDiscovery || opt.Relay.GossipEnabled || len(opt.Relay.GossipPeers) > 0) {
		go runDhtRpcSidecar(ctx, opt.Relay)
	}
	if opt.Relay != nil && strings.TrimSpace(opt.Relay.PeerRelayUDPListen) != "" {
		go runPeerRelayUDP(ctx, opt.Token, opt.Relay)
	}
	if opt.PathManager != nil && opt.Relay != nil && opt.Relay.PeerRelayUseUDP &&
		(len(dhtRPCSeeds(opt.Relay)) > 0 || opt.Relay.PeerPathFromDiscovery || opt.Relay.GossipEnabled) {
		relay := opt.Relay
		opt.PathManager.SetPeerUDPEndpointsResolver(func(peerID string) []string {
			sub, cancel := context.WithTimeout(ctx, 7*time.Second)
			defer cancel()
			return fetchUdpEndpointsFromDHT(sub, relay, peerID)
		})
	}
	if opt.Relay != nil && opt.Relay.SymmetricNatHolePunch && strings.TrimSpace(opt.Relay.PeerID) != "" &&
		(len(dhtRPCSeeds(opt.Relay)) > 0 || opt.Relay.PeerPathFromDiscovery || opt.Relay.GossipEnabled) {
		go runSymmetricNatHolePunch(ctx, opt.Relay)
	}

	var dev device.Device
	var closeDev func()
	if opt.CreateDevice != nil {
		var err error
		dev, closeDev, err = opt.CreateDevice()
		if err != nil {
			udpMux.Close()
			return err
		}
		defer closeDev()
	} else if opt.Device != nil {
		dev = opt.Device
	} else {
		var err error
		dev, err = fdbased.Open(strconv.Itoa(opt.TunFD), uint32(opt.MTU), 0)
		if err != nil {
			udpMux.Close()
			return err
		}
		defer dev.Close()
	}

	var dualSel *tunnel.DualPathSelector
	if opt.DualTransport && tunnel.UsesQUICTransport(opt.Transport, opt.QuicServer) {
		dualSel = tunnel.NewDualPathSelector()
	}
	h := &handler{
		opt:     opt,
		udpMux:  udpMux,
		dualSel: dualSel,
		pathMgr: opt.PathManager,
	}

	st, err := core.CreateStack(&core.Config{
		LinkEndpoint:     dev,
		TransportHandler: h,
	})
	if err != nil {
		udpMux.Close()
		return err
	}

	defer st.Close()
	defer udpMux.Close()

	clientlog.OK("vpn: netstack ready")
	if readyCb != nil {
		telemetry.NoteSessionReady()
		go func() { _ = telemetry.WriteSLOSnapshotFile() }()
		readyCb()
	}
	if opt.WatchdogInterval > 0 && opt.OnWatchdogFail != nil {
		go runWatchdog(ctx, h, opt)
	}
	<-ctx.Done()
	clientlog.Info("vpn: stopping")
	return nil
}

type udpAssocKey struct {
	SrcPort uint16
	DstIP   string
	DstPort uint16
}

type udpAssoc struct {
	c net.PacketConn
}

type udpMux struct {
	chans     []*udpChan
	assoc     sync.Map
	quicConn  *tunnel.QUICConn
	quicClose func()
}

func (m *udpMux) SharedQUICConn() *tunnel.QUICConn {
	return m.quicConn
}

func newUDPMux(addrs []string, token string, n int, prot *config.ProtectionOptions, transport, quicServer, quicServerName string, quicSkipVerify bool, quicCertPinSHA256 string, quicTLSRoots *x509.CertPool) (*udpMux, error) {
	m := &udpMux{
		chans: make([]*udpChan, n),
	}
	if tunnel.UsesQUICTransport(transport, quicServer) {
		var (
			closeConn  func()
			sharedConn *tunnel.QUICConn
			streams    []tunnel.UDPMuxQUICStream
			err        error
		)
		backoff := 300 * time.Millisecond
		for attempt := 0; attempt < 4; attempt++ {
			closeConn, sharedConn, streams, err = tunnel.DialUDPMuxQUIC(addrs, quicServer, quicServerName, quicSkipVerify, quicCertPinSHA256, quicTLSRoots, n, token, prot)
			if err == nil {
				break
			}
			clientlog.Warn("vpn: QUIC UDP mux attempt %d failed: %v", attempt+1, err)
			if attempt < 3 {
				time.Sleep(backoff)
				if backoff < 2*time.Second {
					backoff *= 2
				}
			}
		}
		if err != nil {
			clientlog.Drop("vpn: QUIC UDP mux failed: %v", err)
			return nil, err
		}
		m.quicClose = closeConn
		m.quicConn = sharedConn
		for i := 0; i < n; i++ {
			s := streams[i]
			uc := &udpChan{
				conn:   s.Conn,
				r:      s.R,
				w:      s.W,
				maxPad: s.MaxPad,
				stop:   make(chan struct{}),
				cb:     m.dispatch,
			}
			if prot != nil && prot.ShapeMaxKbps > 0 {
				uc.shape = tunnel.NewByteBucket(prot.ShapeMaxKbps)
			}
			if prot != nil && prot.ShapeJitterMaxMs > 0 {
				uc.shapeJitterMaxMs = prot.ShapeJitterMaxMs
			}
			if prot != nil && prot.ShapeExpMeanMs > 0 {
				uc.shapeExpMeanMs = prot.ShapeExpMeanMs
			}
			clientlog.Traffic("vpn: udp ch %d  [%s]  %s", i, tunnel.VolterTunnelTag(transport, quicServer), volterTunnelCfg(transport, quicServer))
			go uc.readLoop()
			clientlog.OK("vpn: udp channel %d connected", i)
			m.chans[i] = uc
		}
		return m, nil
	}
	for i := 0; i < n; i++ {
		c, err := newUDPChan(byte(i), addrs, token, m.dispatch, prot, transport, quicServer)
		if err != nil {
			clientlog.Drop("vpn: udp channel %d failed: %v", i, err)
			m.Close()
			return nil, err
		}
		clientlog.OK("vpn: udp channel %d connected", i)
		m.chans[i] = c
	}
	return m, nil
}

func (m *udpMux) Close() {
	for _, c := range m.chans {
		if c != nil {
			_ = c.Close()
		}
	}
	if m.quicClose != nil {
		m.quicClose()
		m.quicClose = nil
	}
	m.quicConn = nil
}

func (m *udpMux) register(k udpAssocKey, a *udpAssoc) {
	m.assoc.Store(k, a)
}

func (m *udpMux) unregister(k udpAssocKey) {
	m.assoc.Delete(k)
}

func (m *udpMux) pick(k udpAssocKey) *udpChan {
	h := sha256.Sum256([]byte(fmt.Sprintf("%d|%s|%d", k.SrcPort, k.DstIP, k.DstPort)))
	idx := int(h[0]) % len(m.chans)
	return m.chans[idx]
}

func (m *udpMux) send(k udpAssocKey, payload []byte) error {
	ch := m.pick(k)
	ip := net.ParseIP(k.DstIP)
	f := protocol.UDPFrame{SrcPort: k.SrcPort, DstIP: ip, DstPort: k.DstPort, Payload: payload}
	return ch.Send(f)
}

func (m *udpMux) dispatch(f protocol.UDPFrame) {
	k := udpAssocKey{SrcPort: f.SrcPort, DstIP: f.DstIP.String(), DstPort: f.DstPort}
	v, ok := m.assoc.Load(k)
	if !ok {
		clientlog.Warn("vpn: udp dispatch no assoc for %d->%s:%d", f.SrcPort, f.DstIP.String(), f.DstPort)
		return
	}
	a := v.(*udpAssoc)
	if _, err := a.c.WriteTo(f.Payload, nil); err != nil {
		clientlog.Drop("vpn: udp dispatch write error: %v", err)
	}
}

type udpChan struct {
	conn             net.Conn
	r                *bufio.Reader
	w                *bufio.Writer
	maxPad           int
	burstSmoothMaxMs int
	shapeJitterMaxMs int
	shapeExpMeanMs   int
	shape            *tunnel.ByteBucket
	mu               sync.Mutex
	stopOnce         sync.Once
	stop             chan struct{}
	cb               func(protocol.UDPFrame)
}

func newUDPChan(id byte, addrs []string, token string, cb func(protocol.UDPFrame), prot *config.ProtectionOptions, transport, quicServer string) (*udpChan, error) {
	var last error
	start := int(id) % len(addrs)
	backoff := 250 * time.Millisecond
	for round := 0; round < 3; round++ {
		for i := 0; i < len(addrs); i++ {
			a := addrs[(start+i)%len(addrs)]
			c, err := dialTCP(a, token)
			if err != nil {
				last = err
				continue
			}
			slot := tunnel.SlotForProtection(prot)
			bufSize := protocol.BufSizeForConn(slot)
			uc := &udpChan{
				conn: c,
				r:    bufio.NewReaderSize(c, bufSize),
				w:    bufio.NewWriterSize(c, bufSize),
				stop: make(chan struct{}),
				cb:   cb,
			}
			maxPad, err := tunnel.WriteUDPChannelPreambleSlot(uc.w, id, token, prot, slot)
			if err != nil {
				_ = c.Close()
				last = err
				continue
			}
			uc.maxPad = maxPad
			if prot != nil && prot.BurstSmoothingMaxMs > 0 {
				uc.burstSmoothMaxMs = prot.BurstSmoothingMaxMs
			}
			if prot != nil && prot.ShapeMaxKbps > 0 {
				uc.shape = tunnel.NewByteBucket(prot.ShapeMaxKbps)
			}
			if prot != nil && prot.ShapeJitterMaxMs > 0 {
				uc.shapeJitterMaxMs = prot.ShapeJitterMaxMs
			}
			if prot != nil && prot.ShapeExpMeanMs > 0 {
				uc.shapeExpMeanMs = prot.ShapeExpMeanMs
			}
			clientlog.Traffic("vpn: udp ch %d  [%s]  server=%s  %s", id, tunnel.VolterTunnelTag(transport, quicServer), a, volterTunnelCfg(transport, quicServer))
			go uc.readLoop()
			return uc, nil
		}
		if round < 2 {
			time.Sleep(backoff)
			if backoff < 1200*time.Millisecond {
				backoff *= 2
			}
		}
	}
	if last == nil {
		last = errors.New("dial failed")
	}
	return nil, last
}

func (c *udpChan) Close() error {
	c.stopOnce.Do(func() { close(c.stop) })
	return c.conn.Close()
}

func (c *udpChan) Send(f protocol.UDPFrame) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.shape != nil {
		c.shape.WaitTake(len(f.Payload) + 96)
	}
	if c.shapeJitterMaxMs > 0 {
		time.Sleep(time.Duration(frand.IntN(c.shapeJitterMaxMs+1)) * time.Millisecond)
	}
	if c.shapeExpMeanMs > 0 {
		u := frand.Float64()
		if u < 1e-12 {
			u = 1e-12
		}
		ms := -math.Log(u) * float64(c.shapeExpMeanMs)
		cap := float64(c.shapeExpMeanMs * 30)
		if ms > cap {
			ms = cap
		}
		time.Sleep(time.Duration(ms * float64(time.Millisecond)))
	}
	if c.burstSmoothMaxMs > 0 {
		time.Sleep(time.Duration(frand.IntN(c.burstSmoothMaxMs+1)) * time.Millisecond)
	}
	return protocol.WriteUDPFrameWithPad(c.w, f, c.maxPad)
}

func (c *udpChan) readLoop() {
	for {
		select {
		case <-c.stop:
			return
		default:
		}
		f, err := protocol.ReadUDPFrame(c.r)
		if err != nil {
			clientlog.Drop("vpn: udp channel read failed: %v", err)
			return
		}
		c.cb(f)
	}
}

type handler struct {
	opt     Options
	udpMux  *udpMux
	dualSel *tunnel.DualPathSelector
	pathMgr *tunnel.PathManager
}

func (h *handler) HandleTCP(c adapter.TCPConn) {
	go h.handleTCP(c)
}

func (h *handler) HandleUDP(c adapter.UDPConn) {
	go h.handleUDP(c)
}

func (h *handler) handleUDP(uc adapter.UDPConn) {
	defer uc.Close()

	id := uc.ID()
	srcPort := uint16(id.RemotePort)
	dstIP := tcpipToIP(id.LocalAddress)
	dstPort := uint16(id.LocalPort)
	clientlog.Traffic("vpn: udp assoc %d -> %s:%d  [%s]  %s", srcPort, dstIP.String(), dstPort, tunnel.VolterTunnelTag(h.opt.Transport, h.opt.QuicServer), volterTunnelCfg(h.opt.Transport, h.opt.QuicServer))

	k := udpAssocKey{SrcPort: srcPort, DstIP: dstIP.String(), DstPort: dstPort}
	kAlt := udpAssocKey{SrcPort: dstPort, DstIP: dstIP.String(), DstPort: srcPort}
	a := &udpAssoc{c: uc}
	h.udpMux.register(k, a)
	if kAlt != k {
		h.udpMux.register(kAlt, a)
	}
	defer h.udpMux.unregister(k)
	if kAlt != k {
		defer h.udpMux.unregister(kAlt)
	}

	buf := make([]byte, 64*1024)
	for {
		n, _, err := uc.ReadFrom(buf)
		if err != nil {
			clientlog.Drop("vpn: udp read failed %d->%s:%d: %v", srcPort, dstIP.String(), dstPort, err)
			return
		}
		if err := h.udpMux.send(k, buf[:n]); err != nil {
			clientlog.Drop("vpn: udp send failed %d->%s:%d: %v", srcPort, dstIP.String(), dstPort, err)
			return
		}
	}
}

func (h *handler) handleTCP(tc adapter.TCPConn) {
	defer tc.Close()

	id := tc.ID()
	dstIP := tcpipToIP(id.LocalAddress)
	dstPort := uint16(id.LocalPort)

	if h.isServerAddr(dstIP, dstPort) {
		return
	}

	shared := h.udpMux.SharedQUICConn()
	tag := tunnel.VolterTunnelTag(h.opt.Transport, h.opt.QuicServer)
	if h.opt.DualTransport && shared != nil {
		tag = "QUIC"
	}
	clientlog.Traffic("vpn: tun-tcp %s:%d  [%s]  %s", dstIP.String(), dstPort, tag, volterTunnelCfg(h.opt.Transport, h.opt.QuicServer))

	var sconn net.Conn
	var r *bufio.Reader
	slot := tunnel.SlotForProtection(h.opt.Protection)
	var err error
	var fellBackTCP, tcpOnly bool
	allowPeerPath := h.opt.Relay != nil && h.opt.Relay.PeerPathFromDiscovery && !emergencyPeerRelayBlocked()
	switch strings.ToLower(strings.TrimSpace(h.opt.Protection.RouteMode)) {
	case "direct":
		allowPeerPath = false
	case "server_relay":
		allowPeerPath = false
	case "peer_relay":
		allowPeerPath = true
	}
	sconn, fellBackTCP, tcpOnly, err = tunnel.DialTunFlow(h.opt.ServerAddrs, dstIP, dstPort, h.opt.Token, h.opt.Protection, h.opt.Transport, h.opt.QuicServer, h.opt.QuicServerName, h.opt.QuicSkipVerify, h.opt.QuicCertPinSHA256, h.opt.QuicTLSRoots, shared, h.opt.DualTransport, h.dualSel, h.pathMgr, allowPeerPath, h.opt.Relay)
	if h.opt.DualTransport && shared != nil {
		if fellBackTCP || tcpOnly {
			tag = "TCP"
		} else {
			tag = "QUIC"
		}
	} else if fellBackTCP {
		tag = "TCP"
	}
	if err != nil {
		clientlog.DPI("vpn: tcp connect frame failed: %v", err)
		return
	}
	r = bufio.NewReaderSize(sconn, protocol.BufSizeForConn(slot))
	defer sconn.Close()

	deadline := time.Now().Add(30 * time.Minute)
	_ = tc.SetReadDeadline(deadline)
	_ = tc.SetWriteDeadline(deadline)
	_ = sconn.SetReadDeadline(deadline)
	_ = sconn.SetWriteDeadline(deadline)

	copyBufSize := protocol.CopyBufSize(slot)
	done := make(chan struct{}, 2)
	go func() {
		buf := make([]byte, copyBufSize)
		_, _ = io.CopyBuffer(sconn, tc, buf)
		done <- struct{}{}
	}()
	go func() {
		buf := make([]byte, copyBufSize)
		_, _ = io.CopyBuffer(tc, r, buf)
		done <- struct{}{}
	}()
	<-done
	_ = tc.Close()
	_ = sconn.Close()
	<-done
	clientlog.Traffic("vpn: tun-tcp closed %s:%d  [%s]", dstIP.String(), dstPort, tag)
}

func volterTunnelCfg(transport, quicServer string) string {
	t := strings.TrimSpace(transport)
	qs := strings.TrimSpace(quicServer)
	if t == "" {
		if qs != "" {
			return fmt.Sprintf("transport=implicit-quic quicServer=%q", qs)
		}
		return "transport=empty"
	}
	if qs != "" {
		return fmt.Sprintf("transport=%s quicServer=%q", t, qs)
	}
	return fmt.Sprintf("transport=%s", t)
}

func dialTCP(addr string, token string) (net.Conn, error) {
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
	c, err := d.Dial("tcp", addr)
	if err != nil {
		return nil, err
	}
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	return obfuscate.WrapConn(c, token), nil
}

func (h *handler) isServerAddr(dstIP net.IP, dstPort uint16) bool {
	for _, a := range h.opt.ServerAddrs {
		host, port, err := net.SplitHostPort(a)
		if err != nil || port != strconv.Itoa(int(dstPort)) {
			continue
		}
		ip := net.ParseIP(host)
		if ip != nil && ip.Equal(dstIP) {
			return true
		}
	}
	return false
}

func pickAddr(addrs []string, ip net.IP, port uint16) string {
	if len(addrs) == 1 {
		return addrs[0]
	}
	h := sha256.Sum256([]byte(ip.String() + ":" + fmt.Sprintf("%d", port)))
	return addrs[int(h[0])%len(addrs)]
}

func tcpipToIP(a tcpip.Address) net.IP {
	b := append([]byte(nil), a.AsSlice()...)
	return net.IP(b)
}

func runPeerRelayUDP(ctx context.Context, token string, relay *config.RelayOptions) {
	if relay == nil {
		return
	}
	listen := strings.TrimSpace(relay.PeerRelayUDPListen)
	if listen == "" {
		return
	}
	explicitAdvertise := strings.TrimSpace(relay.PeerRelayUDPAdvertise)
	advertise := explicitAdvertise
	if advertise == "" {
		advertise = listen
	}
	pr, err := tunnel.ListenPeerRelayUDP(ctx, tunnel.PeerRelayUDPOptions{
		Addr:          listen,
		Token:         token,
		MaxConcurrent: relay.MaxConcurrent,
		StunServers:   relay.StunServers,
		OnSrflx: func(s string) {
			if s != "" {
				publishSrflxToDHT(ctx, relay, s)
			}
		},
	})
	if err != nil {
		clientlog.Warn("vpn: peer udp listen %s: %v", listen, err)
		return
	}
	defer func() { _ = pr.Close() }()
	clientlog.OK("vpn: peer udp relay %s", pr.String())
	if explicitAdvertise != "" {
		publishSrflxToDHT(ctx, relay, advertise)
	}
	<-ctx.Done()
}

func probeICEForRelay(pm *tunnel.PathManager, relay *config.RelayOptions) {
	if pm == nil || relay == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()

	needStun := relay.GossipEnabled || len(relay.StunServers) > 0 || relay.PeerPathFromDiscovery ||
		relay.PeerRelayUseUDP || relay.DhtPublishSrflx || relay.SymmetricNatHolePunch ||
		len(relay.GossipPeers) > 0 || len(relay.DHTFindURLs) > 0 ||
		strings.TrimSpace(relay.DhtRpcListenUDP) != "" || len(relay.DhtRpcSeedPeers) > 0
	if needStun {
		r, err := ice.GatherSrflx(ctx, relay.StunServers)
		if err != nil {
			clientlog.Warn("vpn: STUN gather failed: %v", err)
		} else {
			pm.SetSrflxRTT(r.RTT)
			if locals, err := ice.InterfaceIPs(); err == nil && ice.IPOnLocalMachine(r.IP, locals) {
				pm.SetGlobalCandidate(ice.CandidateHost)
			} else {
				pm.SetGlobalCandidate(ice.CandidateSrflx)
			}
			note := fmt.Sprintf("srflx %s:%d rtt=%v via %s", r.IP.String(), r.Port, r.RTT, r.Server)
			telemetry.RecordPath(telemetry.SwitchICE, note)
			clientlog.Info("vpn: STUN srflx %s:%d rtt=%v server=%s", r.IP, r.Port, r.RTT, r.Server)
			hp := fmt.Sprintf("%s:%d", r.IP.String(), r.Port)
			setLastClientSrflx(hp)
			if strings.TrimSpace(relay.PeerRelayUDPListen) == "" {
				publishSrflxToDHT(ctx, relay, hp)
			}
		}
	}

	for _, u := range relay.TurnURLs {
		u = strings.TrimSpace(u)
		if u == "" {
			continue
		}
		tr, err := ice.TryTurnAllocate(ctx, u)
		if err != nil {
			clientlog.Warn("vpn: TURN %s: %v", u, err)
			continue
		}
		pm.SetGlobalCandidate(ice.CandidateRelay)
		pm.SetSrflxRTT(tr.RTT)
		telemetry.RecordPath(telemetry.SwitchICE, fmt.Sprintf("turn relay %s rtt=%v", tr.Relayed.String(), tr.RTT))
		clientlog.Info("vpn: TURN relay %s rtt=%v server=%s", tr.Relayed.String(), tr.RTT, tr.Server)
		break
	}
	telemetry.SetIceSrflxRttEwmaMs(pm.SrflxRTTEwmaMs())
}
