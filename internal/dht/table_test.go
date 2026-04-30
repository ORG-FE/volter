package dht

import (
	"testing"

	"dev.c0redev.volter/internal/discovery"
)

func TestTableNearest(t *testing.T) {
	tab := NewTable("seed-a")
	tab.Insert(discovery.RelayNode{ID: "id1", Endpoints: []string{"1.1.1.1:443"}, UpdatedAt: 1})
	tab.Insert(discovery.RelayNode{ID: "id2", Endpoints: []string{"2.2.2.2:443"}, UpdatedAt: 2})
	n := tab.Nearest(1)
	if len(n) != 1 || n[0].ID == "" {
		t.Fatalf("nearest: %+v", n)
	}
	if tab.Len() != 2 {
		t.Fatal()
	}
}
