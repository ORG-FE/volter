package discovery

import (
	"context"
	"net"
	"strings"
	"sync"

	"dev.c0redev.volter/internal/geo"
)

type GeoResolver func(hostOrEndpoint string) (countryCode string, err error)

func DefaultGeoResolver(hostOrEndpoint string) (string, error) {
	info, err := geo.Fetch(hostOrEndpoint)
	if err != nil {
		return "", err
	}
	return strings.ToUpper(strings.TrimSpace(info.CountryCode)), nil
}

func firstEndpointHost(n RelayNode) string {
	for _, ep := range n.Endpoints {
		ep = strings.TrimSpace(ep)
		if ep == "" {
			continue
		}
		h, _, err := net.SplitHostPort(ep)
		if err != nil {
			h = ep
		}
		return h
	}
	return ""
}

func FilterRelayNodesByGeo(ctx context.Context, nodes []RelayNode, allow, deny []string, resolve GeoResolver) []RelayNode {
	if len(allow) == 0 && len(deny) == 0 {
		out := make([]RelayNode, len(nodes))
		copy(out, nodes)
		return out
	}
	if resolve == nil {
		resolve = DefaultGeoResolver
	}
	aSet, dSet := countrySets(allow, deny)

	sem := make(chan struct{}, 8)
	var wg sync.WaitGroup
	var mu sync.Mutex
	out := make([]RelayNode, 0, len(nodes))

	for _, n := range nodes {
		select {
		case <-ctx.Done():
			wg.Wait()
			return out
		default:
		}
		host := firstEndpointHost(n)
		if host == "" {
			if hint := strings.TrimSpace(n.Region); hint != "" {
				cc := strings.ToUpper(hint)
				if geoAllowed(cc, aSet, dSet) {
					mu.Lock()
					out = append(out, n)
					mu.Unlock()
				}
			}
			continue
		}
		wg.Add(1)
		go func(node RelayNode, h string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			select {
			case <-ctx.Done():
				return
			default:
			}
			cc, err := resolve(h)
			if err != nil {
				mu.Lock()
				out = append(out, node)
				mu.Unlock()
				return
			}
			cc = strings.ToUpper(strings.TrimSpace(cc))
			if !geoAllowed(cc, aSet, dSet) {
				return
			}
			mu.Lock()
			out = append(out, node)
			mu.Unlock()
		}(n, host)
	}
	wg.Wait()
	return out
}

func countrySets(allow, deny []string) (map[string]struct{}, map[string]struct{}) {
	aSet := make(map[string]struct{})
	for _, s := range allow {
		s = strings.ToUpper(strings.TrimSpace(s))
		if s != "" {
			aSet[s] = struct{}{}
		}
	}
	dSet := make(map[string]struct{})
	for _, s := range deny {
		s = strings.ToUpper(strings.TrimSpace(s))
		if s != "" {
			dSet[s] = struct{}{}
		}
	}
	return aSet, dSet
}

func geoAllowed(cc string, allow, deny map[string]struct{}) bool {
	if len(deny) > 0 {
		if _, bad := deny[cc]; bad {
			return false
		}
	}
	if len(allow) == 0 {
		return true
	}
	_, ok := allow[cc]
	return ok
}
