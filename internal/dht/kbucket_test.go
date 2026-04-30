package dht

import "testing"

func TestXorLeadingBitIdxMonotonic(t *testing.T) {
	var a, b [32]byte
	a[0] = 0x80
	b[0] = 0x40
	if xorLeadingBitIdx(a) >= xorLeadingBitIdx(b) {
		t.Fatal("msb order")
	}
}
