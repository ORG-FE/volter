package dht

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"dev.c0redev.volter/internal/discovery"
)

func TestPullMergeFromURL(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(struct {
			Nodes []discovery.RelayNode `json:"nodes"`
		}{Nodes: []discovery.RelayNode{{ID: "x", Endpoints: []string{"1.1.1.1:1"}, Class: "peer", UpdatedAt: 1}}})
	}))
	defer srv.Close()
	tab := NewTable("pull-test")
	if err := tab.PullMergeFromURL(context.Background(), srv.URL); err != nil {
		t.Fatal(err)
	}
	if tab.Len() != 1 {
		t.Fatalf("len=%d", tab.Len())
	}
}
