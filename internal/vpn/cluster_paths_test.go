package vpn

import (
	"testing"

	"dev.c0redev.volter/internal/config"
)

func TestClusterPollPaths_defaults(t *testing.T) {
	m, s, c := clusterPollPaths(nil)
	if m != defaultClusterMapPath || s != defaultClusterSessionsPath || c != defaultClusterClientsPath {
		t.Fatalf("defaults: %q %q %q", m, s, c)
	}
}

func TestClusterPollPaths_custom(t *testing.T) {
	p := &config.ProtectionOptions{
		ClusterMapPath:      "custom/map.json",
		ClusterSessionsPath: "/x/sess.json",
		ClusterClientsPath:  "api/clients",
	}
	m, s, c := clusterPollPaths(p)
	if m != "/custom/map.json" {
		t.Fatalf("map: %q", m)
	}
	if s != "/x/sess.json" {
		t.Fatalf("sess: %q", s)
	}
	if c != "/api/clients" {
		t.Fatalf("clients: %q", c)
	}
}

func TestNormalizeClusterHTTPPath(t *testing.T) {
	if normalizeClusterHTTPPath("a/b") != "/a/b" {
		t.Fatal()
	}
	if normalizeClusterHTTPPath("/z") != "/z" {
		t.Fatal()
	}
}

func TestClusterInvitePaths(t *testing.T) {
	if ClusterInviteHTTPPath(nil) != "" {
		t.Fatal()
	}
	p := &config.ProtectionOptions{ClusterInvitePath: "invite/x", ClusterPeerHandshakePath: "/peer/y"}
	if ClusterInviteHTTPPath(p) != "/invite/x" {
		t.Fatal()
	}
	if ClusterPeerHandshakeHTTPPath(p) != "/peer/y" {
		t.Fatal()
	}
}
