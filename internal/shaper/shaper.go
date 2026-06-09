package shaper

import (
	"math"
	"time"
)

type Config struct {
	Enabled        bool
	Profile        string
	MaxOverheadPct int
	MaxDelayMs     int
	Seed           uint64
}

const (
	defaultMaxOverheadPct = 100
	defaultMaxDelayMs     = 200
	maxFrameTarget        = 1400
	ewmaAlpha             = 0.2
)

type Decision struct {
	TargetLen int
	Delay     time.Duration
}

type Shaper struct {
	enabled    bool
	prof       Profile
	maxOvhdPct int
	maxDelayMs int

	rng   *prng
	state State

	obsLen   float64
	lastSeen bool
}

func New(cfg Config) *Shaper {
	if !cfg.Enabled || cfg.Profile == "" {
		return &Shaper{enabled: false}
	}
	prof, ok := ProfileByName(cfg.Profile)
	if !ok {
		return &Shaper{enabled: false}
	}
	ovhd := cfg.MaxOverheadPct
	if ovhd <= 0 {
		ovhd = defaultMaxOverheadPct
	}
	delay := cfg.MaxDelayMs
	if delay <= 0 {
		delay = defaultMaxDelayMs
	}
	seed := cfg.Seed
	if seed == 0 {

		seed = hashSeed(cfg.Profile, ovhd, delay)
	}
	return &Shaper{
		enabled:    true,
		prof:       prof,
		maxOvhdPct: ovhd,
		maxDelayMs: delay,
		rng:        newPRNG(seed),
		state:      prof.initial,
	}
}

func (s *Shaper) Enabled() bool { return s != nil && s.enabled }

func (s *Shaper) Next(payloadLen int) Decision {
	if s == nil || !s.enabled {
		return Decision{}
	}
	s.observe(payloadLen)
	s.advance()

	p := s.prof.params[s.state]
	target := int(s.rng.sample(p.frameLen))
	delayMs := s.rng.sample(p.delayMs)

	target = s.adaptTarget(target, payloadLen)
	target = s.capTarget(target, payloadLen)

	d := time.Duration(s.capDelay(delayMs)) * time.Millisecond
	return Decision{TargetLen: target, Delay: d}
}

func (s *Shaper) advance() {
	row := s.prof.trans[s.state]
	u := s.rng.float64()
	var acc float64
	for next := State(0); next < numStates; next++ {
		acc += row[next]
		if u <= acc {
			s.state = next
			return
		}
	}
	s.state = numStates - 1
}

func (s *Shaper) observe(payloadLen int) {
	pl := float64(payloadLen)
	if !s.lastSeen {
		s.obsLen = pl
		s.lastSeen = true
		return
	}
	s.obsLen = ewmaAlpha*pl + (1-ewmaAlpha)*s.obsLen
}

func (s *Shaper) adaptTarget(profileTarget, payloadLen int) int {
	if s.obsLen <= 0 {
		return profileTarget
	}
	mixed := 0.7*float64(profileTarget) + 0.3*s.obsLen
	t := int(math.Round(mixed))

	if t < payloadLen {
		t = payloadLen
	}
	return t
}

func (s *Shaper) capTarget(target, payloadLen int) int {
	if target <= payloadLen {
		return 0
	}
	maxAdd := payloadLen * s.maxOvhdPct / 100
	if maxAdd < 0 {
		maxAdd = 0
	}
	if target-payloadLen > maxAdd {
		target = payloadLen + maxAdd
	}
	if target > maxFrameTarget && payloadLen <= maxFrameTarget {
		target = maxFrameTarget
	}
	if target <= payloadLen {
		return 0
	}
	return target
}

func (s *Shaper) capDelay(ms float64) float64 {
	if ms < 0 {
		return 0
	}
	if ms > float64(s.maxDelayMs) {
		return float64(s.maxDelayMs)
	}
	return ms
}

func hashSeed(profile string, a, b int) uint64 {
	h := uint64(1469598103934665603)
	mix := func(x uint64) {
		h ^= x
		h *= 1099511628211
	}
	for i := 0; i < len(profile); i++ {
		mix(uint64(profile[i]))
	}
	mix(uint64(a))
	mix(uint64(b))
	if h == 0 {
		h = 1
	}
	return h
}
