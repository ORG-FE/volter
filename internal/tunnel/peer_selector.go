package tunnel

import "time"

type PeerHealth struct {
	RTT         time.Duration
	SuccessEWMA float64
	FailStreak  int
	LastOK      time.Time
	InFlight    int
}

type PeerCandidate struct {
	ID        string
	Endpoint  string
	UpdatedAt time.Time
	Health    PeerHealth
}

type PeerDecision struct {
	PeerID   string
	Endpoint string
	Score    float64
	Reason   string
}

type PeerSelectorOptions struct {
	MaxAge time.Duration
}

type PeerSelector struct {
	maxAge time.Duration
}

func NewPeerSelector(opt PeerSelectorOptions) *PeerSelector {
	maxAge := opt.MaxAge
	if maxAge <= 0 {
		maxAge = 5 * time.Minute
	}
	return &PeerSelector{maxAge: maxAge}
}

func (s *PeerSelector) Pick(peers []PeerCandidate, now time.Time) (PeerDecision, bool) {
	if s == nil {
		s = NewPeerSelector(PeerSelectorOptions{})
	}
	var best PeerDecision
	for _, p := range peers {
		if p.ID == "" || p.Endpoint == "" {
			continue
		}
		if p.UpdatedAt.IsZero() || now.Sub(p.UpdatedAt) > s.maxAge {
			continue
		}
		score := peerScore(p.Health)
		if score <= 0 {
			continue
		}
		if best.PeerID == "" || score > best.Score {
			best = PeerDecision{PeerID: p.ID, Endpoint: p.Endpoint, Score: score, Reason: "health"}
		}
	}
	return best, best.PeerID != ""
}

func peerScore(h PeerHealth) float64 {
	ok := h.SuccessEWMA
	if ok <= 0 {
		ok = 0.5
	}
	if ok > 1 {
		ok = 1
	}
	failPenalty := 1.0 / float64(1+h.FailStreak)
	rttPenalty := 1.0
	if h.RTT > 0 {
		ms := float64(h.RTT.Milliseconds())
		rttPenalty = 1.0 / (1.0 + ms/250.0)
	}
	inFlightPenalty := 1.0 / float64(1+h.InFlight)
	return ok * failPenalty * rttPenalty * inFlightPenalty
}
