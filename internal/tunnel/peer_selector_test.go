package tunnel

import (
	"testing"
	"time"
)

func TestPeerSelectorPrefersHealthyFreshPeer(t *testing.T) {
	now := time.Unix(1700000000, 0)
	s := NewPeerSelector(PeerSelectorOptions{MaxAge: time.Minute})
	peers := []PeerCandidate{
		{
			ID:        "stale-close",
			Endpoint:  "10.0.0.1:1000",
			UpdatedAt: now.Add(-2 * time.Minute),
			Health:    PeerHealth{RTT: 20 * time.Millisecond, SuccessEWMA: 1},
		},
		{
			ID:        "fresh-bad",
			Endpoint:  "10.0.0.2:1000",
			UpdatedAt: now.Add(-10 * time.Second),
			Health:    PeerHealth{RTT: 10 * time.Millisecond, SuccessEWMA: 0.2, FailStreak: 4},
		},
		{
			ID:        "fresh-good",
			Endpoint:  "10.0.0.3:1000",
			UpdatedAt: now.Add(-10 * time.Second),
			Health:    PeerHealth{RTT: 80 * time.Millisecond, SuccessEWMA: 0.95},
		},
	}

	got, ok := s.Pick(peers, now)
	if !ok {
		t.Fatal("expected peer")
	}
	if got.PeerID != "fresh-good" {
		t.Fatalf("peer=%s want fresh-good", got.PeerID)
	}
}
