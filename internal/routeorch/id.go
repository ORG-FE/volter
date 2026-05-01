package routeorch

import (
	"crypto/rand"
	"encoding/hex"
)

func randomCorrelationID() string {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "corr-" + hex.EncodeToString(b[:])
	}
	return hex.EncodeToString(b[:])
}
