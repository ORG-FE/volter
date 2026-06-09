package tui

import (
	"testing"

	"dev.c0redev.volter/internal/config"
)

func TestConfigFromConnFormInputsPreservesDexotePub(t *testing.T) {
	pub := "AAECAwQFBgcICQoLDA0ODxA="
	uri := config.BuildConnectionURI("1.2.3.4:443", "tok", pub)
	if uri == "" {
		t.Fatal("BuildConnectionURI returned empty")
	}

	inputs := newInputsWithValues("name", uri, "", "", "", "auto", "", "", "", "", "")
	out, errMsg := configFromConnFormInputs(inputs)
	if errMsg != "" {
		t.Fatalf("configFromConnFormInputs error: %s", errMsg)
	}
	if out.Server != "1.2.3.4:443" || out.Token != "tok" {
		t.Fatalf("server/token mismatch: %q %q", out.Server, out.Token)
	}
	if out.DexoteServerPub != pub {
		t.Fatalf("dexote pub dropped: got %q want %q", out.DexoteServerPub, pub)
	}
}

func TestConfigFromConnFormInputsNoPubStaysEmpty(t *testing.T) {
	uri := config.BuildConnectionURI("9.9.9.9:443", "tok")
	inputs := newInputsWithValues("name", uri, "", "", "", "auto", "", "", "", "", "")
	out, errMsg := configFromConnFormInputs(inputs)
	if errMsg != "" {
		t.Fatalf("configFromConnFormInputs error: %s", errMsg)
	}
	if out.DexoteServerPub != "" {
		t.Fatalf("expected empty pub, got %q", out.DexoteServerPub)
	}
}
