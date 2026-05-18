package vpn

import (
	"context"
	"strings"
	"sync/atomic"
	"time"

	"dev.c0redev.volter/internal/clientlog"
	"dev.c0redev.volter/internal/clusteraddr"
	"dev.c0redev.volter/internal/probe"
	"dev.c0redev.volter/internal/telemetry"
	"dev.c0redev.volter/internal/tunnel"
)

const watchdogMaxMisses = 3

var watchdogRR atomic.Uint32

func runWatchdog(ctx context.Context, h *handler, opt Options) {
	interval := opt.WatchdogInterval
	if interval <= 0 || opt.OnWatchdogFail == nil {
		return
	}
	pingTO := opt.WatchdogServerPingTimeout
	if pingTO <= 0 {
		pingTO = 5 * time.Second
	}
	t := time.NewTicker(interval)
	defer t.Stop()
	misses := 0
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if watchdogOnce(h, pingTO) {
				misses = 0
				continue
			}
			misses++
			clientlog.Warn("vpn: watchdog: tcp probe miss %d/%d", misses, watchdogMaxMisses)
			if misses < watchdogMaxMisses {
				continue
			}
			clientlog.Warn("vpn: watchdog: server TCP failed after %d rounds", misses)
			telemetry.RecordPath(telemetry.SwitchWatchdog, "server tcp ping failed")
			opt.OnWatchdogFail()
			return
		}
	}
}

func watchdogPingOrder(h *handler) []string {
	addrs := append([]string(nil), h.opt.ServerAddrs...)
	if len(addrs) <= 1 {
		return addrs
	}
	active := strings.TrimSpace(tunnel.ActiveVolterServer())
	if active == "" {
		return addrs
	}
	want := clusteraddr.CanonicalHostPort(active)
	for i, a := range addrs {
		if clusteraddr.CanonicalHostPort(a) != want {
			continue
		}
		if i == 0 {
			return addrs
		}
		out := make([]string, len(addrs))
		copy(out, addrs[i:])
		copy(out[len(addrs)-i:], addrs[:i])
		return out
	}
	return addrs
}

func watchdogOnce(h *handler, pingTO time.Duration) bool {
	if h.udpMux != nil && h.udpMux.quicAlive() {
		return true
	}
	if len(h.opt.ServerAddrs) == 0 {
		return false
	}
	ordered := watchdogPingOrder(h)
	n := len(ordered)
	start := int(watchdogRR.Add(1)-1) % n
	var lastErr error
	for i := 0; i < n; i++ {
		addr := ordered[(start+i)%n]
		_, err := probe.PingTCP(addr, pingTO)
		if err == nil {
			return true
		}
		lastErr = err
	}
	if lastErr != nil {
		clientlog.Warn("vpn: watchdog: all tcp probes failed: %v", lastErr)
	}
	return false
}
