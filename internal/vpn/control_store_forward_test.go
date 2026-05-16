package vpn

import "testing"

func TestControlPlaneMsgID(t *testing.T) {
	in := [32]byte{0, 1, 2, 3, 4, 5, 6, 7, 8}
	out := controlPlaneMsgID(in)
	if len(out) != 64 {
		t.Fatalf("id len=%d", len(out))
	}
	if out[:16] != "0001020304050607" {
		t.Fatalf("id prefix=%q", out[:16])
	}
}

func TestStoreForwardStatsIncrements(t *testing.T) {
	before := StoreForwardStats()
	incrSent()
	incrSent()
	incrReceived()
	s := StoreForwardStats()
	if s.Sent != before.Sent+2 || s.Received != before.Received+1 {
		t.Fatalf("stats mismatch before=%+v after=%+v", before, s)
	}
}
