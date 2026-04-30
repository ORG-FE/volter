package dht

import (
	"crypto/sha256"
	"sort"
	"strings"
	"sync"

	"dev.c0redev.volter/internal/discovery"
)

const maxEntries = 2048

type Table struct {
	mu      sync.RWMutex
	self    [32]byte
	byID    map[string]relayEntry
	buckets [256][]string
}

type relayEntry struct {
	id [32]byte
	n  discovery.RelayNode
}

func NewTable(seed string) *Table {
	h := sha256.Sum256([]byte(seed))
	return &Table{
		self: h,
		byID: make(map[string]relayEntry),
	}
}

func nodeID(r discovery.RelayNode) [32]byte {
	return sha256.Sum256([]byte(r.ID))
}

func xorDist(a, b [32]byte) (d [32]byte) {
	for i := range d {
		d[i] = a[i] ^ b[i]
	}
	return d
}

func distLess(x, y [32]byte) bool {
	for i := range x {
		if x[i] != y[i] {
			return x[i] < y[i]
		}
	}
	return false
}

func (t *Table) SelfID() [32]byte {
	return t.self
}

func (t *Table) Insert(r discovery.RelayNode) {
	if r.ID == "" {
		return
	}
	hasEP := false
	for _, e := range r.Endpoints {
		if strings.TrimSpace(e) != "" {
			hasEP = true
			break
		}
	}
	if !hasEP && strings.TrimSpace(r.Quic) == "" && strings.TrimSpace(r.Srflx) == "" &&
		strings.TrimSpace(r.DhtRPC) == "" {
		return
	}
	id := nodeID(r)
	t.mu.Lock()
	defer t.mu.Unlock()
	if _, ok := t.byID[r.ID]; ok {
		t.byID[r.ID] = relayEntry{id: id, n: r}
		return
	}

	xd := xorDist(t.self, id)
	bi := xorLeadingBitIdx(xd)
	t.byID[r.ID] = relayEntry{id: id, n: r}
	t.placeInBucketLocked(byte(bi), r.ID)
	t.trimBucketLocked(bi)
	for len(t.byID) > maxEntries {
		t.evictOneFarthestLocked()
	}
}

func (t *Table) placeInBucketLocked(bi byte, id string) {
	b := int(bi)
	t.buckets[b] = append(t.buckets[b], id)
}

func (t *Table) trimBucketLocked(bi int) {
	for len(t.buckets[bi]) > bucketK {
		worst := -1
		var worstD [32]byte
		for i, rid := range t.buckets[bi] {
			e := t.byID[rid]
			d := xorDist(t.self, e.id)
			if worst < 0 || distLess(worstD, d) {
				worst = i
				worstD = d
			}
		}
		if worst < 0 {
			return
		}
		victim := t.buckets[bi][worst]
		t.dropContactLocked(victim)
	}
}

func (t *Table) dropContactLocked(id string) {
	e, ok := t.byID[id]
	if !ok {
		return
	}
	bi := xorLeadingBitIdx(xorDist(t.self, e.id))
	s := t.buckets[bi]
	for i := range s {
		if s[i] == id {
			t.buckets[bi] = append(s[:i], s[i+1:]...)
			break
		}
	}
	delete(t.byID, id)
}

func (t *Table) Merge(relays []discovery.RelayNode) {
	for _, r := range relays {
		t.Insert(r)
	}
}

func (t *Table) evictOneFarthestLocked() {
	var farthest string
	var farthestD [32]byte
	first := true
	for id, e := range t.byID {
		d := xorDist(e.id, t.self)
		if first || distLess(farthestD, d) {
			first = false
			farthest = id
			farthestD = d
		}
	}
	if farthest == "" {
		return
	}
	t.dropContactLocked(farthest)
}

func (t *Table) Nearest(k int) []discovery.RelayNode {
	if k <= 0 {
		return nil
	}
	t.mu.RLock()
	defer t.mu.RUnlock()
	type pair struct {
		d [32]byte
		n discovery.RelayNode
	}
	pp := make([]pair, 0, len(t.byID))
	for _, e := range t.byID {
		pp = append(pp, pair{d: xorDist(e.id, t.self), n: e.n})
	}
	sort.Slice(pp, func(i, j int) bool {
		return distLess(pp[i].d, pp[j].d)
	})
	if k > len(pp) {
		k = len(pp)
	}
	out := make([]discovery.RelayNode, k)
	for i := 0; i < k; i++ {
		out[i] = pp[i].n
	}
	return out
}

func (t *Table) NearestTo(target [32]byte, k int) []discovery.RelayNode {
	if k <= 0 {
		return nil
	}
	t.mu.RLock()
	defer t.mu.RUnlock()
	type pair struct {
		d [32]byte
		n discovery.RelayNode
	}
	pp := make([]pair, 0, len(t.byID))
	for _, e := range t.byID {
		pp = append(pp, pair{d: xorDist(e.id, target), n: e.n})
	}
	sort.Slice(pp, func(i, j int) bool {
		return distLess(pp[i].d, pp[j].d)
	})
	if k > len(pp) {
		k = len(pp)
	}
	out := make([]discovery.RelayNode, k)
	for i := 0; i < k; i++ {
		out[i] = pp[i].n
	}
	return out
}

func (t *Table) Len() int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return len(t.byID)
}

var defaultTable = NewTable("volter-dht-default")

func DefaultTable() *Table { return defaultTable }
