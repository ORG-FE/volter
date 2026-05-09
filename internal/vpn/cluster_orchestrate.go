package vpn

import (
	"context"
	"time"

	"dev.c0redev.volter/internal/routeorch"
)

func RunClusterRouteOrchestrator(ctx context.Context, targetHostPort, entryHostPort string, probe routeorch.ProbeFn) (routeorch.Stage, routeorch.Outcome, string) {
	o := routeorch.NewOrchestrator()
	st, out, detail := o.Run(ctx, routeorch.Target{HostPort: targetHostPort}, entryHostPort, probe)
	if out == routeorch.OutcomeMigrated && detail != "" {
		SetClusterDialPreference(detail)
	}
	return st, out, detail
}

func RunClusterRouteOrchestratorFull(ctx context.Context, targetHostPort, entryHostPort string, probe routeorch.ProbeFn, assist *routeorch.AssistConfig) (routeorch.Stage, routeorch.Outcome, string) {
	st, out, detail, _ := RunClusterRouteOrchestratorFullDirective(ctx, targetHostPort, entryHostPort, probe, assist)
	return st, out, detail
}

func RunClusterRouteOrchestratorFullDirective(ctx context.Context, targetHostPort, entryHostPort string, probe routeorch.ProbeFn, assist *routeorch.AssistConfig) (routeorch.Stage, routeorch.Outcome, string, RouteDirective) {
	o := routeorch.NewOrchestrator()
	st, out, detail, rd := o.RunFullDirective(ctx, routeorch.Target{HostPort: targetHostPort}, entryHostPort, probe, assist)
	dir := RouteDirective{}
	if rd.Endpoint != "" {
		dir = RouteDirective{
			Target:    rd.Target,
			Mode:      rd.Mode,
			PeerID:    rd.PeerID,
			Endpoint:  rd.Endpoint,
			RouteID:   rd.RouteID,
			ExpiresAt: rd.ExpiresAt,
			Reason:    rd.Reason,
		}
	}
	if out == routeorch.OutcomeMigrated && detail != "" {
		SetClusterDialPreference(detail)
		if dir.Endpoint == "" {
			dir = RouteDirective{Target: targetHostPort, Mode: "server_relay", Endpoint: detail, ExpiresAt: time.Now().Add(routeorch.InviteTTL()), Reason: "probe_migrated"}
		}
	}
	return st, out, detail, dir
}
