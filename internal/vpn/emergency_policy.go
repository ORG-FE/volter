package vpn

import (
	"context"
	"strings"
	"sync/atomic"
	"time"

	"dev.c0redev.volter/internal/clientlog"
	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/discovery"
	"dev.c0redev.volter/internal/telemetry"
)

var emergencyDisablePeerRelay atomic.Bool

func emergencyPeerRelayBlocked() bool {
	return emergencyDisablePeerRelay.Load()
}

func emergencyPolicyConfigured(relay *config.RelayOptions) bool {
	if relay == nil {
		return false
	}
	return strings.TrimSpace(relay.EmergencyPolicyURL) != "" && strings.TrimSpace(relay.EmergencyPolicyPubKey) != ""
}

func applyEmergencyPolicyOnce(ctx context.Context, relay *config.RelayOptions) {
	if relay == nil {
		return
	}
	url := strings.TrimSpace(relay.EmergencyPolicyURL)
	pkb := strings.TrimSpace(relay.EmergencyPolicyPubKey)
	if url == "" || pkb == "" {
		return
	}
	pub, err := discovery.DecodeEd25519PublicKey(pkb)
	if err != nil {
		clientlog.Warn("vpn: emergency policy pubkey: %v", err)
		return
	}
	raw, err := discovery.FetchBootstrapBody(ctx, url)
	if err != nil {
		clientlog.Warn("vpn: emergency policy fetch: %v", err)
		return
	}
	pol, err := discovery.ParseSignedEmergencyPolicyJSON(string(raw))
	if err != nil {
		clientlog.Warn("vpn: emergency policy json: %v", err)
		return
	}
	if err := pol.Verify(pub, time.Now()); err != nil {
		clientlog.Warn("vpn: emergency policy verify: %v", err)
		emergencyDisablePeerRelay.Store(false)
		return
	}
	emergencyDisablePeerRelay.Store(pol.DisablePeerRelay)
	if pol.DisablePeerRelay {
		clientlog.Info("vpn: emergency policy sets disablePeerRelay")
		telemetry.RecordPath(telemetry.SwitchRelay, "emergency disablePeerRelay")
	}
}

func runEmergencyPolicyPoll(ctx context.Context, relay *config.RelayOptions) {
	if !emergencyPolicyConfigured(relay) {
		return
	}
	tick := time.NewTicker(10 * time.Minute)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			ectx, cancel := context.WithTimeout(ctx, 15*time.Second)
			applyEmergencyPolicyOnce(ectx, relay)
			cancel()
		}
	}
}
