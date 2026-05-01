package routeorch

import (
	"context"
	"strings"
	"time"

	"dev.c0redev.volter/internal/clusteraddr"
)

type Stage string

const (
	StagePickTarget  Stage = "pick_target"
	StageProbeDirect Stage = "probe_direct"
	StageSignal      Stage = "signal_entry_to_target"
	StageProbeAssist Stage = "probe_assisted"
	StageMigrate     Stage = "migrate_primary"
	StageFallback    Stage = "fallback_entry"
)

type Outcome string

const (
	OutcomeMigrated Outcome = "migrated"
	OutcomeFallback Outcome = "fallback"
	OutcomeError    Outcome = "error"
)

type Target struct {
	HostPort string
}

func (t Target) Canonical() string {
	return clusteraddr.CanonicalHostPort(t.HostPort)
}

type Orchestrator struct {
	ProbeTimeout time.Duration
	AssistWait   time.Duration
}

func NewOrchestrator() *Orchestrator {
	return &Orchestrator{
		ProbeTimeout: ProbeDefaultTimeout,
		AssistWait:   InviteTTL(),
	}
}

type ProbeFn func(ctx context.Context, hostPort string) error

func (o *Orchestrator) Run(ctx context.Context, target Target, entryHostPort string, probe ProbeFn) (Stage, Outcome, string) {
	tgt := target.Canonical()
	if tgt == "" {
		return StagePickTarget, OutcomeError, "empty_target"
	}
	if probe == nil {
		return StageProbeDirect, OutcomeError, "no_probe"
	}
	pctx, cancel := context.WithTimeout(ctx, o.ProbeTimeout)
	err := probe(pctx, tgt)
	cancel()
	if err == nil {
		return StageMigrate, OutcomeMigrated, tgt
	}
	ent := strings.TrimSpace(entryHostPort)
	if ent == "" || clusteraddr.CanonicalHostPort(ent) == tgt {
		return StageFallback, OutcomeFallback, err.Error()
	}
	return StageSignal, OutcomeFallback, "direct_failed_signal_pending"
}
