package telemetry

import "testing"

func TestPathRingSnapshotOrder(t *testing.T) {
	var r PathRing
	r.Add(SwitchICE, "a")
	r.Add(SwitchRelay, "b")
	s := r.Snapshot()
	if len(s) != 2 || s[0].Note != "a" || s[1].Kind != SwitchRelay {
		t.Fatal(s)
	}
}
