package ice

import (
	"encoding/binary"
	"net"
	"testing"
)

func TestDecodeXorMappedIPv4(t *testing.T) {
	var tx [12]byte
	copy(tx[:], []byte{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
	ip := net.IPv4(192, 0, 2, 1).To4()
	port := uint16(9)
	xp := port ^ uint16(stunMagicCookie>>16)
	xi := binary.BigEndian.Uint32(ip) ^ uint32(stunMagicCookie)
	v := make([]byte, 8)
	v[1] = 1
	binary.BigEndian.PutUint16(v[2:4], xp)
	binary.BigEndian.PutUint32(v[4:8], xi)
	gotIP, gotPort := decodeXorMapped(v, tx)
	if gotIP.String() != "192.0.2.1" || gotPort != 9 {
		t.Fatalf("got %s:%d", gotIP, gotPort)
	}
}

func TestPathWeight(t *testing.T) {
	if CandidateRelay.PathWeight() >= 1.0 || CandidateUnknown.PathWeight() != 1.0 {
		t.Fatalf("unexpected weights")
	}
}
