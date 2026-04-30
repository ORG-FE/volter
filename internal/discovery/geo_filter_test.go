package discovery

import (
	"context"
	"testing"
)

func TestFilterRelayNodesByGeoDeny(t *testing.T) {
	nodes := []RelayNode{
		{ID: "a", Endpoints: []string{"11.11.11.11:443"}, UpdatedAt: 100},
	}
	deny := []string{"US"}
	got := FilterRelayNodesByGeo(context.Background(), nodes, nil, deny, func(string) (string, error) {
		return "US", nil
	})
	if len(got) != 0 {
		t.Fatalf("deny US: got %d", len(got))
	}
}

func TestFilterRelayNodesByGeoAllow(t *testing.T) {
	nodes := []RelayNode{
		{ID: "a", Endpoints: []string{"9.9.9.9:443"}, UpdatedAt: 100},
	}
	allow := []string{"DE"}
	got := FilterRelayNodesByGeo(context.Background(), nodes, allow, nil, func(string) (string, error) {
		return "DE", nil
	})
	if len(got) != 1 || got[0].ID != "a" {
		t.Fatalf("allow DE: %+v", got)
	}
}

func TestFilterRelayNodesByGeoRegionHint(t *testing.T) {
	nodes := []RelayNode{
		{ID: "x", Endpoints: []string{}, Region: "RU", UpdatedAt: 100},
	}
	got := FilterRelayNodesByGeo(context.Background(), nodes, []string{"RU"}, nil, nil)
	if len(got) != 1 {
		t.Fatal()
	}
}
