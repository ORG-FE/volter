package tunnel

import (
	"net"
	"strings"
	"sync/atomic"
)

var activeVolterServer atomic.Value

func ClearActiveVolterServer() {
	activeVolterServer.Store("")
}

func setActiveVolterServerAddr(addr string) {
	addr = strings.TrimSpace(addr)
	if addr == "" {
		return
	}
	activeVolterServer.Store(addr)
}

func SetActiveVolterServerForTest(addr string) {
	setActiveVolterServerAddr(addr)
}

func ActiveVolterServer() string {
	v := activeVolterServer.Load()
	if v == nil {
		return ""
	}
	s, _ := v.(string)
	return s
}

func PickServerAddrForQUIC(serverAddrs []string, quicServer string) string {
	if len(serverAddrs) == 0 {
		return ""
	}
	qa, _, err := ResolveQUICDialAddr(serverAddrs, quicServer)
	if err != nil {
		return strings.TrimSpace(serverAddrs[0])
	}
	qh, _, err := net.SplitHostPort(qa)
	if err != nil {
		return strings.TrimSpace(serverAddrs[0])
	}
	qh = strings.Trim(strings.ToLower(qh), "[]")
	for _, sa := range serverAddrs {
		sa = strings.TrimSpace(sa)
		if sa == "" {
			continue
		}
		sh, _, err := net.SplitHostPort(sa)
		if err != nil {
			if strings.EqualFold(strings.Trim(sa, "[]"), qh) {
				return sa
			}
			continue
		}
		sh = strings.Trim(strings.ToLower(sh), "[]")
		if sh == qh {
			return sa
		}
	}
	return strings.TrimSpace(serverAddrs[0])
}
