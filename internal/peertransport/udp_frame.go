package peertransport

import (
	"encoding/binary"
	"errors"
	"io"
)

const peerUDPMagic = "VP01"

const MaxPeerUDPBody = 65507

func WritePeerUDPFrame(w io.Writer, payload []byte) error {
	if len(payload) > MaxPeerUDPBody {
		payload = payload[:MaxPeerUDPBody]
	}
	var hdr [8]byte
	copy(hdr[:4], peerUDPMagic)
	binary.BigEndian.PutUint32(hdr[4:8], uint32(len(payload)))
	if _, err := w.Write(hdr[:]); err != nil {
		return err
	}
	_, err := w.Write(payload)
	return err
}

func ReadPeerUDPFrame(r io.Reader, buf []byte) (int, error) {
	var hdr [8]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return 0, err
	}
	if string(hdr[:4]) != peerUDPMagic {
		return 0, errors.New("peerudp: bad magic")
	}
	n := binary.BigEndian.Uint32(hdr[4:8])
	if n > MaxPeerUDPBody {
		return 0, errors.New("peerudp: length")
	}
	if int(n) > len(buf) {
		return 0, errors.New("peerudp: short buf")
	}
	if _, err := io.ReadFull(r, buf[:n]); err != nil {
		return 0, err
	}
	return int(n), nil
}
