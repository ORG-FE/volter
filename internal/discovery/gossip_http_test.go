package discovery

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestFetchGossipHTTPEnvelope(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"nodes":[{"id":"n1","endpoints":["1.1.1.1:1"],"class":"peer","updatedAt":3}]}`))
	}))
	defer srv.Close()
	got, err := FetchGossipHTTP(context.Background(), srv.URL)
	if err != nil || len(got) != 1 || got[0].ID != "n1" {
		t.Fatalf("got %#v err=%v", got, err)
	}
}
