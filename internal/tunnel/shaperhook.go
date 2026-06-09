package tunnel

import (
	"time"

	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/obfuscate"
	"dev.c0redev.volter/internal/shaper"
)

func buildAEADShapeHook(prot *config.ProtectionOptions, slot int64) obfuscate.ShapeHook {
	if prot == nil || !prot.ShaperEnabled {
		return nil
	}
	sh := shaper.New(shaper.Config{
		Enabled:        true,
		Profile:        prot.ShaperProfile,
		MaxOverheadPct: prot.ShaperMaxOverheadPct,
		MaxDelayMs:     prot.ShaperMaxDelayMs,
		Seed:           uint64(slot),
	})
	if !sh.Enabled() {
		return nil
	}

	return func(payloadLen int) (int, time.Duration) {
		d := sh.Next(payloadLen)
		return d.TargetLen, d.Delay
	}
}
