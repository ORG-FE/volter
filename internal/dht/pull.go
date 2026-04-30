package dht

import (
	"context"

	"dev.c0redev.volter/internal/discovery"
)

func (t *Table) PullMergeFromURL(ctx context.Context, rawURL string) error {
	nodes, err := discovery.FetchGossipHTTP(ctx, rawURL)
	if err != nil {
		return err
	}
	t.Merge(nodes)
	return nil
}
