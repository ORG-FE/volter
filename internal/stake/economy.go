package stake

import (
	"context"

	"dev.c0redev.volter/internal/discovery"
)

type Economy struct {
	RegistryURL        string
	RegistryPubKey     string
	ReputationFile     string
	StakeBonusHTTPURL  string
	StakeMerkleFile    string
	StakeMerkleRootURL string
}

func (e Economy) MergeStake(ctx context.Context, nodes []discovery.RelayNode) []discovery.RelayNode {
	out := ApplyRegistry(ctx, nodes, e.RegistryURL, e.RegistryPubKey)
	out = MergeReputation(out, e.ReputationFile)
	out = MergeHTTPBonus(ctx, out, e.StakeBonusHTTPURL)
	return MergeMerkleFromSources(ctx, out, e.StakeMerkleFile, e.StakeMerkleRootURL)
}
