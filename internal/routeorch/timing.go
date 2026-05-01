package routeorch

import "time"

const (
	ProbeDefaultTimeout         = 5 * time.Second
	StunGatherBudgetDefault     = 12 * time.Second
	InviteTTLDefault            = 5 * time.Second
	ClusterPeerHandshakeTimeout = 5 * time.Second
)

func InviteTTL() time.Duration { return InviteTTLDefault }

func ClusterHandshakeTimeout() time.Duration { return ClusterPeerHandshakeTimeout }

func StunGatherBudget() time.Duration { return StunGatherBudgetDefault }
