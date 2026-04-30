package tui

import (
	"testing"

	"dev.c0redev.volter/internal/config"
)

func TestSplitCommaList(t *testing.T) {
	if x := splitCommaList("a, b , "); len(x) != 2 || x[0] != "a" || x[1] != "b" {
		t.Fatalf("%q", x)
	}
	if splitCommaList("  ") != nil {
		t.Fatal()
	}
}

func TestMeshRelayRoundTrip(t *testing.T) {
	in := newMeshRelayInputs(&config.RelayOptions{
		TurnURLs:       []string{"turn:x@y:1"},
		StunServers:    []string{"s:1"},
		DhtRpcIntervalSec: 60,
		PathAggressive:    true,
	})
	got, err := meshRelayFromInputs(in)
	if err != "" {
		t.Fatal(err)
	}
	if len(got.TurnURLs) != 1 || got.TurnURLs[0] != "turn:x@y:1" {
		t.Fatalf("turn %#v", got.TurnURLs)
	}
	if got.DhtRpcIntervalSec != 60 || !got.PathAggressive {
		t.Fatalf("%+v", got)
	}
}
