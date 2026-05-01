package routeorch

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestPostClusterInvite_OK(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/volter/cluster-invite" || r.Method != http.MethodPost {
			t.Fatalf("bad req %s %s", r.Method, r.URL.Path)
		}
		if r.Header.Get("X-Volter-Cluster-Key") != "sekret" {
			t.Fatalf("missing key")
		}
		_, _ = io.WriteString(w, `{"status":"accepted","redirectHostPort":"r.example:443"}`+"\n")
	}))
	defer ts.Close()
	hostPort := strings.TrimPrefix(strings.TrimPrefix(ts.URL, "http://"), "https://")
	resp, err := PostClusterInvite(context.Background(), hostPort, "sekret", "/volter/cluster-invite", InviteRequest{
		ClientID: "c1", Nonce: "n", TargetNodeID: "node-r", CorrelationID: "x", DeadlineMs: 9999999999999,
	})
	if err != nil {
		t.Fatal(err)
	}
	if resp.Status != "accepted" || resp.RedirectHostPort != "r.example:443" {
		t.Fatalf("%+v", resp)
	}
}

func TestPostClusterPeerHandshake_OK(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/volter/cluster-peer-handshake" {
			t.Fatalf("path %s", r.URL.Path)
		}
		_, _ = io.WriteString(w, `{"status":"accepted","redirectHostPort":"r.example:443"}`)
	}))
	defer ts.Close()
	hostPort := strings.TrimPrefix(strings.TrimPrefix(ts.URL, "http://"), "https://")
	resp, err := PostClusterPeerHandshake(context.Background(), hostPort, "", "/volter/cluster-peer-handshake", PeerHandshakeRequest{
		InviteID: "inv", TargetNodeID: "node-r", CorrelationID: "y", DeadlineMs: 9,
	})
	if err != nil {
		t.Fatal(err)
	}
	if resp.Status != "accepted" {
		t.Fatalf("%+v", resp)
	}
}
