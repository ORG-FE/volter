package tunnel

import (
	"strings"
	"sync/atomic"
)

var liveRouteMode atomic.Value

func SetLiveRouteMode(mode string) {
	mode = strings.ToLower(strings.TrimSpace(mode))
	if mode == "" {
		liveRouteMode.Store("")
		liveClusterPreferredServer.Store("")
		return
	}
	liveRouteMode.Store(mode)
	// clear clusterPreferredServer when mode doesn't support exit routing
	if mode != "server_relay" {
		liveClusterPreferredServer.Store("")
	}
}

func ClearLiveRouteMode() {
	liveRouteMode.Store("")
}

func liveRouteModeValue() string {
	v := liveRouteMode.Load()
	if v == nil {
		return ""
	}
	s, _ := v.(string)
	return strings.TrimSpace(s)
}
