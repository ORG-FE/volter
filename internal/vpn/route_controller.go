package vpn

import (
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Flow struct {
	DstIP   net.IP
	DstPort uint16
}

type RouteDirective struct {
	Target    string
	Mode      string
	PeerID    string
	Endpoint  string
	RouteID   string
	ExpiresAt time.Time
	Reason    string
}

type RouteController struct {
	mu    sync.Mutex
	items map[string]RouteDirective
}

func NewRouteController() *RouteController {
	return &RouteController{items: make(map[string]RouteDirective)}
}

func (c *RouteController) Apply(d RouteDirective) {
	if c == nil {
		return
	}
	key := strings.TrimSpace(d.Target)
	if key == "" || d.ExpiresAt.IsZero() {
		return
	}
	c.mu.Lock()
	c.items[key] = d
	c.mu.Unlock()
}

func (c *RouteController) DirectiveFor(f Flow, now time.Time) (RouteDirective, bool) {
	if c == nil || f.DstIP == nil || f.DstPort == 0 {
		return RouteDirective{}, false
	}
	key := net.JoinHostPort(f.DstIP.String(), portString(f.DstPort))
	c.mu.Lock()
	defer c.mu.Unlock()
	d, ok := c.items[key]
	if !ok {
		return RouteDirective{}, false
	}
	if !d.ExpiresAt.After(now) {
		delete(c.items, key)
		return RouteDirective{}, false
	}
	return d, true
}

func (c *RouteController) DirectiveForTarget(target string, now time.Time) (RouteDirective, bool) {
	if c == nil {
		return RouteDirective{}, false
	}
	key := strings.TrimSpace(target)
	if key == "" {
		return RouteDirective{}, false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	d, ok := c.items[key]
	if !ok {
		return RouteDirective{}, false
	}
	if !d.ExpiresAt.After(now) {
		delete(c.items, key)
		return RouteDirective{}, false
	}
	return d, true
}

func portString(port uint16) string {
	return strconv.Itoa(int(port))
}
