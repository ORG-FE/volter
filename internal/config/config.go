package config

import (
	"encoding/base64"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"strings"
	"unicode/utf8"

	"dev.c0redev.volter/internal/dpi"
	"dev.c0redev.volter/internal/protocol"
)

const DefaultCloudTunCIDR6 = "fd00:13:37::2/64"

type Config struct {
	Server            string `json:"server"`
	QuicServer        string `json:"quicServer,omitempty"`
	Token             string `json:"token"`
	Transport         string `json:"transport,omitempty"`
	QuicServerName    string `json:"quicServerName,omitempty"`
	QuicSkipVerify    *bool  `json:"quicSkipVerify,omitempty"`
	QuicCertPinSHA256 string `json:"quicCertPinSHA256,omitempty"`
	QuicCaCert        string `json:"quicCaCert,omitempty"`

	QuicTraceLog  bool               `json:"quicTraceLog,omitempty"`
	DualTransport *bool              `json:"dualTransport,omitempty"`
	Routes        string             `json:"routes,omitempty"`
	Exclude       string             `json:"exclude,omitempty"`
	TunCIDR6      string             `json:"tunCIDR6,omitempty"`
	Protection    *ProtectionOptions `json:"protection,omitempty"`
	Relay         *RelayOptions      `json:"relay,omitempty"`
}

type RelayOptions struct {
	PeerID                string   `json:"peerId,omitempty"`
	PrivateKey            string   `json:"privateKey,omitempty"`
	AllowedClasses        []string `json:"allowedClasses,omitempty"`
	MaxConcurrent         int      `json:"maxConcurrent,omitempty"`
	BudgetKbps            int      `json:"budgetKbps,omitempty"`
	DiscoverySigned       string   `json:"discoverySigned,omitempty"`
	DiscoveryURL          string   `json:"discoveryURL,omitempty"`
	GossipEnabled         bool     `json:"gossipEnabled,omitempty"`
	BootstrapPubKey       string   `json:"bootstrapPubKey,omitempty"`
	EmergencyPolicyURL    string   `json:"emergencyPolicyURL,omitempty"`
	EmergencyPolicyPubKey string   `json:"emergencyPolicyPubKey,omitempty"`
	PathAggressive        bool     `json:"pathAggressive,omitempty"`
	PathCooldownMs        int      `json:"pathCooldownMs,omitempty"`
	StunServers           []string `json:"stunServers,omitempty"`
	TurnURLs              []string `json:"turnUrls,omitempty"`
	GossipPeers           []string `json:"gossipPeers,omitempty"`
	GossipIntervalSec     int      `json:"gossipIntervalSec,omitempty"`
	GossipMaxAgeSec       int      `json:"gossipMaxAgeSec,omitempty"`
	GeoAllowCountries     []string `json:"geoAllowCountries,omitempty"`
	GeoDenyCountries      []string `json:"geoDenyCountries,omitempty"`
	StakeMin              int      `json:"stakeMin,omitempty"`
	PeerPathFromDiscovery bool     `json:"peerPathFromDiscovery,omitempty"`
	PeerRelayUseQUIC      bool     `json:"peerRelayUseQuic,omitempty"`
	PeerRelayUseUDP       bool     `json:"peerRelayUseUdp,omitempty"`
	PeerRelayUDPListen    string   `json:"peerRelayUdpListen,omitempty"`
	PeerRelayUDPAdvertise string   `json:"peerRelayUdpAdvertise,omitempty"`
	PeerQuicServerName    string   `json:"peerQuicServerName,omitempty"`
	DHTFindURLs           []string `json:"dhtFindUrls,omitempty"`
	StakeRegistryURL      string   `json:"stakeRegistryURL,omitempty"`
	StakeRegistryPubKey   string   `json:"stakeRegistryPubKey,omitempty"`
	StakeReputationFile   string   `json:"stakeReputationFile,omitempty"`
	StakeBonusHTTPURL     string   `json:"stakeBonusHttpUrl,omitempty"`
	StakeMerkleFile       string   `json:"stakeMerkleFile,omitempty"`
	StakeMerkleRootURL    string   `json:"stakeMerkleRootUrl,omitempty"`
	DhtRpcListenUDP       string   `json:"dhtRpcListenUdp,omitempty"`
	DhtRpcSecret          string   `json:"dhtRpcSecret,omitempty"`
	DhtRpcSeedPeers       []string `json:"dhtRpcSeedPeers,omitempty"`
	DhtRpcIntervalSec     int      `json:"dhtRpcIntervalSec,omitempty"`
	DhtRpcFindK           int      `json:"dhtRpcFindK,omitempty"`
	DhtIterativeRounds    int      `json:"dhtIterativeRounds,omitempty"`
	DhtIterativeAlpha     int      `json:"dhtIterativeAlpha,omitempty"`
	DhtPublishSrflx       bool     `json:"dhtPublishSrflx,omitempty"`
	SymmetricNatHolePunch bool     `json:"symmetricNatHolePunch,omitempty"`
}

type ProtectionOptions struct {
	Obfuscation                 string            `json:"obfuscation,omitempty"`
	PreambleProfile             string            `json:"preambleProfile,omitempty"`
	PreambleRotate              bool              `json:"preambleRotate,omitempty"`
	ProbeObfsProfileID          byte              `json:"-"`
	JunkCount                   int               `json:"junkCount,omitempty"`
	JunkMin                     int               `json:"junkMin,omitempty"`
	JunkMax                     int               `json:"junkMax,omitempty"`
	PadS1                       int               `json:"padS1,omitempty"`
	PadS2                       int               `json:"padS2,omitempty"`
	PadS3                       int               `json:"padS3,omitempty"`
	PadS4                       int               `json:"padS4,omitempty"`
	PreCheck                    bool              `json:"preCheck,omitempty"`
	MagicSplit                  string            `json:"magicSplit,omitempty"`
	JunkStyle                   string            `json:"junkStyle,omitempty"`
	FlushPolicy                 string            `json:"flushPolicy,omitempty"`
	ObfSeed                     string            `json:"obfSeed,omitempty"`
	CapsVersion                 int               `json:"capsVersion,omitempty"`
	TransportMask               int               `json:"transportMask,omitempty"`
	FeatureBits                 int               `json:"featureBits,omitempty"`
	ClientNonce                 string            `json:"clientNonce,omitempty"`
	ClientTsSec                 int64             `json:"clientTsSec,omitempty"`
	RelayHop                    int               `json:"relayHop,omitempty"`
	RelayMaxHop                 int               `json:"relayMaxHop,omitempty"`
	RelayBudgetKbps             int               `json:"relayBudgetKbps,omitempty"`
	PeerID                      string            `json:"peerId,omitempty"`
	RelayNonce                  string            `json:"relayNonce,omitempty"`
	RelaySig                    string            `json:"relaySig,omitempty"`
	SessionID                   string            `json:"sessionId,omitempty"`
	ResumeToken                 string            `json:"resumeToken,omitempty"`
	RouteID                     string            `json:"routeId,omitempty"`
	HopIndex                    int               `json:"hopIndex,omitempty"`
	ChurnEpochSec               int               `json:"churnEpochSec,omitempty"`
	FlushJitterMaxMs            int               `json:"flushJitterMaxMs,omitempty"`
	BurstSmoothingMaxMs         int               `json:"burstSmoothingMaxMs,omitempty"`
	ShapeMaxKbps                int               `json:"shapeMaxKbps,omitempty"`
	ShapeJitterMaxMs            int               `json:"shapeJitterMaxMs,omitempty"`
	ShapeExpMeanMs              int               `json:"shapeExpMeanMs,omitempty"`
	ClusterHTTPKey              string            `json:"clusterHttpKey,omitempty"`
	ClusterMapPath              string            `json:"clusterMapPath,omitempty"`
	ClusterSessionsPath         string            `json:"clusterSessionsPath,omitempty"`
	ClusterClientsPath          string            `json:"clusterClientsPath,omitempty"`
	ClusterPreferredServer      string            `json:"clusterPreferredServer,omitempty"`
	ClusterInvitePath           string            `json:"clusterInvitePath,omitempty"`
	ClusterPeerHandshakePath    string            `json:"clusterPeerHandshakePath,omitempty"`
	ClusterRouteAssist          bool              `json:"clusterRouteAssist,omitempty"`
	ClusterAssistTargetNodeID   string            `json:"clusterAssistTargetNodeId,omitempty"`
	TlsProfileID                string            `json:"tlsProfileId,omitempty"`
	Ja3TargetHash               string            `json:"ja3TargetHash,omitempty"`
	StandaloneDpiOnly           bool              `json:"standaloneDpiOnly,omitempty"`
	DpiLocalEngine              string            `json:"dpiLocalEngine,omitempty"`
	DpiLocalEmbedded            *DpiLocalEmbedded `json:"dpiLocalEmbedded,omitempty"`
	DpiVolunteer                bool              `json:"dpiVolunteer,omitempty"`
	DpiVolterTransportObfuscate bool              `json:"dpiVolterTransportObfuscate,omitempty"`
	DpiProbeURLs                []string          `json:"dpiProbeUrls,omitempty"`
	AntiDpiWithVpn              bool              `json:"antiDpiWithVpn,omitempty"`
	DpiLocalPreset              string            `json:"dpiLocalPreset,omitempty"`
	RouteMode                   string            `json:"routeMode,omitempty"`
	RoutePlannerV2              bool              `json:"routePlannerV2,omitempty"`
}

type DpiLocalEmbedded struct {
	SplitAfter  int  `json:"splitAfter,omitempty"`
	SplitAfter2 int  `json:"splitAfter2,omitempty"`
	TTLMillis   int  `json:"ttlMillis,omitempty"`
	TTL2Millis  int  `json:"ttl2Millis,omitempty"`
	Disorder    bool `json:"disorder,omitempty"`
	JitterMaxMs int  `json:"jitterMaxMs,omitempty"`
	LeadInMs    int  `json:"leadInMs,omitempty"`
}

func DpiLocalEngineIsExternal(p *ProtectionOptions) bool {
	if p == nil {
		return false
	}
	return strings.EqualFold(strings.TrimSpace(p.DpiLocalEngine), "external")
}

func DpiLocalEngineIsEmbedded(p *ProtectionOptions) bool {
	if p == nil {
		return false
	}
	return strings.EqualFold(strings.TrimSpace(p.DpiLocalEngine), "embedded")
}

func StandaloneDpiUseExternalBin(cfg *Config) bool {
	if cfg == nil || cfg.Protection == nil {
		return false
	}
	p := cfg.Protection
	if DpiLocalEngineIsExternal(p) {
		return true
	}
	if DpiLocalEngineIsEmbedded(p) {
		return false
	}
	return strings.TrimSpace(p.DpiLocalPreset) != ""
}

func MergeDpiLocalEmbeddedDefaults(e *DpiLocalEmbedded) DpiLocalEmbedded {
	out := DpiLocalEmbedded{SplitAfter: 1, TTLMillis: 8}
	if e != nil {
		if e.SplitAfter > 0 {
			out.SplitAfter = e.SplitAfter
		}
		if e.SplitAfter2 > 0 {
			out.SplitAfter2 = e.SplitAfter2
		}
		if e.TTLMillis > 0 {
			out.TTLMillis = e.TTLMillis
		}
		if e.TTL2Millis > 0 {
			out.TTL2Millis = e.TTL2Millis
		}
		out.Disorder = e.Disorder
		if e.JitterMaxMs > 0 {
			out.JitterMaxMs = e.JitterMaxMs
		}
		if e.LeadInMs > 0 {
			out.LeadInMs = e.LeadInMs
		}
	}
	if out.SplitAfter > 65536 {
		out.SplitAfter = 65536
	}
	if out.SplitAfter2 > 65536 {
		out.SplitAfter2 = 65536
	}
	if out.SplitAfter2 > 0 && out.SplitAfter2 <= out.SplitAfter {
		out.SplitAfter2 = 0
	}
	if out.TTLMillis > 60_000 {
		out.TTLMillis = 60_000
	}
	if out.TTL2Millis > 60_000 {
		out.TTL2Millis = 60_000
	}
	if out.JitterMaxMs > 5000 {
		out.JitterMaxMs = 5000
	}
	if out.LeadInMs > 60_000 {
		out.LeadInMs = 60_000
	}
	return out
}

func ClampDpiLocalPreset(s string) string {
	if utf8.RuneCountInString(s) <= dpi.MaxGossipPresetRunes {
		return s
	}
	r := []rune(s)
	return string(r[:dpi.MaxGossipPresetRunes])
}

func SanitizeProtectionInPlace(p *ProtectionOptions) {
	if p == nil {
		return
	}
	p.DpiLocalPreset = ClampDpiLocalPreset(p.DpiLocalPreset)
}

func Dir() (string, error) {
	d, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(d, "volter"), nil
}

func List() ([]Config, []string, error) {
	dir, err := Dir()
	if err != nil {
		return nil, nil, err
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, nil, err
	}
	ents, err := os.ReadDir(dir)
	if err != nil {
		return nil, nil, err
	}
	var cfgs []Config
	var names []string
	for _, e := range ents {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		if e.Name() == "metrics.json" || e.Name() == "protection.json" || e.Name() == "settings.json" {
			continue
		}
		c, err := Load(filepath.Join(dir, e.Name()))
		if err != nil {
			continue
		}
		cfgs = append(cfgs, c)
		names = append(names, strings.TrimSuffix(e.Name(), ".json"))
	}
	return cfgs, names, nil
}

func Load(path string) (Config, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return Config{}, err
	}
	var c Config
	if err := json.Unmarshal(b, &c); err != nil {
		return Config{}, err
	}
	SanitizeProtectionInPlace(c.Protection)
	return c, nil
}

func (c Config) QuicSkipVerifyEffective() bool {
	if c.QuicSkipVerify == nil {
		return true
	}
	return *c.QuicSkipVerify
}

func (c Config) QuicSkipVerifyFormField() string {
	if c.QuicSkipVerify == nil {
		return ""
	}
	if *c.QuicSkipVerify {
		return "true"
	}
	return "false"
}

func ApplyCloudConnectDefaults(cfg *Config, serverMode string, probeIPv6 bool) {
	if strings.TrimSpace(cfg.QuicCertPinSHA256) == "" {
		cfg.QuicSkipVerify = nil
	}
	mode := strings.ToLower(strings.TrimSpace(serverMode))
	forcedTCP := strings.EqualFold(strings.TrimSpace(cfg.Transport), "tcp")
	if forcedTCP {
		cfg.QuicServer = ""
	} else {
		switch mode {
		case "tcp only":
			cfg.Transport = "tcp"
			cfg.QuicServer = ""
		case "quic only", "quic/tcp":
			if cloudQuicNeedsDefaultPort(cfg.Server, cfg.QuicServer) {
				cfg.QuicServer = QuicServerHostPortForCloudTCP(cfg.Server)
			}
		default:
			if cloudQuicNeedsDefaultPort(cfg.Server, cfg.QuicServer) {
				cfg.QuicServer = QuicServerHostPortForCloudTCP(cfg.Server)
			}
		}
	}
	if strings.TrimSpace(cfg.TunCIDR6) == "" && probeIPv6 {
		cfg.TunCIDR6 = DefaultCloudTunCIDR6
	}
}

func Save(name string, c Config) error {
	SanitizeProtectionInPlace(c.Protection)
	dir, err := Dir()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	path := filepath.Join(dir, name+".json")
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, b, 0600)
}

func pathFor(name string) (string, error) {
	dir, err := Dir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, name+".json"), nil
}

func LoadByName(name string) (Config, error) {
	path, err := pathFor(name)
	if err != nil {
		return Config{}, err
	}
	return Load(path)
}

func SaveByName(name string, c Config) error {
	return Save(name, c)
}

func Delete(name string) error {
	path, err := pathFor(name)
	if err != nil {
		return err
	}
	return os.Remove(path)
}

const (
	protectionFileName = "protection.json"
	settingsFileName   = "settings.json"
)

type ClientSettings struct {
	Mode        string `json:"mode,omitempty"`
	SystemProxy bool   `json:"systemProxy,omitempty"`
	ProxyListen string `json:"proxyListen,omitempty"`
	LastProfile string `json:"lastProfile,omitempty"`
}

func LoadClientSettings() (ClientSettings, error) {
	dir, err := Dir()
	if err != nil {
		return ClientSettings{}, err
	}
	b, err := os.ReadFile(filepath.Join(dir, settingsFileName))
	if err != nil {
		if os.IsNotExist(err) {
			return ClientSettings{Mode: "tun", ProxyListen: "127.0.0.1:1080"}, nil
		}
		return ClientSettings{}, err
	}
	var s ClientSettings
	if err := json.Unmarshal(b, &s); err != nil {
		return ClientSettings{}, err
	}
	if s.Mode != "proxy" {
		s.Mode = "tun"
	}
	if s.ProxyListen == "" {
		s.ProxyListen = "127.0.0.1:1080"
	}
	return s, nil
}

func SaveClientSettings(s ClientSettings) error {
	dir, err := Dir()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, settingsFileName), b, 0600)
}

func LoadProtection() (ProtectionOptions, error) {
	dir, err := Dir()
	if err != nil {
		return ProtectionOptions{}, err
	}
	b, err := os.ReadFile(filepath.Join(dir, protectionFileName))
	if err != nil {
		if os.IsNotExist(err) {
			return ProtectionOptions{}, nil
		}
		return ProtectionOptions{}, err
	}
	var p ProtectionOptions
	if err := json.Unmarshal(b, &p); err != nil {
		return ProtectionOptions{}, err
	}
	SanitizeProtectionInPlace(&p)
	return p, nil
}

func SaveProtection(p ProtectionOptions) error {
	SanitizeProtectionInPlace(&p)
	dir, err := Dir()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(p, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, protectionFileName), b, 0600)
}

func MergeProbeObfsIntoProtection(p *ProtectionOptions, caps *protocol.ServerHelloCaps) *ProtectionOptions {
	var base ProtectionOptions
	if p != nil {
		base = *p
	}
	if caps != nil {
		base.ProbeObfsProfileID = caps.ObfsProfileID
		base.TransportMask = int(caps.TransportMask)
		base.FeatureBits = int(caps.FeatureBits)
		poly := (caps.FeatureBits & protocol.FeaturePolyHandshake) != 0
		if poly && caps.ObfsProfileID > 0 && strings.TrimSpace(strings.ToLower(base.PreambleProfile)) == "" {
			base.PreambleProfile = protocol.PreambleRotate
			base.PreambleRotate = true
		}
		if (caps.FeatureBits&protocol.FeatureRelayServer) != 0 && base.RelayMaxHop == 0 {
			base.RelayHop = 1
			if caps.PathTTL > 0 {
				base.RelayMaxHop = int(caps.PathTTL)
			} else {
				base.RelayMaxHop = 2
			}
			if caps.RelayFlags != 0 && base.RelayBudgetKbps == 0 {
				base.RelayBudgetKbps = 2048
			}
		}
	}
	return &base
}

func ParseConnection(s string) (server, token string, ok bool) {
	s = strings.TrimSpace(s)
	if s == "" {
		return "", "", false
	}
	if strings.HasPrefix(strings.ToLower(s), "volter://") {
		server, token, ok = parseVolterURI(s)
		if ok {
			return server, token, true
		}
	}
	for i := len(s) - 1; i >= 0; i-- {
		if s[i] != ':' {
			continue
		}
		serverCandidate := strings.TrimSpace(s[:i])
		tokenCandidate := strings.TrimSpace(s[i+1:])
		if serverCandidate == "" || tokenCandidate == "" {
			continue
		}
		if isValidServerAddress(serverCandidate) {
			return serverCandidate, tokenCandidate, true
		}
	}
	return "", "", false
}

func BuildConnectionURI(server, token string) string {
	u := volterURI{
		Server: strings.TrimSpace(server),
		Token:  strings.TrimSpace(token),
	}
	if u.Server == "" || u.Token == "" {
		return ""
	}
	b, err := json.Marshal(u)
	if err != nil {
		return ""
	}
	return "volter://" + base64.RawURLEncoding.EncodeToString(b)
}

type volterURI struct {
	Server string `json:"s"`
	Token  string `json:"k"`
}

func parseVolterURI(raw string) (server, token string, ok bool) {
	body := strings.TrimSpace(raw[len("volter://"):])
	if body == "" {
		return "", "", false
	}
	if i := strings.IndexAny(body, "?#"); i >= 0 {
		body = body[:i]
	}
	body = strings.TrimSpace(body)
	if body == "" {
		return "", "", false
	}
	var data []byte
	var err error
	data, err = base64.RawURLEncoding.DecodeString(body)
	if err != nil {
		data, err = base64.URLEncoding.DecodeString(body)
	}
	if err != nil {
		data, err = base64.RawStdEncoding.DecodeString(body)
	}
	if err != nil {
		data, err = base64.StdEncoding.DecodeString(body)
	}
	if err != nil {
		return "", "", false
	}
	var u volterURI
	if err := json.Unmarshal(data, &u); err != nil {
		return "", "", false
	}
	server = strings.TrimSpace(u.Server)
	token = strings.TrimSpace(u.Token)
	if server == "" || token == "" || !isValidServerAddress(server) {
		return "", "", false
	}
	return server, token, true
}

func isValidServerAddress(raw string) bool {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return false
	}
	if strings.HasPrefix(raw, "[") {
		closeIdx := strings.Index(raw, "]")
		if closeIdx <= 0 || closeIdx >= len(raw)-1 || raw[closeIdx+1] != ':' {
			return false
		}
		host, port, err := net.SplitHostPort(raw)
		return err == nil && host != "" && port != ""
	}
	_, _, err := net.SplitHostPort(raw)
	return err == nil
}

func SanitizeName(s string) string {
	s = strings.TrimSpace(s)
	var b strings.Builder
	for _, r := range s {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '-' || r == '_' {
			b.WriteRune(r)
		}
	}
	out := b.String()
	if out == "" {
		out = "default"
	}
	return out
}
