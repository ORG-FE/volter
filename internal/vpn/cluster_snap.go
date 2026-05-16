package vpn

import (
	"sync/atomic"
	"time"
)

type clusterCached struct {
	body string
	at   time.Time
}

func clusterStore(v *atomic.Value, body string) {
	v.Store(&clusterCached{body: body, at: time.Now()})
}

func clusterLoad(v *atomic.Value, maxStale time.Duration) (body string, stale bool) {
	x := v.Load()
	if x == nil {
		return "", false
	}
	c := x.(*clusterCached)
	if maxStale > 0 && time.Since(c.at) > maxStale {
		return "", true
	}
	return c.body, false
}
