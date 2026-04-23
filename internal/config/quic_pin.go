package config

import (
	"encoding/hex"
	"net"
	"strconv"
	"strings"

	"dev.c0redev.volter/internal/protocol"
)

func (c Config) QuicTLSVerifyStrict() bool {
	return c.QuicSkipVerify != nil && !*c.QuicSkipVerify
}

func EffectiveQuicCertPin(c Config, caps *protocol.ServerHelloCaps) string {
	manual := strings.TrimSpace(strings.ReplaceAll(c.QuicCertPinSHA256, ":", ""))
	if manual != "" {
		return c.QuicCertPinSHA256
	}
	if caps == nil || len(caps.QuicLeafPinSHA256) != 32 {
		return ""
	}
	if c.QuicTLSVerifyStrict() && strings.TrimSpace(c.QuicCaCert) != "" {
		return ""
	}
	return hex.EncodeToString(caps.QuicLeafPinSHA256)
}

func ApplyTcpOnlyIfServerHasNoQUIC(cfg *Config, caps *protocol.ServerHelloCaps) {
	if cfg == nil || caps == nil {
		return
	}
	hasTCP := (caps.TransportMask & protocol.TransportTCP) != 0
	hasQUIC := (caps.TransportMask & protocol.TransportQUIC) != 0
	if hasTCP && hasQUIC {
		return
	}
	if hasTCP && !hasQUIC {
		cfg.Transport = "tcp"
		cfg.QuicServer = ""
		return
	}
	if hasQUIC && !hasTCP {
		cfg.Transport = "quic"
		if strings.TrimSpace(cfg.QuicServer) == "" {
			host, _, err := net.SplitHostPort(strings.TrimSpace(cfg.Server))
			if err == nil && host != "" {
				port := int(caps.QuicPort)
				if port <= 0 || port > 65535 {
					port = 4433
				}
				cfg.QuicServer = net.JoinHostPort(host, strconv.Itoa(port))
			}
		}
	}
}
