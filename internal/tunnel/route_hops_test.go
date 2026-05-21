package tunnel

import (
	"encoding/json"
	"testing"

	"dev.c0redev.volter/internal/config"
)

func TestNextRelayHop(t *testing.T) {
	hops := []string{"peer_tcp:1.1.1.1:1", "peer_tcp:2.2.2.2:2"}
	k, a, ok := NextRelayHop(hops, 1)
	if !ok || k != "peer_tcp" || a != "2.2.2.2:2" {
		t.Fatalf("next from hop1: %v %q %q", ok, k, a)
	}
	if _, _, ok = NextRelayHop(hops, 2); ok {
		t.Fatal("expected no hop after last peer")
	}
}

func TestParseRouteHop(t *testing.T) {
	k, a, ok := ParseRouteHop("peer_udp:127.0.0.1:4001")
	if !ok || k != "peer_udp" || a != "127.0.0.1:4001" {
		t.Fatalf("got %v %q %q", ok, k, a)
	}
	if _, _, ok := ParseRouteHop("bad"); ok {
		t.Fatal("expected parse fail")
	}
}

func TestEncodeRouteHops(t *testing.T) {
	plan := RoutePlan{
		Hops: []RouteHop{
			{Kind: "peer_tcp", Addr: "1.1.1.1:1"},
			{Kind: "peer_tcp", Addr: "2.2.2.2:2"},
		},
	}
	got := EncodeRouteHops(plan)
	if len(got) != 2 || got[0] != "peer_tcp:1.1.1.1:1" {
		t.Fatalf("%v", got)
	}
}

func TestAttachRouteHops(t *testing.T) {
	base := &config.ProtectionOptions{}
	plan := RoutePlan{Hops: []RouteHop{{Kind: "peer_tcp", Addr: "a:1"}, {Kind: "peer_tcp", Addr: "b:2"}}}
	out := AttachRouteHops(base, plan)
	if out == nil || len(out.RelayRouteHops) != 2 || !out.RoutePlannerV2 {
		t.Fatalf("%+v", out)
	}
	out2 := AttachRouteHops(&config.ProtectionOptions{RouteID: "r1"}, plan)
	if out2 == nil || len(out2.RelayRouteHops) != 2 || out2.RoutePlannerV2 {
		t.Fatalf("routeId must not force planner v2: %+v", out2)
	}
}

func TestBuildRoutePlanPeerChain(t *testing.T) {
	dec := PathDecision{
		MaxPeerHops:        2,
		PeerTCPCandidates:  []string{"a:1", "b:2", "c:3"},
		PeerQUICCandidates: []string{"q:1"},
	}
	plan := BuildRoutePlan("dst", dec)
	if len(plan.Hops) != 2 {
		t.Fatalf("want 2 hops, got %d %+v", len(plan.Hops), plan.Hops)
	}
	if plan.Hops[0].Addr != "a:1" || plan.Hops[1].Addr != "b:2" {
		t.Fatalf("unexpected chain %+v", plan.Hops)
	}
}

func TestRelayHopLimit(t *testing.T) {
	p := &config.ProtectionOptions{RelayHop: 3, RelayMaxHop: 2}
	if !relayHopLimitExceeded(p) {
		t.Fatal("expected limit exceeded")
	}
	p2 := &config.ProtectionOptions{RelayHop: 2, RelayMaxHop: 2}
	if relayHopLimitExceeded(p2) {
		t.Fatal("hop 2 should be allowed when max is 2")
	}
}

func TestProtForRelayForwardIncrements(t *testing.T) {
	base := &config.ProtectionOptions{
		RelayHop:       1,
		RelayMaxHop:    2,
		HopIndex:       1,
		PeerID:         "p1",
		RelayNonce:     "n1",
		RelayRouteHops: []string{"peer_tcp:a:1", "peer_tcp:b:2"},
	}
	fwd := ProtForRelayForward(base, "tok")
	if fwd == nil || fwd.RelayHop != 2 || fwd.HopIndex != 2 {
		t.Fatalf("%+v", fwd)
	}
	if fwd.RelaySig == "" {
		t.Fatal("expected relay sig")
	}
}

func TestFallbackDialProt(t *testing.T) {
	src := &config.ProtectionOptions{
		RelayHop: 1,
		PeerID:   "p",
	}
	got := fallbackDialProt(src, nil, false, true)
	if got == nil || got.RelayHop != 0 || got.PeerID != "" {
		t.Fatalf("%+v", got)
	}
	got2 := fallbackDialProt(src, nil, true, false)
	if got2 == nil || got2.RelayHop < 1 {
		t.Fatalf("cluster path: %+v", got2)
	}
}

func TestRelayRouteHopsJSONRoundTrip(t *testing.T) {
	p := AttachRouteHops(&config.ProtectionOptions{}, RoutePlan{
		Hops: []RouteHop{{Kind: "peer_tcp", Addr: "h:1"}},
	})
	b, err := json.Marshal(p)
	if err != nil {
		t.Fatal(err)
	}
	var back config.ProtectionOptions
	if err := json.Unmarshal(b, &back); err != nil {
		t.Fatal(err)
	}
	if len(back.RelayRouteHops) != 1 || back.RelayRouteHops[0] != "peer_tcp:h:1" {
		t.Fatalf("%+v", back)
	}
}
