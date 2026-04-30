package telemetry

import (
	"sync"
	"time"
)

var (
	failoverMu     sync.Mutex
	failoverEwmaMs float64
)

func NoteFailoverLatency(d time.Duration) {
	if d <= 0 {
		return
	}
	ms := float64(d.Milliseconds())
	failoverMu.Lock()
	defer failoverMu.Unlock()
	if failoverEwmaMs <= 0 {
		failoverEwmaMs = ms
		return
	}
	failoverEwmaMs = 0.85*failoverEwmaMs + 0.15*ms
}

func FailoverLatencyEWMA() time.Duration {
	failoverMu.Lock()
	defer failoverMu.Unlock()
	if failoverEwmaMs <= 0 {
		return 0
	}
	return time.Duration(failoverEwmaMs) * time.Millisecond
}
