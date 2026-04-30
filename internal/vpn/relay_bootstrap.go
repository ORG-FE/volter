package vpn

import (
	"context"
	"fmt"
	"strings"
	"time"

	"dev.c0redev.volter/internal/clientlog"
	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/dht"
	"dev.c0redev.volter/internal/discovery"
	"dev.c0redev.volter/internal/stake"
	"dev.c0redev.volter/internal/telemetry"
)

func runRelayBootstrapVerify(ctx context.Context, relay *config.RelayOptions) {
	if relay == nil {
		return
	}
	pkb := strings.TrimSpace(relay.BootstrapPubKey)
	url := strings.TrimSpace(relay.DiscoveryURL)
	inline := strings.TrimSpace(relay.DiscoverySigned)
	if pkb == "" || (url == "" && inline == "") {
		return
	}
	pub, err := discovery.DecodeEd25519PublicKey(pkb)
	if err != nil {
		clientlog.Warn("vpn: relay bootstrap pubkey: %v", err)
		return
	}

	var cur discovery.SignedBootstrap
	loaded := false
	lastDig := ""

	applyRaw := func(raw string) (string, error) {
		b, err := discovery.ParseSignedBootstrapJSON(raw)
		if err != nil {
			return "", err
		}
		if err := b.Verify(pub, time.Now()); err != nil {
			return "", err
		}
		gctx, cancel := context.WithTimeout(ctx, 90*time.Second)
		view := applyRelayProductFilters(gctx, b.Nodes, relay)
		cancel()
		dht.DefaultTable().Merge(view)
		dig, err := discovery.RelayIndexDigest(view)
		if err != nil {
			return "", err
		}
		cur = b
		loaded = true
		return dig, nil
	}

	fetchRaw := func() (string, error) {
		if url != "" {
			fetchCtx, cancel := context.WithTimeout(ctx, 50*time.Second)
			b, err := discovery.FetchBootstrapBody(fetchCtx, url)
			cancel()
			if err != nil {
				if inline != "" {
					clientlog.Warn("vpn: relay bootstrap fetch: %v, use inline", err)
					return inline, nil
				}
				return "", err
			}
			return string(b), nil
		}
		return inline, nil
	}

	raw0, err := fetchRaw()
	if err != nil {
		clientlog.Warn("vpn: relay bootstrap: %v", err)
		return
	}
	dig0, err := applyRaw(raw0)
	if err != nil {
		clientlog.Warn("vpn: relay bootstrap verify: %v", err)
		return
	}
	if dig0 != lastDig {
		lastDig = dig0
		gctx, cancel := context.WithTimeout(ctx, 90*time.Second)
		nv := applyRelayProductFilters(gctx, cur.Nodes, relay)
		cancel()
		clientlog.OK("vpn: relay index nodes=%d digest=%s", len(nv), dig0)
		telemetry.RecordPath(telemetry.SwitchRelay, fmt.Sprintf("bootstrap digest=%s nodes=%d", dig0, len(nv)))
	}

	tick := time.NewTicker(15 * time.Minute)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			if url != "" {
				fetchCtx, cancel := context.WithTimeout(ctx, 50*time.Second)
				b, err := discovery.FetchBootstrapBody(fetchCtx, url)
				cancel()
				if err != nil {
					clientlog.Warn("vpn: relay bootstrap refresh fetch: %v", err)
				} else {
					dig, err := applyRaw(string(b))
					if err != nil {
						clientlog.Warn("vpn: relay bootstrap refresh verify: %v", err)
					} else if dig != lastDig {
						lastDig = dig
						gctx, cancel := context.WithTimeout(ctx, 90*time.Second)
						nv := applyRelayProductFilters(gctx, cur.Nodes, relay)
						cancel()
						clientlog.Info("vpn: relay index updated nodes=%d digest=%s", len(nv), dig)
						telemetry.RecordPath(telemetry.SwitchRelay, fmt.Sprintf("refresh digest=%s nodes=%d", dig, len(nv)))
					}
				}
			}
			if !loaded {
				continue
			}
			if err := cur.Verify(pub, time.Now()); err != nil {
				clientlog.Warn("vpn: relay bootstrap expired: %v", err)
				telemetry.RecordPath(telemetry.SwitchRelay, "bootstrap expired")
				return
			}
		}
	}
}

func applyRelayProductFilters(ctx context.Context, nodes []discovery.RelayNode, relay *config.RelayOptions) []discovery.RelayNode {
	if relay == nil {
		return append([]discovery.RelayNode(nil), nodes...)
	}
	out := discovery.FilterRelayNodesByClass(nodes, relay.AllowedClasses)
	out = stake.Economy{
		RegistryURL: relay.StakeRegistryURL, RegistryPubKey: relay.StakeRegistryPubKey,
		ReputationFile:     relay.StakeReputationFile,
		StakeBonusHTTPURL:  relay.StakeBonusHTTPURL,
		StakeMerkleFile:    relay.StakeMerkleFile,
		StakeMerkleRootURL: relay.StakeMerkleRootURL,
	}.MergeStake(ctx, out)
	out = discovery.FilterRelayNodesByStake(out, relay.StakeMin)
	out = discovery.FilterRelayNodesByGeo(ctx, out, relay.GeoAllowCountries, relay.GeoDenyCountries, nil)
	return out
}
