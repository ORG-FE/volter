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
	var entry string
	for _, a := range opt.ServerAddrs {
		a = strings.TrimSpace(a)
		if a != "" {
			entry = a
			break
		}
	}
	if entry == "" {
		return
	}
	probe := tcpReachProbe(routeorch.ProbeDefaultTimeout)
	octx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()

	invitePath := strings.TrimSpace(p.ClusterInvitePath)
	if invitePath == "" {
		invitePath = "/volter/cluster-invite"
	}
	hsPath := strings.TrimSpace(p.ClusterPeerHandshakePath)
	if hsPath == "" {
		hsPath = "/volter/cluster-peer-handshake"
	}
	nodeID := strings.TrimSpace(p.ClusterAssistTargetNodeID)
	if nodeID != "" {
		st, out, detail := RunClusterRouteOrchestratorFull(octx, target, entry, probe, &routeorch.AssistConfig{
			ClusterKey:    clusterPollHeaderKey(opt),
			InvitePath:    invitePath,
			HandshakePath: hsPath,
			TargetNodeID:  nodeID,
		})
		clientlog.Info("vpn: cluster route assist full stage=%s outcome=%s detail=%s", st, out, detail)
		return
	}
	st, out, detail := RunClusterRouteOrchestrator(octx, target, entry, probe)
	clientlog.Info("vpn: cluster route assist stage=%s outcome=%s detail=%s", st, out, detail)
}
