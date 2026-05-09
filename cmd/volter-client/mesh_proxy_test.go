package main

import (
	"testing"

	"dev.c0redev.volter/internal/config"
)

func TestMeshProxyModeError(t *testing.T) {
	if err := meshProxyModeError(nil); err != nil {
		t.Fatal(err)
	}
	if err := meshProxyModeError(&config.MeshConfig{}); err != nil {
		t.Fatal(err)
	}
	if err := meshProxyModeError(&config.MeshConfig{Enabled: true}); err == nil {
		t.Fatal("expected mesh proxy mode error")
	}
}
