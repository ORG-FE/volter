package routeorch

import (
	"context"
	"strings"
	"time"
)

type AssistConfig struct {
	ClusterKey    string
	InvitePath    string
	HandshakePath string
	TargetNodeID  string
	ClientID      string
	CorrelationID string
	DeadlineMs    int64
}

func (o *Orchestrator) RunFull(ctx context.Context, target Target, entryHostPort string, probe ProbeFn, assist *AssistConfig) (Stage, Outcome, string) {
	st, out, detail := o.Run(ctx, target, entryHostPort, probe)
	if out == OutcomeMigrated {
		return st, out, detail
	}
	if st != StageSignal || assist == nil {
		return st, out, detail
	}
	ent := strings.TrimSpace(entryHostPort)
	if ent == "" {
		return StageFallback, OutcomeFallback, detail
	}
	tgt := strings.TrimSpace(assist.TargetNodeID)
	if tgt == "" {
		return StageSignal, OutcomeFallback, "assist_missing_target_node_id"
	}
	req := InviteRequest{
		ClientID:      strings.TrimSpace(assist.ClientID),
		Nonce:         randomCorrelationID(),
		TargetNodeID:  tgt,
		CorrelationID: strings.TrimSpace(assist.CorrelationID),
		DeadlineMs:    assist.DeadlineMs,
	}
	if req.ClientID == "" {
		req.ClientID = "volter-client"
	}
	if req.CorrelationID == "" {
		req.CorrelationID = randomCorrelationID()
	}
	if req.DeadlineMs == 0 {
		req.DeadlineMs = time.Now().Add(InviteTTL()).UnixMilli()
	}
	ip := strings.TrimSpace(assist.InvitePath)
	resp, err := PostClusterInvite(ctx, ent, assist.ClusterKey, ip, req)
	if err != nil {
		return StageProbeAssist, OutcomeFallback, err.Error()
	}
	if resp != nil {
		s := strings.ToLower(strings.TrimSpace(resp.Status))
		if s != "" && s != "accepted" {
			return StageProbeAssist, OutcomeFallback, "invite_status:" + resp.Status
		}
	}
	probeTarget := target.Canonical()
	if resp != nil {
		redir := Target{HostPort: strings.TrimSpace(resp.RedirectHostPort)}.Canonical()
		if redir != "" {
			probeTarget = redir
		}
	}
	hp := strings.TrimSpace(assist.HandshakePath)
	hsResp, hsErr := PostClusterPeerHandshake(ctx, ent, assist.ClusterKey, hp, PeerHandshakeRequest{
		InviteID:      req.CorrelationID,
		TargetNodeID:  tgt,
		CorrelationID: req.CorrelationID,
		DeadlineMs:    req.DeadlineMs,
	})
	if hsErr != nil {
		return StageProbeAssist, OutcomeFallback, hsErr.Error()
	}
	if hsResp != nil {
		s := strings.ToLower(strings.TrimSpace(hsResp.Status))
		if s != "" && s != "accepted" {
			return StageProbeAssist, OutcomeFallback, "handshake_status:" + hsResp.Status
		}
		redir := Target{HostPort: strings.TrimSpace(hsResp.RedirectHostPort)}.Canonical()
		if redir != "" {
			probeTarget = redir
		}
	}
	wait := o.AssistWait
	if req.DeadlineMs > 0 {
		if d := time.Until(time.UnixMilli(req.DeadlineMs)); d > 0 && d < wait {
			wait = d
		}
	}
	select {
	case <-ctx.Done():
		return StageProbeAssist, OutcomeError, ctx.Err().Error()
	case <-time.After(wait):
	}
	pctx, cancel := context.WithTimeout(ctx, o.ProbeTimeout)
	err = probe(pctx, probeTarget)
	cancel()
	if err == nil {
		return StageMigrate, OutcomeMigrated, probeTarget
	}
	return StageFallback, OutcomeFallback, err.Error()
}
