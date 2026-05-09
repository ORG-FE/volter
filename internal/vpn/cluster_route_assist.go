package vpn

import (
	"context"
	"net"
	"strings"
	"time"

	"dev.c0redev.volter/internal/clientlog"
	"dev.c0redev.volter/internal/clusteraddr"
	"dev.c0redev.volter/internal/routeorch"
)

func tcpReachProbe(d time.Duration) routeorch.ProbeFn {
	return func(ctx context.Context, hostPort string) error {
		var dialer net.Dialer
		if d > 0 {
			dialer.Timeout = d
		}
		c, err := dialer.DialContext(ctx, "tcp", hostPort)
		if err != nil {
			return err
		}
		return c.Close()
	}
}

func runClusterRouteAssist(ctx context.Context, opt Options) {
	p := opt.Protection
	if p == nil || !p.ClusterRouteAssist {
		return
	}
	raw := strings.TrimSpace(p.ClusterPreferredServer)
	if raw == "" {
		return
	}
	target := clusteraddr.CanonicalHostPort(raw)
	if target == "" || !strings.Contains(target, ":") {
		clientlog.Info("vpn: cluster route assist skipped (clusterPreferredServer must be host:port)")
		return
	}
	var entries []string
	seen := make(map[string]struct{})
	for _, a := range opt.ServerAddrs {
		a = strings.TrimSpace(a)
		if a == "" {
			continue
		}
		k := strings.ToLower(a)
		if _, ok := seen[k]; ok {
			continue
		}
		seen[k] = struct{}{}
		entries = append(entries, a)
	}
	if len(entries) == 0 {
		return
	}
	invitePath := strings.TrimSpace(p.ClusterInvitePath)
	if invitePath == "" {
		invitePath = "/volter/cluster-invite"
	}
	hsPath := strings.TrimSpace(p.ClusterPeerHandshakePath)
	if hsPath == "" {
		hsPath = "/volter/cluster-peer-handshake"
	}
	nodeID := strings.TrimSpace(p.ClusterAssistTargetNodeID)

	runOnce := func() {
		probe := tcpReachProbe(routeorch.ProbeDefaultTimeout)
		for _, entry := range entries {
			octx, cancel := context.WithTimeout(ctx, 60*time.Second)
			if nodeID != "" {
				st, out, detail, dir := RunClusterRouteOrchestratorFullDirective(octx, target, entry, probe, &routeorch.AssistConfig{
					ClusterKey:    clusterPollHeaderKey(opt),
					InvitePath:    invitePath,
					HandshakePath: hsPath,
					TargetNodeID:  nodeID,
				})
				cancel()
				clientlog.Info("vpn: cluster route assist full entry=%s stage=%s outcome=%s detail=%s", entry, st, out, detail)
				if out == routeorch.OutcomeMigrated {
					if opt.RouteController != nil && dir.Endpoint != "" {
						opt.RouteController.Apply(dir)
					}
					return
				}
				continue
			}
			st, out, detail := RunClusterRouteOrchestrator(octx, target, entry, probe)
			cancel()
			clientlog.Info("vpn: cluster route assist entry=%s stage=%s outcome=%s detail=%s", entry, st, out, detail)
			if out == routeorch.OutcomeMigrated {
				return
			}
		}
	}

	runOnce()
	tick := time.NewTicker(2 * time.Minute)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			runOnce()
		}
	}
}
