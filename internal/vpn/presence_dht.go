package vpn

import (
	"context"
	crand "crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"net"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/dht"
	"dev.c0redev.volter/internal/ice"
)

var lastPublishedSrflx atomic.Value

func setLastClientSrflx(s string) {
	s = strings.TrimSpace(s)
	if s == "" {
		return
	}
	lastPublishedSrflx.Store(s)
}

func getLastClientSrflx() string {
	v := lastPublishedSrflx.Load()
	if v == nil {
		return ""
	}
	return v.(string)
}

func LastClientSrflx() string { return getLastClientSrflx() }

type dhtPresenceRecord struct {
	Srflx      string   `json:"srflx"`
	Candidates []string `json:"candidates,omitempty"`
	Epoch      int64    `json:"epoch,omitempty"`
}

func mergePresenceEndpoints(primary string, extra []string) []string {
	seen := make(map[string]struct{})
	var out []string
	add := func(s string) {
		s = strings.TrimSpace(s)
		if s == "" {
			return
		}
		k := strings.ToLower(s)
		if _, ok := seen[k]; ok {
			return
		}
		seen[k] = struct{}{}
		out = append(out, s)
	}
	add(primary)
	for _, e := range extra {
		add(e)
	}
	return out
}

func marshalPresence(primary string, extra []string) ([]byte, error) {
	cands := mergePresenceEndpoints(primary, extra)
	if len(cands) == 0 {
		return nil, nil
	}
	rec := dhtPresenceRecord{
		Srflx:      cands[0],
		Candidates: append([]string(nil), cands...),
		Epoch:      time.Now().Unix(),
	}
	return json.Marshal(rec)
}

func presenceDHTStore(ctx context.Context, relay *config.RelayOptions, val []byte) {
	seeds := dhtRPCSeeds(relay)
	if relay == nil || strings.TrimSpace(relay.PeerID) == "" || len(seeds) == 0 || len(val) == 0 {
		return
	}
	key := sha256.Sum256([]byte(strings.TrimSpace(relay.PeerID)))
	secret := relay.DhtRpcSecret
	for _, seed := range seeds {
		seed = strings.TrimSpace(seed)
		if seed == "" {
			continue
		}
		sub, cancel := context.WithTimeout(ctx, 6*time.Second)
		_, _ = dht.UDPStore(sub, seed, secret, key, 900, val)
		cancel()
	}
}

func publishMergedPresenceCandidates(ctx context.Context, relay *config.RelayOptions, primary string, extras []string) {
	if relay == nil {
		return
	}
	if !relay.DhtPublishSrflx && !relay.SymmetricNatHolePunch {
		return
	}
	b, err := marshalPresence(primary, extras)
	if err != nil || len(b) == 0 {
		return
	}
	presenceDHTStore(ctx, relay, b)
}

func publishSrflxToDHT(ctx context.Context, relay *config.RelayOptions, hostPort string) {
	seeds := dhtRPCSeeds(relay)
	if relay == nil || !relay.DhtPublishSrflx ||
		len(seeds) == 0 || strings.TrimSpace(relay.PeerID) == "" ||
		strings.TrimSpace(hostPort) == "" {
		return
	}
	hostPort = strings.TrimSpace(hostPort)
	setLastClientSrflx(hostPort)
	publishMergedPresenceCandidates(ctx, relay, hostPort, nil)
}

func endpointsFromPresenceJSON(raw []byte) []string {
	var rec dhtPresenceRecord
	if err := json.Unmarshal(raw, &rec); err != nil {
		var legacy struct {
			Srflx string `json:"srflx"`
		}
		if json.Unmarshal(raw, &legacy) != nil || strings.TrimSpace(legacy.Srflx) == "" {
			return nil
		}
		return []string{strings.TrimSpace(legacy.Srflx)}
	}
	return mergePresenceEndpoints(rec.Srflx, rec.Candidates)
}

func fetchUdpEndpointsFromDHT(ctx context.Context, relay *config.RelayOptions, peerID string) []string {
	seeds := dhtRPCSeeds(relay)
	if relay == nil || strings.TrimSpace(peerID) == "" || len(seeds) == 0 {
		return nil
	}
	key := sha256.Sum256([]byte(strings.TrimSpace(peerID)))
	secret := relay.DhtRpcSecret
	for _, seed := range seeds {
		seed = strings.TrimSpace(seed)
		if seed == "" {
			continue
		}
		sub, cancel := context.WithTimeout(ctx, 7*time.Second)
		val, ok, err := dht.UDPGet(sub, seed, secret, key)
		cancel()
		if err != nil || !ok {
			continue
		}
		if ends := endpointsFromPresenceJSON(val); len(ends) > 0 {
			return ends
		}
	}
	return nil
}

func punchBurst(uc *net.UDPConn, hostPort string) {
	hostPort = strings.TrimSpace(hostPort)
	if uc == nil || hostPort == "" {
		return
	}
	raddr, err := net.ResolveUDPAddr("udp", hostPort)
	if err != nil {
		return
	}
	var buf [32]byte
	for i := 0; i < 8; i++ {
		_, _ = crand.Read(buf[:])
		_, _ = uc.WriteToUDP(buf[:], raddr)
		time.Sleep(18 * time.Millisecond)
	}
}

func runSymmetricNatHolePunch(ctx context.Context, relay *config.RelayOptions) {
	if relay == nil || !relay.SymmetricNatHolePunch || len(dhtRPCSeeds(relay)) == 0 ||
		strings.TrimSpace(relay.PeerID) == "" {
		return
	}
	round := func() {
		sub, cancel := context.WithTimeout(ctx, 14*time.Second)
		defer cancel()
		uc, err := net.ListenUDP("udp", &net.UDPAddr{IP: nil, Port: 0})
		if err != nil {
			return
		}
		defer func() { _ = uc.Close() }()
		r, err := ice.GatherSrflxOnUDP(sub, uc, relay.StunServers)
		if err != nil {
			return
		}
		ephemeral := net.JoinHostPort(r.IP.String(), strconv.Itoa(int(r.Port)))
		base := getLastClientSrflx()
		publishMergedPresenceCandidates(sub, relay, base, []string{ephemeral})
		for _, n := range dht.DefaultTable().Nearest(16) {
			if !strings.EqualFold(strings.TrimSpace(n.Class), "peer") {
				continue
			}
			pid := strings.TrimSpace(n.ID)
			if pid == "" {
				continue
			}
			for _, hp := range fetchUdpEndpointsFromDHT(sub, relay, pid) {
				punchBurst(uc, hp)
			}
		}
	}
	delay := time.NewTimer(4 * time.Second)
	select {
	case <-ctx.Done():
		delay.Stop()
		return
	case <-delay.C:
	}
	round()
	ticker := time.NewTicker(22 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			round()
		}
	}
}
