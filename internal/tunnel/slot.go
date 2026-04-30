package tunnel

import (
	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/protocol"
)

func SlotForProtection(prot *config.ProtectionOptions) int64 {
	c := 0
	if prot != nil {
		c = prot.ChurnEpochSec
	}
	return protocol.EffectiveSlot(c)
}
