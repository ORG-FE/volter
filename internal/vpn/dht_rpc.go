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

func runDhtRpcSidecar(ctx context.Context, relay *config.RelayOptions) {
	if relay == nil {
		return
	}
	if listen := strings.TrimSpace(relay.DhtRpcListenUDP); listen != "" {
		c, err := dht.ListenRPCUDP(listen, relay.DhtRpcSecret, dht.DefaultTable(), dht.DefaultKVStore())
		if err != nil {
			clientlog.Warn("vpn: dht rpc listen %s: %v", listen, err)
		} else {
			defer func() { _ = c.Close() }()
			clientlog.OK("vpn: dht rpc udp %s", listen)
		}
	}

	interval := 75 * time.Second
	if relay.DhtRpcIntervalSec > 0 {
		interval = time.Duration(relay.DhtRpcIntervalSec) * time.Second
	}
	runDhtRpcProbe(ctx, relay)
	bootstrap := time.NewTicker(4 * time.Second)
	defer bootstrap.Stop()
	bootstrapUntil := time.NewTimer(80 * time.Second)
	defer bootstrapUntil.Stop()
	bootstrapOn := true
	tick := time.NewTicker(interval)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-bootstrap.C:
			if !bootstrapOn {
				continue
			}
			runDhtRpcProbe(ctx, relay)
		case <-bootstrapUntil.C:
			bootstrapOn = false
		case <-tick.C:
			runDhtRpcProbe(ctx, relay)
		}
	}
}

func runDhtRpcProbe(ctx context.Context, relay *config.RelayOptions) {
	if relay == nil {
		return
	}
	tab := dht.DefaultTable()
	target := tab.SelfID()
	secret := relay.DhtRpcSecret
	seeds := dhtRPCSeeds(relay)
	findK := 24
	if relay.DhtRpcFindK > 0 {
		findK = relay.DhtRpcFindK
	}
	if relay.DhtIterativeRounds > 0 {
		alpha := relay.DhtIterativeAlpha
		if alpha <= 0 {
			alpha = 3
		}
		filter := func(ctx context.Context, nodes []discovery.RelayNode) []discovery.RelayNode {
			gctx, cancel := context.WithTimeout(ctx, 90*time.Second)
			defer cancel()
			return applyRelayProductFilters(gctx, nodes, relay)
		}
		dht.IterativeFindNode(ctx, tab, secret, target, seeds, findK, alpha, relay.DhtIterativeRounds, filter)
		view := tab.Nearest(256)
		if dig, err := discovery.RelayIndexDigest(view); err == nil {
			telemetry.RecordPath(telemetry.SwitchRelay, fmt.Sprintf("dht iterative digest=%s nodes=%d", dig, len(view)))
		}
		return
	}
	seen := make(map[string]struct{})
	addrs := append([]string(nil), seeds...)
	for _, n := range tab.Nearest(64) {
		if a := strings.TrimSpace(n.DhtRPC); a != "" {
			addrs = append(addrs, a)
		}
	}
	for _, addr := range addrs {
		addr = strings.TrimSpace(addr)
		if addr == "" {
			continue
		}
		if _, dup := seen[addr]; dup {
			continue
		}
		seen[addr] = struct{}{}
		sub, cancel := context.WithTimeout(ctx, 8*time.Second)
		_ = dht.UDPPing(sub, addr, secret)
		raw, err := dht.UDPFindNode(sub, addr, secret, target, findK)
		cancel()
		if err != nil {
			clientlog.Warn("vpn: dht rpc %s: %v", addr, err)
			continue
		}
		gctx, cancel := context.WithTimeout(ctx, 90*time.Second)
		view := applyRelayProductFilters(gctx, raw, relay)
		cancel()
		tab.Merge(view)
		if dig, err := discovery.RelayIndexDigest(view); err == nil {
			telemetry.RecordPath(telemetry.SwitchRelay, fmt.Sprintf("dht rpc digest=%s nodes=%d", dig, len(view)))
		}
	}
}
