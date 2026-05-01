package vpn

import (
	"regexp"
	"strings"
)

var reHostPort = regexp.MustCompile(`\b(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}):(\d{1,5})\b`)

func peerHintsFromClusterRaw(raw string) []string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil
	}
	seen := make(map[string]struct{})
	var out []string
	for _, m := range reHostPort.FindAllStringSubmatch(raw, -1) {
		if len(m) < 3 {
			continue
		}
		s := m[1] + ":" + m[2]
		if _, ok := seen[s]; ok {
			continue
		}
		seen[s] = struct{}{}
		out = append(out, s)
		if len(out) >= 32 {
			break
		}
	}
	return out
}
