package vpn

import (
	"testing"

	"dev.c0redev.volter/internal/config"
)

func TestClusterPollPaths_defaults(t *testing.T) {
	m, s := clusterPollPaths(nil)
	if m != defaultClusterMapPath || s != defaultClusterSessionsPath {
		t.Fatalf("defaults: %q %q", m, s)
	}
}

func TestClusterPollPaths_custom(t *testing.T) {
	p := &config.ProtectionOptions{
		ClusterMapPath:      "custom/map.json",
		ClusterSessionsPath: "/x/sess.json",
	}
	m, s := clusterPollPaths(p)
	if m != "/custom/map.json" {
		t.Fatalf("map: %q", m)
	}
	if s != "/x/sess.json" {
		t.Fatalf("sess: %q", s)
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
