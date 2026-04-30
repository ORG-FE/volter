package telemetry

import (
	"encoding/json"
	"math"
	"os"
	"path/filepath"
	"time"
)

func WriteSLOSnapshotFile() error {
	d, err := os.UserConfigDir()
	if err != nil {
		return err
	}
	dir := filepath.Join(d, "volter")
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	p := filepath.Join(dir, "telemetry-slo.json")
	started, ready, lastReady := SLOSnapshot()
	ew := FailoverLatencyEWMA()
	var ewMs float64
	if ew > 0 {
		ewMs = float64(ew.Milliseconds())
	}
	good := DpiQuicGoodnessEWMA()
	lossProxy := 0.0
	if good > 0 && good <= 1 {
		lossProxy = math.Max(0, 1.0-good)
	}
	out := struct {
		WroteAt             time.Time `json:"wroteAt"`
		SessionsStarted     uint64    `json:"sessionsStarted"`
		SessionsReady       uint64    `json:"sessionsReady"`
		LastReadyAt         time.Time `json:"lastReadyAt,omitempty"`
		TransportFallbacks  uint64    `json:"transportFallbacks"`
		FailoverEwmaMs      float64   `json:"failoverEwmaMs"`
		DpiQuicGoodnessEwma float64   `json:"dpiQuicGoodnessEwma"`
		DpiQuicLossProxy    float64   `json:"dpiQuicLossProxy"`
		IceSrflxRttEwmaMs   float64   `json:"iceSrflxRttEwmaMs"`
	}{
		WroteAt:             time.Now().UTC(),
		SessionsStarted:     started,
		SessionsReady:       ready,
		LastReadyAt:         lastReady,
		TransportFallbacks:  TransportFallbackCount(),
		FailoverEwmaMs:      ewMs,
		DpiQuicGoodnessEwma: good,
		DpiQuicLossProxy:    lossProxy,
		IceSrflxRttEwmaMs:   IceSrflxRttEwmaMs(),
	}
	b, err := json.MarshalIndent(out, "", "  ")
	if err != nil {
		return err
	}
	tmp := p + ".tmp"
	if err := os.WriteFile(tmp, b, 0600); err != nil {
		return err
	}
	return os.Rename(tmp, p)
}
