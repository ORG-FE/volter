package peertransport

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
)

const vp02Magic = "VP02"

const vp02Header = 12

const MaxVP02ChunkPayload = 1200

func WriteVP02Message(c *net.UDPConn, p []byte) error {
	return writeVP02Message(p, func(b []byte) error {
		_, err := c.Write(b)
		return err
	})
}

func WriteVP02MessageTo(c *net.UDPConn, addr *net.UDPAddr, p []byte) error {
	return writeVP02Message(p, func(b []byte) error {
		_, err := c.WriteToUDP(b, addr)
		return err
	})
}

type UDPFrameWriter interface {
	WriteTo([]byte, *net.UDPAddr) (int, error)
}

func WriteVP02MessageToPeer(w UDPFrameWriter, addr *net.UDPAddr, p []byte) error {
	return writeVP02Message(p, func(b []byte) error {
		_, err := w.WriteTo(b, addr)
		return err
	})
}

func writeVP02Message(p []byte, send func([]byte) error) error {
	if len(p) == 0 {
		return errors.New("peerudp: empty payload")
	}
	const chunk = MaxVP02ChunkPayload
	total := (len(p) + chunk - 1) / chunk
	if total > 65535 {
		return errors.New("peerudp: payload too large")
	}
	off := 0
	for seq := 0; seq < total; seq++ {
		n := chunk
		if off+n > len(p) {
			n = len(p) - off
		}
		h := make([]byte, vp02Header+n)
		copy(h[:4], vp02Magic)
		binary.BigEndian.PutUint16(h[4:6], uint16(total))
		binary.BigEndian.PutUint16(h[6:8], uint16(seq))
		binary.BigEndian.PutUint32(h[8:12], uint32(n))
		copy(h[12:], p[off:off+n])
		if err := send(h); err != nil {
			return err
		}
		off += n
	}
	return nil
}

type Assembler struct {
	total int
	parts [][]byte
	got   int
}

func (a *Assembler) reset() {
	a.total = 0
	a.parts = nil
	a.got = 0
}

func (a *Assembler) Feed(pkt []byte) ([]byte, bool) {
	if len(pkt) < vp02Header {
		a.reset()
		return nil, false
	}
	if string(pkt[:4]) != vp02Magic {
		a.reset()
		return nil, false
	}
	total := int(binary.BigEndian.Uint16(pkt[4:6]))
	seq := int(binary.BigEndian.Uint16(pkt[6:8]))
	plen := int(binary.BigEndian.Uint32(pkt[8:12]))
	if plen < 0 || plen > MaxVP02ChunkPayload || len(pkt) < vp02Header+plen {
		a.reset()
		return nil, false
	}
	payload := pkt[vp02Header : vp02Header+plen]
	if total < 1 || seq < 0 || seq >= total {
		a.reset()
		return nil, false
	}
	if a.parts == nil {
		a.reset()
		a.total = total
		a.parts = make([][]byte, total)
	}
	if a.total != total || a.parts == nil {
		a.reset()
		return nil, false
	}
	if len(a.parts[seq]) != 0 {
		a.reset()
		return nil, false
	}
	a.parts[seq] = append([]byte(nil), payload...)
	a.got++
	if a.got < a.total {
		return nil, false
	}
	var out []byte
	for _, b := range a.parts {
		out = append(out, b...)
	}
	a.reset()
	return out, true
}

func ReadVP02Message(c *net.UDPConn, pktBuf []byte, asm *Assembler) ([]byte, error) {
	if asm == nil {
		return nil, errors.New("peerudp: nil assembler")
	}
	for {
		n, err := c.Read(pktBuf)
		if err != nil {
			return nil, err
		}
		msg, ok := asm.Feed(pktBuf[:n])
		if ok {
			return msg, nil
		}
	}
}

func WriteVP02Frame(w io.Writer, total, seq uint16, payload []byte) error {
	if len(payload) > MaxVP02ChunkPayload {
		return errors.New("peerudp: chunk too large")
	}
	var hdr [vp02Header]byte
	copy(hdr[:4], vp02Magic)
	binary.BigEndian.PutUint16(hdr[4:6], total)
	binary.BigEndian.PutUint16(hdr[6:8], seq)
	binary.BigEndian.PutUint32(hdr[8:12], uint32(len(payload)))
	if _, err := w.Write(hdr[:]); err != nil {
		return err
	}
	_, err := w.Write(payload)
	return err
}
