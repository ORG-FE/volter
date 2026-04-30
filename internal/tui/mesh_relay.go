package tui

import (
	"fmt"
	"strconv"
	"strings"

	"dev.c0redev.volter/internal/config"

	"github.com/charmbracelet/bubbles/textinput"
)

const meshRelayInputCount = 37

var meshRelayLabels = []string{
	"peerId",
	"discoveryURL",
	"bootstrapPubKey",
	"discoverySigned",
	"stunServers (,)",
	"turnUrls (,)",
	"dhtRpcSeedPeers (,)",
	"dhtFindUrls (,)",
	"dhtRpcSecret",
	"dhtRpcListenUdp",
	"peerRelayUdpListen",
	"peerRelayUdpAdvertise",
	"peerQuicServerName",
	"emergencyPolicyURL",
	"emergencyPolicyPubKey",
	"gossipPeers (,)",
	"stakeRegistryURL",
	"stakeRegistryPubKey",
	"stakeReputationFile",
	"stakeBonusHttpUrl",
	"stakeMerkleFile",
	"stakeMerkleRootUrl",
	"dhtRpcIntervalSec",
	"dhtRpcFindK",
	"dhtIterativeRounds",
	"dhtIterativeAlpha",
	"pathCooldownMs",
	"gossipIntervalSec",
	"gossipMaxAgeSec",
	"stakeMin",
	"gossipEnabled t/f",
	"peerPathFromDiscovery t/f",
	"peerRelayUseQuic t/f",
	"peerRelayUseUdp t/f",
	"dhtPublishSrflx t/f",
	"symmetricNatHolePunch t/f",
	"pathAggressive t/f",
}

func splitCommaList(s string) []string {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil
	}
	var out []string
	for _, p := range strings.Split(s, ",") {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func parseBoolLoose(s string) bool {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "1", "true", "yes", "y", "on":
		return true
	default:
		return false
	}
}

func atoiField(s string) int {
	n, _ := strconv.Atoi(strings.TrimSpace(s))
	return n
}

func newMeshRelayInputs(r *config.RelayOptions) []textinput.Model {
	ti := func(pl, val string) textinput.Model {
		t := textinput.New()
		t.Placeholder = pl
		t.SetValue(val)
		return t
	}
	var z config.RelayOptions
	if r != nil {
		z = *r
	}
	join := func(xs []string) string {
		return strings.Join(xs, ", ")
	}
	boolStr := func(b bool) string {
		if b {
			return "true"
		}
		return "false"
	}
	return []textinput.Model{
		ti("", z.PeerID),
		ti("https://...", z.DiscoveryURL),
		ti("base64", z.BootstrapPubKey),
		ti("", z.DiscoverySigned),
		ti("host:port,...", join(z.StunServers)),
		ti("turn:...", join(z.TurnURLs)),
		ti("host:port,...", join(z.DhtRpcSeedPeers)),
		ti("https://...", join(z.DHTFindURLs)),
		ti("", z.DhtRpcSecret),
		ti(":4001", z.DhtRpcListenUDP),
		ti("0.0.0.0:0", z.PeerRelayUDPListen),
		ti("", z.PeerRelayUDPAdvertise),
		ti("", z.PeerQuicServerName),
		ti("", z.EmergencyPolicyURL),
		ti("", z.EmergencyPolicyPubKey),
		ti("", join(z.GossipPeers)),
		ti("", z.StakeRegistryURL),
		ti("", z.StakeRegistryPubKey),
		ti("", z.StakeReputationFile),
		ti("", z.StakeBonusHTTPURL),
		ti("", z.StakeMerkleFile),
		ti("", z.StakeMerkleRootURL),
		ti("120", strconv.Itoa(z.DhtRpcIntervalSec)),
		ti("20", strconv.Itoa(z.DhtRpcFindK)),
		ti("0", strconv.Itoa(z.DhtIterativeRounds)),
		ti("3", strconv.Itoa(z.DhtIterativeAlpha)),
		ti("0", strconv.Itoa(z.PathCooldownMs)),
		ti("180", strconv.Itoa(z.GossipIntervalSec)),
		ti("900", strconv.Itoa(z.GossipMaxAgeSec)),
		ti("0", strconv.Itoa(z.StakeMin)),
		ti("false", boolStr(z.GossipEnabled)),
		ti("true", boolStr(z.PeerPathFromDiscovery)),
		ti("false", boolStr(z.PeerRelayUseQUIC)),
		ti("true", boolStr(z.PeerRelayUseUDP)),
		ti("true", boolStr(z.DhtPublishSrflx)),
		ti("true", boolStr(z.SymmetricNatHolePunch)),
		ti("true", boolStr(z.PathAggressive)),
	}
}

func meshRelayFromInputs(inputs []textinput.Model) (config.RelayOptions, string) {
	if len(inputs) != meshRelayInputCount {
		return config.RelayOptions{}, "internal: mesh form"
	}
	get := func(i int) string {
		return strings.TrimSpace(inputs[i].Value())
	}
	out := config.RelayOptions{
		PeerID:                get(0),
		DiscoveryURL:          get(1),
		BootstrapPubKey:       get(2),
		DiscoverySigned:       get(3),
		StunServers:           splitCommaList(get(4)),
		TurnURLs:              splitCommaList(get(5)),
		DhtRpcSeedPeers:       splitCommaList(get(6)),
		DHTFindURLs:           splitCommaList(get(7)),
		DhtRpcSecret:          get(8),
		DhtRpcListenUDP:       get(9),
		PeerRelayUDPListen:    get(10),
		PeerRelayUDPAdvertise: get(11),
		PeerQuicServerName:    get(12),
		EmergencyPolicyURL:    get(13),
		EmergencyPolicyPubKey: get(14),
		GossipPeers:           splitCommaList(get(15)),
		StakeRegistryURL:      get(16),
		StakeRegistryPubKey:   get(17),
		StakeReputationFile:   get(18),
		StakeBonusHTTPURL:     get(19),
		StakeMerkleFile:       get(20),
		StakeMerkleRootURL:    get(21),
		DhtRpcIntervalSec:     atoiField(get(22)),
		DhtRpcFindK:           atoiField(get(23)),
		DhtIterativeRounds:    atoiField(get(24)),
		DhtIterativeAlpha:     atoiField(get(25)),
		PathCooldownMs:        atoiField(get(26)),
		GossipIntervalSec:     atoiField(get(27)),
		GossipMaxAgeSec:       atoiField(get(28)),
		StakeMin:              atoiField(get(29)),
		GossipEnabled:         parseBoolLoose(get(30)),
		PeerPathFromDiscovery: parseBoolLoose(get(31)),
		PeerRelayUseQUIC:      parseBoolLoose(get(32)),
		PeerRelayUseUDP:       parseBoolLoose(get(33)),
		DhtPublishSrflx:       parseBoolLoose(get(34)),
		SymmetricNatHolePunch: parseBoolLoose(get(35)),
		PathAggressive:        parseBoolLoose(get(36)),
	}
	return out, ""
}

func relayMergeKeepAdvanced(old *config.RelayOptions, nu *config.RelayOptions) {
	if old == nil || nu == nil {
		return
	}
	if strings.TrimSpace(nu.PrivateKey) == "" {
		nu.PrivateKey = old.PrivateKey
	}
	if len(nu.AllowedClasses) == 0 && len(old.AllowedClasses) > 0 {
		nu.AllowedClasses = old.AllowedClasses
	}
	if nu.MaxConcurrent == 0 && old.MaxConcurrent != 0 {
		nu.MaxConcurrent = old.MaxConcurrent
	}
	if nu.BudgetKbps == 0 && old.BudgetKbps != 0 {
		nu.BudgetKbps = old.BudgetKbps
	}
	if len(nu.GeoAllowCountries) == 0 && len(old.GeoAllowCountries) > 0 {
		nu.GeoAllowCountries = old.GeoAllowCountries
	}
	if len(nu.GeoDenyCountries) == 0 && len(old.GeoDenyCountries) > 0 {
		nu.GeoDenyCountries = old.GeoDenyCountries
	}
}

func relaySummaryShort(r *config.RelayOptions) string {
	if r == nil {
		return "  (relay не задан в JSON профиля)\n"
	}
	var b strings.Builder
	trim := func(s string) string {
		s = strings.TrimSpace(s)
		if len(s) > 64 {
			return s[:61] + "…"
		}
		return s
	}
	if s := trim(r.DiscoveryURL); s != "" {
		fmt.Fprintf(&b, "  discoveryURL: %s\n", s)
	}
	if s := trim(r.BootstrapPubKey); s != "" {
		fmt.Fprintf(&b, "  bootstrapPubKey: %s\n", s)
	}
	if len(r.TurnURLs) > 0 {
		fmt.Fprintf(&b, "  turnUrls: %s\n", strings.Join(r.TurnURLs, ", "))
	}
	if len(r.StunServers) > 0 {
		fmt.Fprintf(&b, "  stunServers: %s\n", strings.Join(r.StunServers, ", "))
	}
	if len(r.DhtRpcSeedPeers) > 0 {
		fmt.Fprintf(&b, "  dhtRpcSeedPeers: %s\n", strings.Join(r.DhtRpcSeedPeers, ", "))
	}
	if b.Len() == 0 {
		return "  (поля relay пусты — укажи discoveryURL / peer / DHT)\n"
	}
	return b.String()
}

func mergeCfgPreserveRelayProtection(oldName string, cfg config.Config) config.Config {
	old, err := config.LoadByName(oldName)
	if err != nil {
		return cfg
	}
	cfg.Relay = old.Relay
	cfg.Protection = old.Protection
	return cfg
}
