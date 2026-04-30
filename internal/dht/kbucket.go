package dht

import "math/bits"

const bucketK = 20

func xorLeadingBitIdx(xd [32]byte) int {
	for i := 0; i < 32; i++ {
		if xd[i] != 0 {
			return i*8 + bits.LeadingZeros8(xd[i])
		}
	}
	return 255
}
