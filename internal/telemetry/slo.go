package telemetry

import (
	"math"
	"sync"
	"sync/atomic"
	"time"
)

var (
	sessionsStarted     atomic.Uint64
	sessionsReady       atomic.Uint64
	transportFallback   atomic.Uint64
	dpiQuicGoodnessBits atomic.Uint64
	iceSrflxEwmaBits    atomic.Uint64
	lastReadyMu         sync.Mutex
	lastReadyAt         time.Time
)

func NoteVPNStart() {
	sessionsStarted.Add(1)
}

func NoteSessionReady() {
	sessionsReady.Add(1)
	lastReadyMu.Lock()
	lastReadyAt = time.Now()
	lastReadyMu.Unlock()
}

func NoteTransportFallback() {
	transportFallback.Add(1)
}

func SLOSnapshot() (started uint64, ready uint64, lastReady time.Time) {
	lastReadyMu.Lock()
	t := lastReadyAt
	lastReadyMu.Unlock()
	return sessionsStarted.Load(), sessionsReady.Load(), t
}

func TransportFallbackCount() uint64 {
	return transportFallback.Load()
}

func SetDpiQuicGoodnessEWMA(v float64) {
	dpiQuicGoodnessBits.Store(math.Float64bits(v))
}

func DpiQuicGoodnessEWMA() float64 {
	return math.Float64frombits(dpiQuicGoodnessBits.Load())
}

func SetIceSrflxRttEwmaMs(v float64) {
	iceSrflxEwmaBits.Store(math.Float64bits(v))
}

func IceSrflxRttEwmaMs() float64 {
	return math.Float64frombits(iceSrflxEwmaBits.Load())
}
