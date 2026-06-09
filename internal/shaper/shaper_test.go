package shaper

import (
	"math"
	"testing"
	"time"
)

func TestDisabledIsNoOp(t *testing.T) {
	for _, cfg := range []Config{
		{Enabled: false, Profile: "web"},
		{Enabled: true, Profile: ""},
		{Enabled: true, Profile: "nonexistent"},
	} {
		s := New(cfg)
		if s.Enabled() {
			t.Fatalf("cfg %+v: ожидался no-op shaper", cfg)
		}
		d := s.Next(500)
		if d.TargetLen != 0 || d.Delay != 0 {
			t.Fatalf("cfg %+v: no-op должен вернуть пустое Decision, got %+v", cfg, d)
		}
	}
}

func TestNilShaperSafe(t *testing.T) {
	var s *Shaper
	if s.Enabled() {
		t.Fatal("nil shaper не должен быть enabled")
	}
	if d := s.Next(100); d.TargetLen != 0 || d.Delay != 0 {
		t.Fatalf("nil shaper должен вернуть пустое Decision, got %+v", d)
	}
}

func TestDeterministicBySeed(t *testing.T) {
	mk := func() *Shaper { return New(Config{Enabled: true, Profile: "web", Seed: 12345}) }
	a, b := mk(), mk()
	for i := 0; i < 1000; i++ {
		da := a.Next(300 + i%200)
		db := b.Next(300 + i%200)
		if da != db {
			t.Fatalf("шаг %d: расхождение при одном seed: %+v vs %+v", i, da, db)
		}
	}
}

func TestDifferentSeedsDiverge(t *testing.T) {
	a := New(Config{Enabled: true, Profile: "web", Seed: 1})
	b := New(Config{Enabled: true, Profile: "web", Seed: 2})
	same := true
	for i := 0; i < 200; i++ {
		if a.Next(400) != b.Next(400) {
			same = false
			break
		}
	}
	if same {
		t.Fatal("разные seed дали идентичные последовательности")
	}
}

func TestDelayCap(t *testing.T) {
	const capMs = 50
	s := New(Config{Enabled: true, Profile: "web", MaxDelayMs: capMs, Seed: 7})
	for i := 0; i < 5000; i++ {
		d := s.Next(200)
		if d.Delay < 0 {
			t.Fatalf("отрицательная задержка: %v", d.Delay)
		}
		if d.Delay > capMs*time.Millisecond {
			t.Fatalf("задержка %v превысила cap %dms", d.Delay, capMs)
		}
	}
}

func TestOverheadCap(t *testing.T) {
	const payload = 200
	const pct = 50
	s := New(Config{Enabled: true, Profile: "bulk", MaxOverheadPct: pct, Seed: 9})
	maxLen := payload + payload*pct/100
	for i := 0; i < 5000; i++ {
		d := s.Next(payload)
		if d.TargetLen == 0 {
			continue
		}
		if d.TargetLen < payload {
			t.Fatalf("TargetLen %d меньше payload %d", d.TargetLen, payload)
		}
		if d.TargetLen > maxLen {
			t.Fatalf("TargetLen %d превысил cap %d (payload=%d, pct=%d)", d.TargetLen, maxLen, payload, pct)
		}
	}
}

func TestTargetNeverShrinksPayload(t *testing.T) {
	s := New(Config{Enabled: true, Profile: "game", Seed: 3})
	for i := 0; i < 3000; i++ {
		pl := 100 + i%1300
		d := s.Next(pl)
		if d.TargetLen != 0 && d.TargetLen < pl {
			t.Fatalf("TargetLen %d < payload %d — padding не может укорачивать", d.TargetLen, pl)
		}
	}
}

func TestTransitionMatricesSumToOne(t *testing.T) {
	for _, name := range ProfileNames() {
		p, ok := ProfileByName(name)
		if !ok {
			t.Fatalf("профиль %q не найден", name)
		}
		for st := State(0); st < numStates; st++ {
			var sum float64
			for next := State(0); next < numStates; next++ {
				v := p.trans[st][next]
				if v < 0 {
					t.Fatalf("%s[%s][%s] отрицательная вероятность %v", name, st, next, v)
				}
				sum += v
			}
			if math.Abs(sum-1.0) > 1e-9 {
				t.Fatalf("%s: строка %s суммируется в %v, ожидалось 1.0", name, st, sum)
			}
		}
	}
}

func TestAllProfilesProduceSaneOutput(t *testing.T) {
	for _, name := range ProfileNames() {
		s := New(Config{Enabled: true, Profile: name, Seed: 42})
		if !s.Enabled() {
			t.Fatalf("профиль %q должен быть enabled", name)
		}
		for i := 0; i < 2000; i++ {
			d := s.Next(300)
			if d.TargetLen < 0 {
				t.Fatalf("%s: отрицательный TargetLen %d", name, d.TargetLen)
			}
			if d.Delay < 0 {
				t.Fatalf("%s: отрицательная задержка %v", name, d.Delay)
			}
		}
	}
}

func TestProfilesDifferStatistically(t *testing.T) {
	avgDelay := func(name string) float64 {
		s := New(Config{Enabled: true, Profile: name, Seed: 100})
		var total time.Duration
		const n = 20000
		for i := 0; i < n; i++ {
			total += s.Next(400).Delay
		}
		return float64(total.Milliseconds()) / float64(n)
	}
	web := avgDelay("web")
	bulk := avgDelay("bulk")
	if !(bulk < web) {
		t.Fatalf("ожидалось bulk(%.2fms) < web(%.2fms) по средней задержке", bulk, web)
	}
}
