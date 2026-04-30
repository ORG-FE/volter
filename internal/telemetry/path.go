package telemetry

import (
	"sync"
	"time"
)

type PathSwitchKind string

const (
	SwitchTransport PathSwitchKind = "transport"
	SwitchICE       PathSwitchKind = "ice"
	SwitchRelay     PathSwitchKind = "relay"
	SwitchWatchdog  PathSwitchKind = "watchdog"
)

type PathEvent struct {
	Ts   time.Time
	Kind PathSwitchKind
	Note string
}

const pathRingCap = 64

type PathRing struct {
	mu   sync.Mutex
	buf  [pathRingCap]PathEvent
	n    int
	head int
}

func (r *PathRing) Add(kind PathSwitchKind, note string) {
	if r == nil {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.buf[r.head] = PathEvent{Ts: time.Now(), Kind: kind, Note: note}
	r.head = (r.head + 1) % pathRingCap
	if r.n < pathRingCap {
		r.n++
	}
}

func (r *PathRing) Snapshot() []PathEvent {
	if r == nil {
		return nil
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.n == 0 {
		return nil
	}
	out := make([]PathEvent, 0, r.n)
	start := (r.head - r.n + pathRingCap) % pathRingCap
	for i := 0; i < r.n; i++ {
		out = append(out, r.buf[(start+i)%pathRingCap])
	}
	return out
}
