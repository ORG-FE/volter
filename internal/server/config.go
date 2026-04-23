package server

import (
	"encoding/hex"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Mode string

const (
	ModeTCPOnly Mode = "tcp-only"
	ModeQUICOnly Mode = "quic-only"
	ModeBoth    Mode = "both"
)

type Config struct {
	ListenPorts              []int
	Token                    string
	UDPChannels              int
	PublicHost               string
	Debug                    bool
	ServerMode               Mode
	QUICListenPort           int
	QUICCertPath             string
	QUICKeyPath              string
	QUICALPN                 string
	QUICMaxStreams           int
	QUICMaxHandshakes        int
	QUICIdleTimeout          time.Duration
	QUICHandshakeTimeout     time.Duration
	QUICTCPConnectTimeout    time.Duration
	QUICIngressRingSlots     int
	RealityEnabled           bool
	RealityDest              string
	RealityPrivateKeyPEM     string
	RealityShortIDs          [][8]byte
	RealityServerNames       []string
	RealityMinVer            []byte
	RealityMaxVer            []byte
	WireProfileRotateSec     int
	TCPNoDelay               bool
	HandshakeSkew            time.Duration
	ReplayWindow             time.Duration
}

func LoadConfig(path string) (*Config, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	p := parseProperties(string(b))
	portsStr := firstNonEmpty(p["listenPorts"], p["listenPort"])
	if portsStr == "" {
		return nil, fmt.Errorf("listenPorts is required")
	}
	ports, err := parsePortsCSV(portsStr)
	if err != nil {
		return nil, err
	}
	token := strings.TrimSpace(p["token"])
	if token == "" {
		return nil, fmt.Errorf("token is required")
	}
	if len(token) > 4096 {
		return nil, fmt.Errorf("token too long")
	}
	udpCh := parseIntDef(p["udpChannels"], 4)
	if udpCh != 4 {
		return nil, fmt.Errorf("udpChannels must be 4")
	}
	mode := Mode(strings.ToLower(strings.TrimSpace(firstNonEmpty(p["serverMode"], "tcp-only"))))
	if mode != ModeTCPOnly && mode != ModeQUICOnly && mode != ModeBoth {
		return nil, fmt.Errorf("bad serverMode")
	}
	qPort := parseIntDef(p["quicListenPort"], 0)
	qCert := strings.TrimSpace(p["quicCertPath"])
	qKey := strings.TrimSpace(p["quicKeyPath"])
	qAlpn := strings.TrimSpace(firstNonEmpty(p["quicAlpn"], "volter"))
	if mode == ModeQUICOnly || mode == ModeBoth {
		if qPort < 1 || qPort > 65535 {
			return nil, fmt.Errorf("bad quicListenPort")
		}
		if qCert == "" || qKey == "" {
			return nil, fmt.Errorf("quicCertPath and quicKeyPath are required for QUIC")
		}
	}
	cfg := &Config{
		ListenPorts:           ports,
		Token:                 token,
		UDPChannels:           udpCh,
		PublicHost:            strings.TrimSpace(p["publicHost"]),
		Debug:                 strings.EqualFold(strings.TrimSpace(p["debug"]), "true"),
		ServerMode:            mode,
		QUICListenPort:        qPort,
		QUICCertPath:          qCert,
		QUICKeyPath:           qKey,
		QUICALPN:              qAlpn,
		QUICMaxStreams:        parseIntDef(p["quicMaxStreams"], 128),
		QUICMaxHandshakes:     parseIntDef(p["quicMaxHandshakes"], 512),
		QUICIdleTimeout:       time.Duration(parseIntDef(p["quicIdleTimeoutMs"], 900_000)) * time.Millisecond,
		QUICHandshakeTimeout:  time.Duration(parseIntDef(p["quicHandshakeTimeoutMs"], 60_000)) * time.Millisecond,
		QUICTCPConnectTimeout: time.Duration(parseIntDef(p["quicTcpConnectTimeoutMs"], 10_000)) * time.Millisecond,
		QUICIngressRingSlots:  parseIntDef(p["quicIngressRingSlots"], 4096),
		RealityEnabled:        strings.EqualFold(strings.TrimSpace(p["reality.enabled"]), "true"),
		RealityDest:           strings.TrimSpace(p["reality.dest"]),
		RealityPrivateKeyPEM:  strings.TrimSpace(p["reality.privateKey"]),
		WireProfileRotateSec:  parseIntDef(p["wireProfile.rotateSec"], 300),
		TCPNoDelay:            parseBoolDef(p["tcpNoDelay"], true),
		HandshakeSkew:         time.Duration(parseIntDef(p["handshake.skewSec"], 120)) * time.Second,
		ReplayWindow:          time.Duration(parseIntDef(p["replay.windowSec"], 90)) * time.Second,
	}
	if cfg.QUICIdleTimeout <= 0 {
		cfg.QUICIdleTimeout = 24 * time.Hour
	}
	if sn := strings.TrimSpace(p["reality.serverNames"]); sn != "" {
		for _, s := range strings.Split(sn, ",") {
			s = strings.TrimSpace(s)
			if s != "" {
				cfg.RealityServerNames = append(cfg.RealityServerNames, s)
			}
		}
	}
	if sid := strings.TrimSpace(p["reality.shortIds"]); sid != "" {
		for _, h := range strings.Split(sid, ",") {
			h = strings.TrimSpace(h)
			b, err := hex.DecodeString(h)
			if err != nil || len(b) != 8 {
				continue
			}
			var id [8]byte
			copy(id[:], b)
			cfg.RealityShortIDs = append(cfg.RealityShortIDs, id)
		}
	}
	if cfg.RealityEnabled {
		if cfg.RealityDest == "" || cfg.RealityPrivateKeyPEM == "" {
			return nil, fmt.Errorf("reality.dest and reality.privateKey required when reality.enabled")
		}
	}
	return cfg, nil
}

func parseProperties(s string) map[string]string {
	out := make(map[string]string)
	for _, line := range strings.Split(s, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		i := strings.IndexByte(line, '=')
		if i <= 0 {
			continue
		}
		k := strings.TrimSpace(line[:i])
		v := strings.TrimSpace(line[i+1:])
		out[k] = v
	}
	return out
}

func firstNonEmpty(a, b string) string {
	a = strings.TrimSpace(a)
	if a != "" {
		return a
	}
	return strings.TrimSpace(b)
}

func parsePortsCSV(s string) ([]int, error) {
	var out []int
	for _, raw := range strings.Split(s, ",") {
		raw = strings.TrimSpace(raw)
		if raw == "" {
			continue
		}
		p, err := strconv.Atoi(raw)
		if err != nil || p < 1 || p > 65535 {
			return nil, fmt.Errorf("bad port: %s", raw)
		}
		out = append(out, p)
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("listenPorts empty")
	}
	return out, nil
}

func parseIntDef(s string, def int) int {
	s = strings.TrimSpace(s)
	if s == "" {
		return def
	}
	v, err := strconv.Atoi(s)
	if err != nil {
		return def
	}
	return v
}

func parseBoolDef(s string, def bool) bool {
	s = strings.TrimSpace(strings.ToLower(s))
	if s == "" {
		return def
	}
	return s == "true" || s == "1" || s == "yes"
}
