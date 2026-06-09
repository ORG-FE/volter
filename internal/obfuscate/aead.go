package obfuscate

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	"dev.c0redev.volter/internal/dexote"
	"golang.org/x/crypto/chacha20poly1305"
)

const (
	maxFramePayload = 16384
	maxPad          = 1024
	lenHdr          = 2 + chacha20poly1305.Overhead
	maxChunk        = 2 + maxFramePayload + maxPad + chacha20poly1305.Overhead
)

type dir struct {
	mu    sync.Mutex
	data  []byte
	lenK  []byte
	nD    uint64
	nL    uint64
	poly  *dexote.Poly
	maxPd int
}

func nonce12(ctr uint64) []byte {
	var n [12]byte
	binary.LittleEndian.PutUint64(n[4:], ctr)
	return n[:]
}

type aeadConn struct {
	net.Conn
	tx   *dir
	rx   *dir
	rbuf []byte
	hook ShapeHook
}

type ShapeHook func(payloadLen int) (targetLen int, delay time.Duration)

func WrapAEAD(conn net.Conn, k *dexote.Keys, txPoly, rxPoly *dexote.Poly, padMax int) net.Conn {
	return WrapAEADShaped(conn, k, txPoly, rxPoly, padMax, nil)
}

func WrapAEADShaped(conn net.Conn, k *dexote.Keys, txPoly, rxPoly *dexote.Poly, padMax int, hook ShapeHook) net.Conn {
	if padMax > maxPad {
		padMax = maxPad
	}
	return &aeadConn{
		Conn: conn,
		tx:   &dir{data: k.TxKey, lenK: k.TxLenKey, poly: txPoly, maxPd: padMax},
		rx:   &dir{data: k.RxKey, lenK: k.RxLenKey, poly: rxPoly, maxPd: padMax},
		hook: hook,
	}
}

func sealChunk(key, nonce, pt []byte) ([]byte, error) {
	a, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, err
	}
	return a.Seal(nil, nonce, pt, nil), nil
}

func openChunk(key, nonce, ct []byte) ([]byte, error) {
	a, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, err
	}
	return a.Open(nil, nonce, ct, nil)
}

func (c *aeadConn) Write(p []byte) (int, error) {
	c.tx.mu.Lock()
	defer c.tx.mu.Unlock()
	total := 0
	for len(p) > 0 {
		n := len(p)
		if n > maxFramePayload {
			n = maxFramePayload
		}
		shapePad, delay := c.shapeFor(n)
		if delay > 0 {
			time.Sleep(delay)
		}
		if err := c.writeFrame(p[:n], shapePad); err != nil {
			return total, err
		}
		total += n
		p = p[n:]
	}
	return total, nil
}

func (c *aeadConn) shapeFor(n int) (int, time.Duration) {
	if c.hook == nil {
		return 0, 0
	}
	targetLen, delay := c.hook(n)
	pad := 0
	if targetLen > n {
		pad = targetLen - n
	}
	if pad > maxPad {
		pad = maxPad
	}
	return pad, delay
}

func (c *aeadConn) writeFrame(payload []byte, shapePad int) error {
	pad := c.tx.poly.PadLen(c.tx.maxPd) + shapePad
	if pad > maxPad {
		pad = maxPad
	}
	pt := make([]byte, 2+len(payload)+pad)
	binary.BigEndian.PutUint16(pt[:2], uint16(len(payload)))
	copy(pt[2:], payload)

	encData, err := sealChunk(c.tx.data, nonce12(c.tx.nD), pt)
	if err != nil {
		return err
	}
	c.tx.nD++

	var lp [2]byte
	binary.BigEndian.PutUint16(lp[:], uint16(len(encData)))
	encLen, err := sealChunk(c.tx.lenK, nonce12(c.tx.nL), lp[:])
	if err != nil {
		return err
	}
	c.tx.nL++

	if _, err := c.Conn.Write(encLen); err != nil {
		return err
	}
	_, err = c.Conn.Write(encData)
	return err
}

func (c *aeadConn) Read(p []byte) (int, error) {
	if len(c.rbuf) > 0 {
		n := copy(p, c.rbuf)
		c.rbuf = c.rbuf[n:]
		return n, nil
	}
	payload, err := c.readFrame()
	if err != nil {
		return 0, err
	}
	n := copy(p, payload)
	if n < len(payload) {
		c.rbuf = payload[n:]
	}
	return n, nil
}

func (c *aeadConn) readFrame() ([]byte, error) {
	c.rx.mu.Lock()
	defer c.rx.mu.Unlock()

	encLen := make([]byte, lenHdr)
	if _, err := io.ReadFull(c.Conn, encLen); err != nil {
		return nil, err
	}
	lp, err := openChunk(c.rx.lenK, nonce12(c.rx.nL), encLen)
	if err != nil {
		return nil, errFrame
	}
	c.rx.nL++
	dataLen := int(binary.BigEndian.Uint16(lp))
	if dataLen < 2+chacha20poly1305.Overhead || dataLen > maxChunk {
		return nil, errFrame
	}
	encData := make([]byte, dataLen)
	if _, err := io.ReadFull(c.Conn, encData); err != nil {
		return nil, err
	}
	pt, err := openChunk(c.rx.data, nonce12(c.rx.nD), encData)
	if err != nil {
		return nil, errFrame
	}
	c.rx.nD++
	if len(pt) < 2 {
		return nil, errFrame
	}
	real := int(binary.BigEndian.Uint16(pt[:2]))
	if 2+real > len(pt) {
		return nil, errFrame
	}
	return pt[2 : 2+real], nil
}

var errFrame = errors.New("obfuscate: bad aead frame")
