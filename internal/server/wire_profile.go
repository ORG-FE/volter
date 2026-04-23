package server

import (
	"sync/atomic"
	"time"
)

type WireProfileStyle byte

const (
	StylePlain WireProfileStyle = iota
	StyleHTTP1Like
	StyleHTTP2Preface
	StyleTLSRecordJunk
)

type WireProfileRotator struct {
	rotate time.Duration
	epoch  atomic.Uint64
}

func NewWireProfileRotator(rotateSec int) *WireProfileRotator {
	r := &WireProfileRotator{}
	if rotateSec < 60 {
		rotateSec = 300
	}
	r.rotate = time.Duration(rotateSec) * time.Second
	return r
}

func (w *WireProfileRotator) current() (WireProfileStyle, byte) {
	if w.rotate <= 0 {
		return StylePlain, 0
	}
	t := time.Now().Unix() / int64(w.rotate.Seconds())
	w.epoch.Store(uint64(t))
	switch int(t) % 4 {
	case 0:
		return StyleHTTP1Like, byte(t % 7)
	case 1:
		return StyleHTTP2Preface, byte(t % 5)
	case 2:
		return StyleTLSRecordJunk, byte(t % 11)
	default:
		return StylePlain, 0
	}
}
