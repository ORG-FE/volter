package vpn

import "testing"

func TestHex16(t *testing.T) {
	in := []byte{0, 1, 2, 3, 4, 5, 6, 7, 8}
	out := hex16(in)
	if len(out) != 16 {
		t.Fatalf("hex16 len=%d", len(out))
	}
	if out != "0001020304050607" {
		t.Fatalf("hex16 value=%q", out)
	}
}

func TestStoreForwardStatsIncrements(t *testing.T) {
	cpStats.Store(storeForwardStats{})
	incrSent()
	incrSent()
	incrReceived()
	s := StoreForwardStats()
	if s.Sent != 2 || s.Received != 1 {
		t.Fatalf("stats mismatch: %+v", s)
	}
}
