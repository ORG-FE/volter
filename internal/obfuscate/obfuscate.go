package obfuscate

import (
	"crypto/sha256"
	"net"
	"sync"
	"sync/atomic"
)

func keyFromToken(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}

type xorConn struct {
	net.Conn
	key  []byte
	rPos atomic.Int64
	wPos atomic.Int64
}

func (c *xorConn) Read(p []byte) (n int, err error) {
	n, err = c.Conn.Read(p)
	xorBytes(p[:n], c.key, &c.rPos)
	return n, err
}

var writeBufPool = sync.Pool{
	New: func() any { b := make([]byte, 256*1024); return &b },
}

func (c *xorConn) Write(p []byte) (n int, err error) {
	buf := writeBufPool.Get().(*[]byte)
	defer writeBufPool.Put(buf)
	need := len(p)
	if cap(*buf) < need {
		*buf = make([]byte, need)
	}
	b := (*buf)[:need]
	copy(b, p)
	xorBytes(b, c.key, &c.wPos)
	n, err = c.Conn.Write(b)
	if n < need {
		c.wPos.Add(-int64(need - n))
	}
	return n, err
}

func xorBytes(b, key []byte, pos *atomic.Int64) {
	kl := len(key)
	p0 := int(pos.Load())
	if kl == 32 {
		for i := range b {
			b[i] ^= key[p0&31]
			p0++
		}
	} else if kl > 0 {
		for i := range b {
			b[i] ^= key[p0%kl]
			p0++
		}
	}
	pos.Store(int64(p0))
}

func WrapConn(conn net.Conn, token string) net.Conn {
	return &xorConn{Conn: conn, key: keyFromToken(token)}
}
