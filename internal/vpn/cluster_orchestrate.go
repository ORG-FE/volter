package vpn

import (
	"context"

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
	o := routeorch.NewOrchestrator()
	st, out, detail := o.RunFull(ctx, routeorch.Target{HostPort: targetHostPort}, entryHostPort, probe, assist)
	if out == routeorch.OutcomeMigrated && detail != "" {
		SetClusterDialPreference(detail)
	}
	return st, out, detail
}
