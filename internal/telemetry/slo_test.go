package telemetry

import "testing"

func TestSLOSnapshot(t *testing.T) {
	NoteVPNStart()
	NoteSessionReady()
	before := TransportFallbackCount()
	NoteTransportFallback()
	if TransportFallbackCount() != before+1 {
		t.Fatal("fallback count")
	}
	a, b, _ := SLOSnapshot()
	if a != 1 || b != 1 {
		t.Fatalf("%d %d", a, b)
	}
}
