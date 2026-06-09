package dexote

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"hash"
	"io"

	"golang.org/x/crypto/blake2s"
	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"
)

const (
	Version = 1

	keyLen   = 32
	macLen   = 16
	nonceLen = 16
	tagLen   = chacha20poly1305.Overhead

	infoMask    = "dexote-mask-v1"
	infoC2S     = "dexote-c2s-v1"
	infoS2C     = "dexote-s2c-v1"
	infoSession = "dexote-session-v1"
)

func newSHA256() hash.Hash { return sha256.New() }

func slotBytes(slot int64) []byte {
	b := make([]byte, 8)
	binary.BigEndian.PutUint64(b, uint64(slot))
	return b
}

func x25519(scalar, point []byte) ([]byte, error) {
	return curve25519.X25519(scalar, point)
}

func pubFromScalar(scalar []byte) ([]byte, error) {
	return curve25519.X25519(scalar, curve25519.Basepoint)
}

func hkdfKey(ikm, salt []byte, info string, n int) []byte {
	r := hkdf.New(newSHA256, ikm, salt, []byte(info))
	out := make([]byte, n)
	_, _ = io.ReadFull(r, out)
	return out
}

func maskStream(serverPub []byte, slot int64) []byte {
	return hkdfKey(serverPub, slotBytes(slot), infoMask, keyLen)
}

func maskPub(pub, serverPub []byte, slot int64) []byte {
	ks := maskStream(serverPub, slot)
	out := make([]byte, keyLen)
	for i := 0; i < keyLen; i++ {
		out[i] = pub[i] ^ ks[i]
	}
	return out
}

func unmaskPub(masked, serverPub []byte, slot int64) []byte {
	return maskPub(masked, serverPub, slot)
}

func mac(serverPub, data []byte) []byte {
	h, _ := blake2s.New128(serverPub[:keyLen])
	h.Write(data)
	return h.Sum(nil)[:macLen]
}

func macEqual(a, b []byte) bool {
	return subtle.ConstantTimeCompare(a, b) == 1
}
