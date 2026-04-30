package tunnel

import (
	"sync"
	"time"
)

type ByteBucket struct {
	mu    sync.Mutex
	rate  float64
	burst float64
	tok   float64
	last  time.Time
}

func NewByteBucket(maxKbps int) *ByteBucket {
	if maxKbps <= 0 {
		return nil
	}
	r := float64(maxKbps) * 1000.0 / 8.0
	return &ByteBucket{rate: r, burst: r * 2, tok: r * 2, last: time.Now()}
}

func (b *ByteBucket) WaitTake(n int) {
	if b == nil || n <= 0 {
		return
	}
	need := float64(n)
	for {
		var sleep time.Duration
		b.mu.Lock()
		now := time.Now()
		dt := now.Sub(b.last).Seconds()
		if dt > 0 {
			b.tok += dt * b.rate
			if b.tok > b.burst {
				b.tok = b.burst
			}
			b.last = now
		}
		if b.tok >= need {
			b.tok -= need
			b.mu.Unlock()
			return
		}
		short := need - b.tok
		sleep = time.Duration(short / b.rate * float64(time.Second))
		if sleep < time.Millisecond {
			sleep = time.Millisecond
		}
		b.mu.Unlock()
		time.Sleep(sleep)
	}
}
