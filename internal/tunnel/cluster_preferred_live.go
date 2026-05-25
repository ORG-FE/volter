package tunnel

import (
	"strings"
	"sync/atomic"
)

var liveClusterPreferredServer atomic.Value

func SetLiveClusterPreferredServer(server string) {
	s := strings.TrimSpace(server)
	if s == "" {
		liveClusterPreferredServer.Store("")
		return
	}
	liveClusterPreferredServer.Store(s)
}

func LiveClusterPreferredServerValue() string {
	v := liveClusterPreferredServer.Load()
	if v == nil {
		return ""
	}
	s, _ := v.(string)
	return strings.TrimSpace(s)
}
