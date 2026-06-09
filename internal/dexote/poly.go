package dexote

import (
	"encoding/binary"

	"golang.org/x/crypto/chacha20"
)

type Poly struct {
	cipher *chacha20.Cipher
	zero   []byte
}

func NewPoly(secret []byte, slot int64, info string) *Poly {
	key := hkdfKey(secret, slotBytes(slot), "dexote-poly-"+info, 32)
	nonce := make([]byte, chacha20.NonceSize)
	c, err := chacha20.NewUnauthenticatedCipher(key, nonce)
	if err != nil {
		panic("dexote: poly cipher: " + err.Error())
	}
	return &Poly{cipher: c, zero: make([]byte, 8)}
}

func (p *Poly) next64() uint64 {
	buf := make([]byte, 8)
	p.cipher.XORKeyStream(buf, p.zero)
	return binary.BigEndian.Uint64(buf)
}

func (p *Poly) IntRange(min, max int) int {
	if max <= min {
		return min
	}
	span := uint64(max-min) + 1
	return min + int(p.next64()%span)
}

func (p *Poly) PadLen(maxPad int) int {
	if maxPad <= 0 {
		return 0
	}
	return p.IntRange(0, maxPad)
}

func (p *Poly) JunkPlan(maxCount, minSize, maxSize int) []int {
	n := p.IntRange(0, maxCount)
	sizes := make([]int, n)
	for i := range sizes {
		sizes[i] = p.IntRange(minSize, maxSize)
	}
	return sizes
}
