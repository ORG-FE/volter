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
		return
	}
	liveRouteMode.Store(mode)
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
