package shaper

import "testing"

func TestVecPRNGNext(t *testing.T) {
	p := newPRNG(12345)
	want := []uint64{
		0x22118258a9d111a0,
		0x346edce5f713f8ed,
		0x1e9a57bc80e6721d,
		0x2d160e7e5c3f42ca,
		0x81c2e6dc980d78eb,
	}
	for i, w := range want {
		if got := p.next(); got != w {
			t.Errorf("next()[%d] = 0x%016x, want 0x%016x", i, got, w)
		}
	}
}

func TestVecPRNGFloat64(t *testing.T) {
	p := newPRNG(12345)
	want := []float64{
		0.1330796686614273,
		0.20481663336165912,
		0.11954258300911547,
		0.17611780724496118,
		0.506880215507456,
	}
	for i, w := range want {
		if got := p.float64(); got != w {
			t.Errorf("float64()[%d] = %v, want %v", i, got, w)
		}
	}
}

func TestVecHashSeed(t *testing.T) {
	if got := hashSeed("web", 100, 200); got != 0x5b37348f357dadff {
		t.Errorf("hashSeed = 0x%016x, want 0x5b37348f357dadff", got)
	}
}
