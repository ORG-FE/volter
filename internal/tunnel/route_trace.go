package tunnel

import "sync/atomic"

var lastRouteTarget atomic.Value
var lastRoutePlan atomic.Value
var lastRouteHop atomic.Value
var lastRouteAckReason atomic.Value

func SetRouteTrace(target, plan, hop, reason string) {
	if target != "" {
		lastRouteTarget.Store(target)
	}
	if plan != "" {
		lastRoutePlan.Store(plan)
	}
	if hop != "" {
		lastRouteHop.Store(hop)
	}
	if reason != "" {
		lastRouteAckReason.Store(reason)
	}
}

func LastRouteTrace() (target, plan, hop, reason string) {
	if v := lastRouteTarget.Load(); v != nil {
		target = v.(string)
	}
	if v := lastRoutePlan.Load(); v != nil {
		plan = v.(string)
	}
	if v := lastRouteHop.Load(); v != nil {
		hop = v.(string)
	}
	if v := lastRouteAckReason.Load(); v != nil {
		reason = v.(string)
	}
	return
}
