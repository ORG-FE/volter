package tunnel

import (
	"net"
	"strings"
	"sync"
	"time"

	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/dht"
	"dev.c0redev.volter/internal/ice"
)

const (
	PathClassDirect byte = 1
	PathClassServer byte = 2
	PathClassPeer   byte = 3
)

type PathDecision struct {
	PreferTCP         bool
	RelayClass        byte
	PathTTL           byte
	PeerAddr          string
	PeerQUIC          string
	PeerUDP           string
	PeerUDPCandidates []string
}

type PathManager struct {
	mu               sync.Mutex
	paths            map[string]*pathStat
	globalCand       ice.CandidateKind
	srflxRTTEwma     float64
	aggressive       bool
	pathCooldown     time.Duration
	tcpStickUntil    map[string]time.Time
	peerDial         bool
	peerRelayUseQuic bool
	peerRelayUseUDP  bool
	peerUDPResolver  func(string) string
	peerUDPEndpoints func(string) []string
}

type pathStat struct {
	ewmaOK      float64
	failStreak  int
	lastRelay   byte
	lastTTL     byte
	lastAttempt time.Time
}

func NewPathManager() *PathManager {
	return &PathManager{paths: make(map[string]*pathStat)}
}

func NewPathManagerFromRelay(r *config.RelayOptions) *PathManager {
	m := &PathManager{paths: make(map[string]*pathStat)}
	if r != nil {
		m.aggressive = r.PathAggressive
		m.peerDial = r.PeerPathFromDiscovery
		m.peerRelayUseQuic = r.PeerRelayUseQUIC
		m.peerRelayUseUDP = r.PeerRelayUseUDP
		if r.PathCooldownMs > 0 {
			m.pathCooldown = time.Duration(r.PathCooldownMs) * time.Millisecond
			m.tcpStickUntil = make(map[string]time.Time)
		}
	}
	return m
}

func (m *PathManager) SetPeerUDPResolver(fn func(string) string) {
	if m == nil {
		return
	}
	m.mu.Lock()
	m.peerUDPResolver = fn
	m.peerUDPEndpoints = nil
	m.mu.Unlock()
}

func (m *PathManager) SetPeerUDPEndpointsResolver(fn func(string) []string) {
	if m == nil {
		return
	}
	m.mu.Lock()
	m.peerUDPEndpoints = fn
	m.peerUDPResolver = nil
	m.mu.Unlock()
}

func dedupeEndpoints(list []string) []string {
	seen := make(map[string]struct{})
	var out []string
	for _, s := range list {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		k := strings.ToLower(s)
		if _, ok := seen[k]; ok {
			continue
		}
		seen[k] = struct{}{}
		out = append(out, s)
	}
	return out
}

func pickPeerRoutes(epEndpoints func(string) []string, singleUDP func(string) string, useUDP bool) (tcpAddr, quicAddr, udpAddr string, udpEnds []string) {
	resolveUDPEnds := func(id string) []string {
		if epEndpoints != nil {
			return dedupeEndpoints(epEndpoints(id))
		}
		if singleUDP == nil {
			return nil
		}
		if s := strings.TrimSpace(singleUDP(id)); s != "" {
			return []string{s}
		}
		return nil
	}
	nodes := dht.DefaultTable().Nearest(32)
	for _, n := range nodes {
		if !strings.EqualFold(strings.TrimSpace(n.Class), "peer") {
			continue
		}
		var tcp string
		if s := strings.TrimSpace(n.Srflx); s != "" {
			tcp = s
		} else {
			for _, ep := range n.Endpoints {
				ep = strings.TrimSpace(ep)
				if ep != "" {
					tcp = ep
					break
				}
			}
		}
		quic := strings.TrimSpace(n.Quic)
		if useUDP {
			var cand []string
			if tcp != "" {
				cand = append(cand, tcp)
			}
			cand = append(cand, resolveUDPEnds(n.ID)...)
			udpEnds = dedupeEndpoints(cand)
			if len(udpEnds) > 0 {
				udpAddr = udpEnds[0]
			}
		}
		if tcp != "" || quic != "" || udpAddr != "" {
			return tcp, quic, udpAddr, udpEnds
		}
	}
	return "", "", "", nil
}
func (m *PathManager) SetGlobalCandidate(k ice.CandidateKind) {
	if m == nil {
		return
	}
	m.mu.Lock()
	m.globalCand = k
	m.mu.Unlock()
}

func (m *PathManager) SetSrflxRTT(d time.Duration) {
	if m == nil || d <= 0 {
		return
	}
	ms := float64(d.Milliseconds())
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.srflxRTTEwma <= 0 {
		m.srflxRTTEwma = ms
		return
	}
	m.srflxRTTEwma = 0.85*m.srflxRTTEwma + 0.15*ms
}

func (m *PathManager) SrflxRTTEwmaMs() float64 {
	if m == nil {
		return 0
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.srflxRTTEwma
}

func (m *PathManager) srflxLatencyFactor() float64 {
	if m.srflxRTTEwma <= 0 {
		return 1.0
	}
	if m.srflxRTTEwma < 80 {
		return 1.0
	}
	p := 1.0 - (m.srflxRTTEwma-80)/500.0
	if p < 0.72 {
		p = 0.72
	}
	return p
}

func (m *PathManager) Decide(dst net.IP, dual bool, quicEnabled bool, allowPeerPath bool) PathDecision {
	if m == nil {
		return PathDecision{RelayClass: PathClassDirect, PathTTL: 1}
	}
	key := dst.String()
	m.mu.Lock()
	defer m.mu.Unlock()
	st, ok := m.paths[key]
	if !ok {
		st = &pathStat{ewmaOK: 1}
		m.paths[key] = st
	}
	st.lastAttempt = time.Now()

	tryPeer := func() (PathDecision, bool) {
		if !allowPeerPath || !m.peerDial {
			return PathDecision{}, false
		}
		cand := m.globalCand
		if cand != ice.CandidateSrflx && cand != ice.CandidateRelay {
			return PathDecision{}, false
		}
		if st.failStreak < 1 && st.ewmaOK >= 0.42 {
			return PathDecision{}, false
		}
		epSnap := m.peerUDPEndpoints
		resolver := m.peerUDPResolver
		useUDP := m.peerRelayUseUDP
		m.mu.Unlock()
		tcpAddr, quicAddr, udpAddr, udpEnds := pickPeerRoutes(epSnap, resolver, useUDP)
		m.mu.Lock()
		if tcpAddr == "" && quicAddr == "" && udpAddr == "" {
			return PathDecision{}, false
		}
		if !m.peerRelayUseQuic {
			quicAddr = ""
		}
		var peerUDPExtras []string
		if !m.peerRelayUseUDP {
			udpAddr = ""
			udpEnds = nil
		} else {
			peerUDPExtras = udpEnds
		}
		prefTCP := true
		if m.peerRelayUseQuic && quicAddr != "" {
			prefTCP = false
		}
		return PathDecision{PreferTCP: prefTCP, RelayClass: PathClassPeer, PathTTL: 2, PeerAddr: tcpAddr, PeerQUIC: quicAddr, PeerUDP: udpAddr, PeerUDPCandidates: peerUDPExtras}, true
	}

	dec := PathDecision{RelayClass: PathClassDirect, PathTTL: 1}
	if !dual || !quicEnabled {
		dec.PreferTCP = true
		if d, ok := tryPeer(); ok {
			return d
		}
		return dec
	}
	if m.pathCooldown > 0 && m.tcpStickUntil != nil {
		if until, ok := m.tcpStickUntil[key]; ok && time.Now().Before(until) {
			dec.PreferTCP = true
			if d, ok := tryPeer(); ok {
				return d
			}
			return dec
		}
	}
	failThresh := 3
	effThresh := 0.35
	if m.aggressive {
		failThresh = 2
		effThresh = 0.38
	}
	w := 1.0
	if m.globalCand != ice.CandidateUnknown {
		w = m.globalCand.PathWeight()
	}
	effOK := st.ewmaOK * w * m.srflxLatencyFactor()
	if effOK < 0 {
		effOK = 0
	}
	if effOK > 1 {
		effOK = 1
	}
	if st.failStreak >= failThresh || effOK < effThresh {
		if d, ok := tryPeer(); ok {
			return d
		}
		dec.PreferTCP = true
		if m.pathCooldown > 0 && m.tcpStickUntil != nil {
			m.tcpStickUntil[key] = time.Now().Add(m.pathCooldown)
		}
		return dec
	}
	if d, ok := tryPeer(); ok {
		return d
	}
	return dec
}

func (m *PathManager) Record(dst net.IP, ok bool, relayClass byte, pathTTL byte) {
	if m == nil {
		return
	}
	key := dst.String()
	m.mu.Lock()
	defer m.mu.Unlock()
	st, hit := m.paths[key]
	if !hit {
		st = &pathStat{ewmaOK: 1}
		m.paths[key] = st
	}
	if ok {
		st.failStreak = 0
		st.ewmaOK = 0.85*st.ewmaOK + 0.15
		if m.tcpStickUntil != nil {
			delete(m.tcpStickUntil, key)
		}
	} else {
		st.failStreak++
		st.ewmaOK *= 0.8
	}
	if st.ewmaOK > 1 {
		st.ewmaOK = 1
	}
	if st.ewmaOK < 0 {
		st.ewmaOK = 0
	}
	st.lastRelay = relayClass
	st.lastTTL = pathTTL
}
