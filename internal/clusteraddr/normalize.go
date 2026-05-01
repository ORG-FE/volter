package clusteraddr

import (
	"net"
	"net/url"
	"strings"
)

func CanonicalHostPort(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	if i := strings.Index(s, "("); i >= 0 {
		if j := strings.LastIndex(s, ")"); j > i {
			inner := strings.TrimSpace(s[i+1 : j])
			if c := CanonicalHostPort(inner); c != "" {
				return c
			}
		}
	}
	if c := joinCanonicalHostPort(s); c != "" {
		return c
	}
	if u, err := url.Parse(s); err == nil && u.Host != "" {
		return joinCanonicalHostPort(u.Host)
	}
	return s
}

func joinCanonicalHostPort(h string) string {
	h = strings.TrimSpace(h)
	if h == "" {
		return ""
	}
	host, port, err := net.SplitHostPort(h)
	if err != nil {
		return ""
	}
	host = strings.TrimSpace(host)
	port = strings.TrimSpace(port)
	if ip := net.ParseIP(strings.Trim(host, "[]")); ip != nil {
		return net.JoinHostPort(ip.String(), port)
	}
	return strings.ToLower(host) + ":" + port
}

func MatchPreferred(addrs []string, preferred string) int {
	want := CanonicalHostPort(preferred)
	if want == "" {
		return -1
	}
	for i, a := range addrs {
		if CanonicalHostPort(a) == want {
			return i
		}
		if joinCanonicalHostPort(strings.TrimSpace(a)) == want {
			return i
		}
		if strings.EqualFold(strings.TrimSpace(a), strings.TrimSpace(preferred)) {
			return i
		}
	}
	return -1
}
