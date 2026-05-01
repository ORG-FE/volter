package vpn

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/dht"
)

type controlPlaneMessage struct {
	ID        string `json:"id"`
	From      string `json:"from"`
	Type      string `json:"type"`
	Body      string `json:"body"`
	CreatedAt int64  `json:"createdAt"`
	TTL       int64  `json:"ttl"`
}

type controlPlaneQueue struct {
	mu      sync.Mutex
	pending []controlPlaneMessage
	seen    map[string]int64
}

type storeForwardStats struct {
	Sent     uint64
	Received uint64
}

var cpStats atomic.Value

func StoreForwardStats() storeForwardStats {
	v := cpStats.Load()
	if v == nil {
		return storeForwardStats{}
	}
	return v.(storeForwardStats)
}

func runStoreForwardControlPlane(ctx context.Context, relay *config.RelayOptions) {
	if relay == nil || strings.TrimSpace(relay.PeerID) == "" {
		return
	}
	q := &controlPlaneQueue{seen: make(map[string]int64)}
	enqueue := func(tp, body string, ttl time.Duration) {
		idRaw := sha256.Sum256([]byte(tp + "|" + body + "|" + strconvI64(time.Now().UnixMilli())))
		msg := controlPlaneMessage{
			ID:        hex16(idRaw[:]),
			From:      strings.TrimSpace(relay.PeerID),
			Type:      tp,
			Body:      body,
			CreatedAt: time.Now().UnixMilli(),
			TTL:       ttl.Milliseconds(),
		}
		q.mu.Lock()
		q.pending = append(q.pending, msg)
		q.mu.Unlock()
	}
	enqueue("hello", "boot", 30*time.Second)

	flushTick := time.NewTicker(4 * time.Second)
	pullTick := time.NewTicker(6 * time.Second)
	defer flushTick.Stop()
	defer pullTick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-flushTick.C:
			enqueue("heartbeat", "alive", 30*time.Second)
			flushControlQueue(ctx, relay, q)
		case <-pullTick.C:
			pullControlInbox(ctx, relay, q)
		}
	}
}

func flushControlQueue(ctx context.Context, relay *config.RelayOptions, q *controlPlaneQueue) {
	q.mu.Lock()
	msgs := append([]controlPlaneMessage(nil), q.pending...)
	q.pending = q.pending[:0]
	q.mu.Unlock()
	if len(msgs) == 0 {
		return
	}
	now := time.Now().UnixMilli()
	for _, m := range msgs {
		if m.CreatedAt+m.TTL <= now {
			continue
		}
		b, err := json.Marshal(m)
		if err != nil {
			continue
		}
		key := sha256.Sum256([]byte("cp:" + strings.TrimSpace(relay.PeerID) + ":" + m.ID))
		headKey := sha256.Sum256([]byte("cp:" + strings.TrimSpace(relay.PeerID)))
		for _, seed := range dhtRPCSeeds(relay) {
			seed = strings.TrimSpace(seed)
			if seed == "" {
				continue
			}
			sub, cancel := context.WithTimeout(ctx, 4*time.Second)
			ok, _ := dht.UDPStore(sub, seed, relay.DhtRpcSecret, key, uint32(max(5, int(m.TTL/1000))), b)
			cancel()
			if ok {
				incrSent()
			}
			subHead, cancelHead := context.WithTimeout(ctx, 4*time.Second)
			_, _ = dht.UDPStore(subHead, seed, relay.DhtRpcSecret, headKey, uint32(max(5, int(m.TTL/1000))), b)
			cancelHead()
		}
	}
}

func pullControlInbox(ctx context.Context, relay *config.RelayOptions, q *controlPlaneQueue) {
	nearest := dht.DefaultTable().Nearest(8)
	for _, n := range nearest {
		peerID := strings.TrimSpace(n.ID)
		if peerID == "" || strings.EqualFold(peerID, relay.PeerID) {
			continue
		}
		key := sha256.Sum256([]byte("cp:" + peerID))
		for _, seed := range dhtRPCSeeds(relay) {
			seed = strings.TrimSpace(seed)
			if seed == "" {
				continue
			}
			sub, cancel := context.WithTimeout(ctx, 4*time.Second)
			val, ok, _ := dht.UDPGet(sub, seed, relay.DhtRpcSecret, key)
			cancel()
			if !ok || len(val) == 0 {
				continue
			}
			var m controlPlaneMessage
			if json.Unmarshal(val, &m) != nil {
				continue
			}
			if m.ID == "" {
				continue
			}
			q.mu.Lock()
			if _, hit := q.seen[m.ID]; !hit {
				q.seen[m.ID] = time.Now().UnixMilli()
				incrReceived()
			}
			q.mu.Unlock()
		}
	}
}

func incrSent() {
	cur := StoreForwardStats()
	cur.Sent++
	cpStats.Store(cur)
}

func incrReceived() {
	cur := StoreForwardStats()
	cur.Received++
	cpStats.Store(cur)
}

func hex16(b []byte) string {
	const d = "0123456789abcdef"
	out := make([]byte, 16)
	for i := 0; i < 8 && i < len(b); i++ {
		out[i*2] = d[(b[i]>>4)&0x0f]
		out[i*2+1] = d[b[i]&0x0f]
	}
	return string(out)
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func strconvI64(v int64) string {
	return strconv.FormatInt(v, 10)
}
