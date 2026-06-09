package shaper

import "math"

type prng struct {
	s uint64
}

func newPRNG(seed uint64) *prng {
	if seed == 0 {
		seed = 0x9e3779b97f4a7c15
	}
	return &prng{s: seed}
}

func (p *prng) next() uint64 {
	p.s += 0x9e3779b97f4a7c15
	z := p.s
	z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9
	z = (z ^ (z >> 27)) * 0x94d049bb133111eb
	return z ^ (z >> 31)
}

func (p *prng) float64() float64 {

	return float64(p.next()>>11) / float64(1<<53)
}

func (p *prng) normFloat64() float64 {
	u1 := p.float64()
	if u1 < 1e-12 {
		u1 = 1e-12
	}
	u2 := p.float64()
	return math.Sqrt(-2*math.Log(u1)) * math.Cos(2*math.Pi*u2)
}

func (p *prng) sample(d dist) float64 {
	if d.max <= d.min {
		return d.min
	}
	var v float64
	switch d.kind {
	case distUniform:
		v = d.min + p.float64()*(d.max-d.min)
	case distExp:
		u := p.float64()
		if u < 1e-12 {
			u = 1e-12
		}
		mean := d.mean
		if mean <= 0 {
			mean = (d.max - d.min) / 2
		}
		v = d.min + (-math.Log(u))*mean
	case distNormal:
		std := d.std
		if std <= 0 {
			std = (d.max - d.min) / 6
		}
		v = d.mean + p.normFloat64()*std
	default:
		v = d.min
	}
	if v < d.min {
		v = d.min
	}
	if v > d.max {
		v = d.max
	}
	return v
}
