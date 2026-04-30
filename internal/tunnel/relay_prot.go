package tunnel

import (
	"fmt"
	"strings"
	"time"

	"dev.c0redev.volter/internal/config"
)

func RelayProtForPeerHop(base *config.ProtectionOptions, relay *config.RelayOptions) *config.ProtectionOptions {
	if relay == nil && base == nil {
		return nil
	}
	var cp config.ProtectionOptions
	if base != nil {
		cp = *base
	}
	if relay != nil {
		if s := strings.TrimSpace(relay.PeerID); s != "" {
			cp.PeerID = s
		}
		if relay.BudgetKbps > 0 && cp.RelayBudgetKbps <= 0 {
			cp.RelayBudgetKbps = relay.BudgetKbps
		}
	}
	if cp.RelayHop <= 0 {
		cp.RelayHop = 1
	}
	if cp.RelayMaxHop <= 0 {
		cp.RelayMaxHop = 3
	}
	if strings.TrimSpace(cp.RouteID) == "" {
		cp.RouteID = fmt.Sprintf("r-%d", time.Now().UnixNano())
	}
	if cp.HopIndex <= 0 {
		cp.HopIndex = cp.RelayHop
	}
	return &cp
}
