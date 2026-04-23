package protocol

import (
	"crypto/rand"
	"crypto/sha256"
	"io"
	"math/big"
	"strconv"
	"strings"
)

const (
	PreambleTLSRecord  = "tls_record"
	PreambleTLSCHShape = "tls_ch_shape"
	PreambleSMB1Shape  = "smb1_shape"
	PreambleMcFrame    = "mc_frame"
	PreambleRotate       = "rotate"
)

func ResolvePreambleKind(junkStyle, preambleProfile string, preambleRotate, obfuscationEnhanced bool, slot int64, token string, probeObfs byte) string {
	pp := strings.ToLower(strings.TrimSpace(preambleProfile))
	if pp == PreambleRotate || (obfuscationEnhanced && preambleRotate) {
		return kindRotate(slot, token, probeObfs)
	}
	switch pp {
	case PreambleTLSRecord, PreambleTLSCHShape, PreambleSMB1Shape, PreambleMcFrame:
		return pp
	}
	if strings.EqualFold(junkStyle, "tls") {
		return PreambleTLSRecord
	}
	return ""
}

func kindRotate(slot int64, token string, probeObfs byte) string {
	base := int(probeObfs)
	if probeObfs == 0 {
		sum := sha256.Sum256([]byte(strconv.FormatInt(slot, 10) + "|" + token))
		base = int(sum[0])
	}
	switch (base + int(slot)) % 4 {
	case 0:
		return PreambleTLSRecord
	case 1:
		return PreambleTLSCHShape
	case 2:
		return PreambleSMB1Shape
	default:
		return PreambleMcFrame
	}
}

func WritePreamble(w io.Writer, kind string, count, min, max int, flushPolicy string, flush func()) error {
	var fc func()
	if flush != nil && strings.EqualFold(flushPolicy, "perChunk") {
		fc = flush
	}
	switch strings.ToLower(strings.TrimSpace(kind)) {
	case PreambleTLSRecord:
		return WriteTLSLikeJunk(w, count, min, max, fc)
	case PreambleTLSCHShape:
		return writeTLSClientHelloShapeJunk(w, count, min, max, fc)
	case PreambleSMB1Shape:
		return writeSMB1ShapeJunk(w, count, min, max, fc)
	case PreambleMcFrame:
		return writeMinecraftFrameJunk(w, count, min, max, fc)
	default:
		return WriteJunk(w, count, min, max, fc)
	}
}

func writeTLSClientHelloShapeJunk(w io.Writer, count, minLen, maxLen int, flushAfterChunk func()) error {
	if count <= 0 || minLen <= 0 || maxLen < minLen {
		return nil
	}
	if maxLen > 1024 {
		maxLen = 1024
	}
	if minLen > maxLen {
		minLen = maxLen
	}
	payload := make([]byte, maxLen)
	for i := 0; i < count; i++ {
		n, _ := rand.Int(rand.Reader, big.NewInt(int64(maxLen-minLen+1)))
		payloadLen := minLen + int(n.Int64())
		if payloadLen < 11 {
			payloadLen = 11
		}
		header := [5]byte{0x16, 0x03, 0x03, byte((payloadLen - 5) >> 8), byte(payloadLen - 5)}
		if _, err := w.Write(header[:]); err != nil {
			return err
		}
		inner := payloadLen - 5
		if inner > len(payload) {
			inner = len(payload)
		}
		if _, err := rand.Read(payload[:inner]); err != nil {
			return err
		}
		if _, err := w.Write(payload[:inner]); err != nil {
			return err
		}
		if flushAfterChunk != nil {
			flushAfterChunk()
		}
	}
	return nil
}

func writeSMB1ShapeJunk(w io.Writer, count, minLen, maxLen int, flushAfterChunk func()) error {
	if count <= 0 || minLen <= 0 || maxLen < minLen {
		return nil
	}
	if maxLen > 1024 {
		maxLen = 1024
	}
	if minLen > maxLen {
		minLen = maxLen
	}
	prefix := []byte{0x00, 0x00, 0x00, 0x54, 0xff, 0x53, 0x4d, 0x42, 0x72, 0x00, 0x00, 0x00, 0x00}
	payload := make([]byte, maxLen)
	for i := 0; i < count; i++ {
		n, _ := rand.Int(rand.Reader, big.NewInt(int64(maxLen-minLen+1)))
		total := minLen + int(n.Int64())
		extra := total - len(prefix)
		if extra < 8 {
			extra = 8
		}
		if _, err := w.Write(prefix); err != nil {
			return err
		}
		if extra > len(payload) {
			extra = len(payload)
		}
		if _, err := rand.Read(payload[:extra]); err != nil {
			return err
		}
		if _, err := w.Write(payload[:extra]); err != nil {
			return err
		}
		if flushAfterChunk != nil {
			flushAfterChunk()
		}
	}
	return nil
}

func writeVarInt(w io.Writer, v uint32) error {
	for {
		b := byte(v & 0x7f)
		v >>= 7
		if v != 0 {
			b |= 0x80
		}
		if _, err := w.Write([]byte{b}); err != nil {
			return err
		}
		if v == 0 {
			return nil
		}
	}
}

func writeMinecraftFrameJunk(w io.Writer, count, minLen, maxLen int, flushAfterChunk func()) error {
	if count <= 0 || minLen <= 0 || maxLen < minLen {
		return nil
	}
	if maxLen > 1024 {
		maxLen = 1024
	}
	if minLen > maxLen {
		minLen = maxLen
	}
	payload := make([]byte, maxLen)
	for i := 0; i < count; i++ {
		n, _ := rand.Int(rand.Reader, big.NewInt(int64(maxLen-minLen+1)))
		inner := minLen + int(n.Int64())
		if inner < 16 {
			inner = 16
		}
		if inner > len(payload) {
			inner = len(payload)
		}
		if err := writeVarInt(w, uint32(inner)); err != nil {
			return err
		}
		if _, err := rand.Read(payload[:inner]); err != nil {
			return err
		}
		if _, err := w.Write(payload[:inner]); err != nil {
			return err
		}
		if flushAfterChunk != nil {
			flushAfterChunk()
		}
	}
	return nil
}
