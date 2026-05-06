package vpn

import (
	"context"
	"fmt"
	"strings"
	"time"

	"dev.c0redev.volter/internal/clientlog"
	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/dht"
	"dev.c0redev.volter/internal/discovery"
	"dev.c0redev.volter/internal/telemetry"
)

func runGossipMesh(ctx context.Context, relay *config.RelayOptions) {
	if relay == nil {
		return
	}
	if !relay.GossipEnabled {
		return
	}
	if len(relay.GossipPeers) == 0 && len(relay.DHTFindURLs) == 0 {
		return
	}
	interval := 45 * time.Second
	if relay.GossipIntervalSec > 0 {
		interval = time.Duration(relay.GossipIntervalSec) * time.Second
	}
	maxAge := 24 * time.Hour
	if relay.GossipMaxAgeSec > 0 {
		maxAge = time.Duration(relay.GossipMaxAgeSec) * time.Second
	}
	runGossipMeshOnce(ctx, relay, maxAge)
	tick := time.NewTicker(interval)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			runGossipMeshOnce(ctx, relay, maxAge)
		}
	}
}

func runGossipMeshOnce(ctx context.Context, relay *config.RelayOptions, maxAge time.Duration) {
	var merged []discovery.RelayNode
	for _, u := range relay.GossipPeers {
		u = strings.TrimSpace(u)
		if u == "" {
			continue
		}
		sub, cancel := context.WithTimeout(ctx, 50*time.Second)
		nodes, err := discovery.FetchGossipHTTP(sub, u)
		cancel()
		if err != nil {
			clientlog.Warn("vpn: gossip fetch %s: %v", u, err)
			continue
		}
		merged = discovery.MergeGossip(merged, nodes, time.Now(), maxAge)
	}
	self := dht.DefaultTable().SelfID()
	for _, base := range relay.DHTFindURLs {
		base = strings.TrimSpace(base)
		if base == "" {
			continue
		}
		sub, cancel := context.WithTimeout(ctx, 50*time.Second)
		found, err := dht.FetchFindNearest(sub, base, self, 32)
		cancel()
		if err != nil {
			clientlog.Warn("vpn: dht find %s: %v", base, err)
			continue
		}
		merged = discovery.MergeGossip(merged, found, time.Now(), maxAge)
	}
	gctx, cancel := context.WithTimeout(ctx, 90*time.Second)
	view := applyRelayProductFilters(gctx, merged, relay)
	cancel()
	dig, err := discovery.RelayIndexDigest(view)
	if err != nil {
		clientlog.Warn("vpn: gossip digest: %v", err)
		return
	}
	clientlog.Info("vpn: gossip mesh digest=%s nodes=%d", dig, len(view))
	telemetry.RecordPath(telemetry.SwitchRelay, fmt.Sprintf("gossip digest=%s nodes=%d", dig, len(view)))
	dht.DefaultTable().Merge(view)
}
