package dht

import (
	"context"
	"strings"
	"sync"
	"time"

	"dev.c0redev.volter/internal/discovery"
)

type NodeFilter func(ctx context.Context, nodes []discovery.RelayNode) []discovery.RelayNode

func IterativeFindNode(ctx context.Context, tab *Table, secret string, target [32]byte, seedAddrs []string, k, alpha, maxRounds int, filter NodeFilter) {
	if tab == nil || maxRounds <= 0 || alpha <= 0 {
		return
	}
	if k < 1 {
		k = 16
	}
	if k > 256 {
		k = 256
	}
	seen := make(map[string]struct{})
	for round := 0; round < maxRounds; round++ {
		addrs := pickQueryAddrs(tab, target, seedAddrs, seen, alpha)
		if len(addrs) == 0 {
			return
		}
		var mu sync.Mutex
		var batch []discovery.RelayNode
		var wg sync.WaitGroup
		for _, addr := range addrs {
			addr := addr
			seen[addr] = struct{}{}
			wg.Add(1)
			go func() {
				defer wg.Done()
				sub, cancel := context.WithTimeout(ctx, 8*time.Second)
				defer cancel()
				_ = UDPPing(sub, addr, secret)
				raw, err := UDPFindNode(sub, addr, secret, target, k)
				if err != nil {
					return
				}
				if filter != nil {
					raw = filter(ctx, raw)
				}
				if len(raw) == 0 {
					return
				}
				mu.Lock()
				batch = append(batch, raw...)
				mu.Unlock()
			}()
		}
		wg.Wait()
		if len(batch) == 0 {
			continue
		}
		tab.Merge(batch)
	}
}

func pickQueryAddrs(tab *Table, target [32]byte, seedAddrs []string, seen map[string]struct{}, alpha int) []string {
	var out []string
	for _, a := range seedAddrs {
		if len(out) >= alpha {
			break
		}
		a = strings.TrimSpace(a)
		if a == "" {
			continue
		}
		if _, ok := seen[a]; ok {
			continue
		}
		out = append(out, a)
	}
	near := tab.NearestTo(target, 512)
	for _, n := range near {
		if len(out) >= alpha {
			break
		}
		a := strings.TrimSpace(n.DhtRPC)
		if a == "" {
			continue
		}
		if _, ok := seen[a]; ok {
			continue
		}
		out = append(out, a)
	}
	return out
}
