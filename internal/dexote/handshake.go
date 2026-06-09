package dexote

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"time"

	"golang.org/x/crypto/chacha20poly1305"
)

const (
	maxCtLen     = 8192
	maxHSPad     = 512
	replayWindow = 90
)

var (
	ErrBadMAC     = errors.New("dexote: bad mac")
	ErrBadVersion = errors.New("dexote: bad version")
	ErrReplay     = errors.New("dexote: stale or replayed hello")
	ErrDecrypt    = errors.New("dexote: decrypt failed")
)

type Keys struct {
	TxKey    []byte
	RxKey    []byte
	TxLenKey []byte
	RxLenKey []byte
	Secret   []byte
}

type ClientHelloPayload struct {
	Role  byte
	Token string
	Opts  []byte
}

func sessionKeys(secret, cliNonce, srvNonce []byte, clientSide bool) *Keys {
	salt := append(append([]byte{}, cliNonce...), srvNonce...)
	c2s := hkdfKey(secret, salt, infoSession+"|c2s|data", keyLen)
	s2c := hkdfKey(secret, salt, infoSession+"|s2c|data", keyLen)
	c2sLen := hkdfKey(secret, salt, infoSession+"|c2s|len", keyLen)
	s2cLen := hkdfKey(secret, salt, infoSession+"|s2c|len", keyLen)
	k := &Keys{Secret: hkdfKey(secret, salt, infoSession+"|root", keyLen)}
	if clientSide {
		k.TxKey, k.RxKey, k.TxLenKey, k.RxLenKey = c2s, s2c, c2sLen, s2cLen
	} else {
		k.TxKey, k.RxKey, k.TxLenKey, k.RxLenKey = s2c, c2s, s2cLen, c2sLen
	}
	return k
}

func seal(key, ad, plaintext []byte) ([]byte, error) {
	a, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, a.NonceSize())
	return a.Seal(nil, nonce, plaintext, ad), nil
}

func open(key, ad, ct []byte) ([]byte, error) {
	a, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, a.NonceSize())
	pt, err := a.Open(nil, nonce, ct, ad)
	if err != nil {
		return nil, ErrDecrypt
	}
	return pt, nil
}

func writeU16(w io.Writer, v int) error {
	var b [2]byte
	binary.BigEndian.PutUint16(b[:], uint16(v))
	_, err := w.Write(b[:])
	return err
}

func readU16(r io.Reader) (int, error) {
	var b [2]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0, err
	}
	return int(binary.BigEndian.Uint16(b[:])), nil
}

func randBytes(n int) []byte {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return b
}

func ClientHandshake(conn io.ReadWriter, serverPub []byte, slot int64, p ClientHelloPayload) (*Keys, []byte, error) {
	if len(serverPub) != keyLen {
		return nil, nil, errors.New("dexote: bad server pubkey")
	}
	scalar := randBytes(keyLen)
	ePub, err := pubFromScalar(scalar)
	if err != nil {
		return nil, nil, err
	}
	masked := maskPub(ePub, serverPub, slot)
	m := mac(serverPub, append(append([]byte{}, masked...), slotBytes(slot)...))

	ss1, err := x25519(scalar, serverPub)
	if err != nil {
		return nil, nil, err
	}
	k1 := hkdfKey(ss1, slotBytes(slot), infoC2S, keyLen)

	cliNonce := randBytes(nonceLen)
	pad := randBytes(NewPoly(k1, slot, "hspad").PadLen(maxHSPad))
	pt := buildClientPlaintext(p, cliNonce, time.Now().Unix(), pad)
	ct, err := seal(k1, masked, pt)
	if err != nil {
		return nil, nil, err
	}
	if len(ct) > maxCtLen {
		return nil, nil, errors.New("dexote: hello too large")
	}

	if _, err := conn.Write(masked); err != nil {
		return nil, nil, err
	}
	if _, err := conn.Write(m); err != nil {
		return nil, nil, err
	}
	if err := writeU16(conn, len(ct)); err != nil {
		return nil, nil, err
	}
	if _, err := conn.Write(ct); err != nil {
		return nil, nil, err
	}

	maskedS := make([]byte, keyLen)
	if _, err := io.ReadFull(conn, maskedS); err != nil {
		return nil, nil, err
	}
	sPub := unmaskPub(maskedS, serverPub, slot)
	ss3, err := x25519(scalar, sPub)
	if err != nil {
		return nil, nil, err
	}
	k2 := hkdfKey(ss3, slotBytes(slot), infoS2C, keyLen)
	sctLen, err := readU16(conn)
	if err != nil {
		return nil, nil, err
	}
	if sctLen > maxCtLen {
		return nil, nil, errors.New("dexote: server hello too large")
	}
	sct := make([]byte, sctLen)
	if _, err := io.ReadFull(conn, sct); err != nil {
		return nil, nil, err
	}
	spt, err := open(k2, maskedS, sct)
	if err != nil {
		return nil, nil, err
	}
	srvNonce, caps, err := parseServerPlaintext(spt)
	if err != nil {
		return nil, nil, err
	}

	secret := deriveSecret(ss3, ss1, cliNonce, srvNonce)
	return sessionKeys(secret, cliNonce, srvNonce, true), caps, nil
}

func ServerHandshake(conn io.ReadWriter, serverScalar, serverPub []byte, slot int64, capsBytes []byte, seen ReplayCache) (*Keys, *ClientHelloPayload, error) {
	masked := make([]byte, keyLen)
	if _, err := io.ReadFull(conn, masked); err != nil {
		return nil, nil, err
	}
	gotMAC := make([]byte, macLen)
	if _, err := io.ReadFull(conn, gotMAC); err != nil {
		return nil, nil, err
	}
	wantMAC := mac(serverPub, append(append([]byte{}, masked...), slotBytes(slot)...))
	if !macEqual(gotMAC, wantMAC) {
		return nil, nil, ErrBadMAC
	}
	ePub := unmaskPub(masked, serverPub, slot)
	ss1, err := x25519(serverScalar, ePub)
	if err != nil {
		return nil, nil, err
	}
	k1 := hkdfKey(ss1, slotBytes(slot), infoC2S, keyLen)
	ctLen, err := readU16(conn)
	if err != nil {
		return nil, nil, err
	}
	if ctLen > maxCtLen {
		return nil, nil, errors.New("dexote: hello too large")
	}
	ct := make([]byte, ctLen)
	if _, err := io.ReadFull(conn, ct); err != nil {
		return nil, nil, err
	}
	pt, err := open(k1, masked, ct)
	if err != nil {
		return nil, nil, err
	}
	payload, cliNonce, tsSec, err := parseClientPlaintext(pt)
	if err != nil {
		return nil, nil, err
	}
	now := time.Now().Unix()
	if tsSec < now-replayWindow || tsSec > now+replayWindow {
		return nil, nil, ErrReplay
	}
	if seen != nil && !seen.Add(cliNonce, tsSec) {
		return nil, nil, ErrReplay
	}

	sScalar := randBytes(keyLen)
	sEPub, err := pubFromScalar(sScalar)
	if err != nil {
		return nil, nil, err
	}
	maskedS := maskPub(sEPub, serverPub, slot)
	ss3, err := x25519(sScalar, ePub)
	if err != nil {
		return nil, nil, err
	}
	k2 := hkdfKey(ss3, slotBytes(slot), infoS2C, keyLen)
	srvNonce := randBytes(nonceLen)
	pad := randBytes(NewPoly(k2, slot, "hspad").PadLen(maxHSPad))
	spt := buildServerPlaintext(srvNonce, capsBytes, pad)
	sct, err := seal(k2, maskedS, spt)
	if err != nil {
		return nil, nil, err
	}
	if _, err := conn.Write(maskedS); err != nil {
		return nil, nil, err
	}
	if err := writeU16(conn, len(sct)); err != nil {
		return nil, nil, err
	}
	if _, err := conn.Write(sct); err != nil {
		return nil, nil, err
	}

	secret := deriveSecret(ss3, ss1, cliNonce, srvNonce)
	return sessionKeys(secret, cliNonce, srvNonce, false), payload, nil
}

func deriveSecret(ss3, ss1, cliNonce, srvNonce []byte) []byte {
	ikm := append(append([]byte{}, ss3...), ss1...)
	salt := append(append([]byte{}, cliNonce...), srvNonce...)
	return hkdfKey(ikm, salt, infoSession, keyLen)
}

func buildClientPlaintext(p ClientHelloPayload, cliNonce []byte, tsSec int64, pad []byte) []byte {
	tok := []byte(p.Token)
	var buf []byte
	buf = append(buf, Version, p.Role)
	buf = append(buf, cliNonce...)
	var ts [8]byte
	binary.BigEndian.PutUint64(ts[:], uint64(tsSec))
	buf = append(buf, ts[:]...)
	buf = appendU16(buf, len(tok))
	buf = append(buf, tok...)
	buf = appendU16(buf, len(p.Opts))
	buf = append(buf, p.Opts...)
	buf = appendU16(buf, len(pad))
	buf = append(buf, pad...)
	return buf
}

func parseClientPlaintext(pt []byte) (*ClientHelloPayload, []byte, int64, error) {
	r := newCursor(pt)
	ver := r.u8()
	role := r.u8()
	if ver != Version {
		return nil, nil, 0, ErrBadVersion
	}
	cliNonce := r.n(nonceLen)
	tsSec := int64(binary.BigEndian.Uint64(r.n(8)))
	tok := r.lenPrefixed()
	opts := r.lenPrefixed()
	_ = r.lenPrefixed()
	if r.err != nil {
		return nil, nil, 0, r.err
	}
	return &ClientHelloPayload{Role: role, Token: string(tok), Opts: opts}, cliNonce, tsSec, nil
}

func buildServerPlaintext(srvNonce, caps, pad []byte) []byte {
	var buf []byte
	buf = append(buf, srvNonce...)
	buf = appendU16(buf, len(caps))
	buf = append(buf, caps...)
	buf = appendU16(buf, len(pad))
	buf = append(buf, pad...)
	return buf
}

func parseServerPlaintext(pt []byte) (srvNonce, caps []byte, err error) {
	r := newCursor(pt)
	srvNonce = r.n(nonceLen)
	caps = r.lenPrefixed()
	_ = r.lenPrefixed()
	if r.err != nil {
		return nil, nil, r.err
	}
	return srvNonce, caps, nil
}

func appendU16(b []byte, v int) []byte {
	return append(b, byte(v>>8), byte(v))
}

type cursor struct {
	b   []byte
	off int
	err error
}

func newCursor(b []byte) *cursor { return &cursor{b: b} }

func (c *cursor) u8() byte {
	if c.err != nil || c.off >= len(c.b) {
		c.err = fmt.Errorf("dexote: short plaintext")
		return 0
	}
	v := c.b[c.off]
	c.off++
	return v
}

func (c *cursor) n(n int) []byte {
	if c.err != nil || c.off+n > len(c.b) {
		c.err = fmt.Errorf("dexote: short plaintext")
		return make([]byte, n)
	}
	v := c.b[c.off : c.off+n]
	c.off += n
	return v
}

func (c *cursor) lenPrefixed() []byte {
	if c.err != nil || c.off+2 > len(c.b) {
		c.err = fmt.Errorf("dexote: short plaintext")
		return nil
	}
	n := int(binary.BigEndian.Uint16(c.b[c.off : c.off+2]))
	c.off += 2
	return c.n(n)
}
