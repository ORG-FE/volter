package vpn

import (
	"context"
	"time"

	"dev.c0redev.volter/internal/clientlog"
	"dev.c0redev.volter/internal/probe"
	"dev.c0redev.volter/internal/telemetry"
)

func runWatchdog(ctx context.Context, h *handler, opt Options) {
	interval := opt.WatchdogInterval
	if interval <= 0 || opt.OnWatchdogFail == nil {
		return
	}
	pingTO := opt.WatchdogServerPingTimeout
	if pingTO <= 0 {
		pingTO = 2 * time.Second
	}
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if !watchdogOnce(h, pingTO) {
				clientlog.Warn("vpn: watchdog: server TCP failed")
				telemetry.RecordPath(telemetry.SwitchWatchdog, "server tcp ping failed")
				opt.OnWatchdogFail()
				return
			}
		}
	}
}

func watchdogOnce(h *handler, pingTO time.Duration) bool {
	if len(h.opt.ServerAddrs) == 0 {
		return false
	}
	if _, err := probe.Ping(h.opt.ServerAddrs[0], pingTO); err != nil {
		clientlog.Warn("vpn: watchdog: server TCP: %v", err)
		return false
	}
	return true
}
