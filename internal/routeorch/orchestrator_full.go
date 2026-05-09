package routeorch

import (
	"context"
	"strings"
	"time"
)

type RouteDirective struct {
	Target    string
	Mode      string
	PeerID    string
	Endpoint  string
	RouteID   string
	ExpiresAt time.Time
	Reason    string
}

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
	st, out, detail, _ := o.RunFullDirective(ctx, target, entryHostPort, probe, assist)
	return st, out, detail
}

func (o *Orchestrator) RunFullDirective(ctx context.Context, target Target, entryHostPort string, probe ProbeFn, assist *AssistConfig) (Stage, Outcome, string, RouteDirective) {
	st, out, detail := o.Run(ctx, target, entryHostPort, probe)
	if out == OutcomeMigrated {
		return st, out, detail, RouteDirective{}
	}
	if st != StageSignal || assist == nil {
		return st, out, detail, RouteDirective{}
	}
	ent := strings.TrimSpace(entryHostPort)
	if ent == "" {
		return StageFallback, OutcomeFallback, detail, RouteDirective{}
	}
	tgt := strings.TrimSpace(assist.TargetNodeID)
	if tgt == "" {
		return StageSignal, OutcomeFallback, "assist_missing_target_node_id", RouteDirective{}
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
		return StageProbeAssist, OutcomeFallback, err.Error(), RouteDirective{}
	}
	if resp != nil {
		s := strings.ToLower(strings.TrimSpace(resp.Status))
		if s != "" && s != "accepted" {
			return StageProbeAssist, OutcomeFallback, "invite_status:" + resp.Status, RouteDirective{}
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
		return StageProbeAssist, OutcomeFallback, hsErr.Error(), RouteDirective{}
	}
	dir := routeDirectiveFromResponses(target.Canonical(), req.CorrelationID, resp, hsResp)
	if hsResp != nil {
		s := strings.ToLower(strings.TrimSpace(hsResp.Status))
		if s != "" && s != "accepted" {
			return StageProbeAssist, OutcomeFallback, "handshake_status:" + hsResp.Status, RouteDirective{}
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
		return StageProbeAssist, OutcomeError, ctx.Err().Error(), RouteDirective{}
	case <-time.After(wait):
	}
	pctx, cancel := context.WithTimeout(ctx, o.ProbeTimeout)
	err = probe(pctx, probeTarget)
	cancel()
	if err == nil {
		if dir.Endpoint == "" {
			dir = RouteDirective{Target: target.Canonical(), Mode: "server_relay", Endpoint: probeTarget, RouteID: req.CorrelationID, ExpiresAt: time.UnixMilli(req.DeadlineMs), Reason: "probe_assist"}
		}
		return StageMigrate, OutcomeMigrated, probeTarget, dir
	}
	return StageFallback, OutcomeFallback, err.Error(), RouteDirective{}
}

func routeDirectiveFromResponses(target, routeID string, inv *InviteResponse, hs *PeerHandshakeResponse) RouteDirective {
	var mode, peer, endpoint, rid string
	var ttl int
	apply := func(m, p, e, r string, t int) {
		if strings.TrimSpace(m) != "" {
			mode = strings.TrimSpace(m)
		}
		if strings.TrimSpace(p) != "" {
			peer = strings.TrimSpace(p)
		}
		if strings.TrimSpace(e) != "" {
			endpoint = strings.TrimSpace(e)
		}
		if strings.TrimSpace(r) != "" {
			rid = strings.TrimSpace(r)
		}
		if t > 0 {
			ttl = t
		}
	}
	if inv != nil {
		apply(inv.RouteMode, inv.PeerID, inv.Endpoint, inv.RouteID, inv.TTLSeconds)
	}
	if hs != nil {
		apply(hs.RouteMode, hs.PeerID, hs.Endpoint, hs.RouteID, hs.TTLSeconds)
	}
	if endpoint == "" {
		return RouteDirective{}
	}
	if mode == "" {
		mode = "server_relay"
	}
	if rid == "" {
		rid = routeID
	}
	if ttl <= 0 {
		ttl = int(InviteTTL().Seconds())
	}
	return RouteDirective{
		Target:    target,
		Mode:      mode,
		PeerID:    peer,
		Endpoint:  endpoint,
		RouteID:   rid,
		ExpiresAt: time.Now().Add(time.Duration(ttl) * time.Second),
		Reason:    "assist_response",
	}
}
