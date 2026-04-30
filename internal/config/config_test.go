package config

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"dev.c0redev.volter/internal/protocol"
)

func TestQuicSkipVerifyJSONOmitMeansLax(t *testing.T) {
	var c Config
	if err := json.Unmarshal([]byte(`{"server":"h:1","token":"t"}`), &c); err != nil {
		t.Fatal(err)
	}
	if c.QuicSkipVerify != nil {
		t.Fatalf("want nil, got %#v", c.QuicSkipVerify)
	}
	if !c.QuicSkipVerifyEffective() {
		t.Error("missing quicSkipVerify must default to lax QUIC")
	}
	b, err := json.Marshal(c)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(b, &c); err != nil {
		t.Fatal(err)
	}
	if !c.QuicSkipVerifyEffective() {
		t.Error("round-trip without key stays lax")
	}
}

func TestSaveLoadDelete(t *testing.T) {
	dir := t.TempDir()
	os.Setenv("XDG_CONFIG_HOME", dir)
	defer os.Unsetenv("XDG_CONFIG_HOME")

	c := Config{Server: "1.2.3.4:443", Token: "secret"}
	if err := Save("test", c); err != nil {
		t.Fatal(err)
	}
	got, err := LoadByName("test")
	if err != nil {
		t.Fatal(err)
	}
	if got.Server != c.Server || got.Token != c.Token {
		t.Errorf("got %+v", got)
	}
	if err := Delete("test"); err != nil {
		t.Fatal(err)
	}
	_, err = LoadByName("test")
	if err == nil {
		t.Error("want error after delete")
	}
}

func TestSanitizeName(t *testing.T) {
	for _, tc := range []struct {
		in, want string
	}{
		{"abc", "abc"},
		{"a-b_c1", "a-b_c1"},
		{"  x  ", "x"},
		{"a b", "ab"},
		{"", "default"},
		{"!!!", "default"},
	} {
		got := SanitizeName(tc.in)
		if got != tc.want {
			t.Errorf("SanitizeName(%q)=%q want %q", tc.in, got, tc.want)
		}
	}
}

func TestParseConnection(t *testing.T) {
	uriJSON := `{"s":"1.2.3.4:443","k":"VIKDKKKK23K3KKJ4JK3"}`
	uriB64 := base64.RawURLEncoding.EncodeToString([]byte(uriJSON))
	for _, tc := range []struct {
		in         string
		wantServer string
		wantToken  string
		ok         bool
	}{
		{"1.2.3.4:443:abc:def", "1.2.3.4:443", "abc:def", true},
		{"[2001:db8::1]:443:tok", "[2001:db8::1]:443", "tok", true},
		{"[2001:db8::1]:443:abc:def", "[2001:db8::1]:443", "abc:def", true},
		{"volter://" + uriB64, "1.2.3.4:443", "VIKDKKKK23K3KKJ4JK3", true},
		{"volter://" + uriB64 + "?x=1", "1.2.3.4:443", "VIKDKKKK23K3KKJ4JK3", true},
		{"volter://badbase64", "", "", false},
		{"bad format", "", "", false},
		{"[2001:db8::1]  :443:tok", "", "", false},
	} {
		server, token, ok := ParseConnection(tc.in)
		if ok != tc.ok {
			t.Fatalf("ParseConnection(%q) ok=%v want %v", tc.in, ok, tc.ok)
		}
		if ok {
			if server != tc.wantServer || token != tc.wantToken {
				t.Fatalf("ParseConnection(%q)=%q %q want %q %q", tc.in, server, token, tc.wantServer, tc.wantToken)
			}
		}
	}
}

func TestBuildConnectionURI(t *testing.T) {
	uri := BuildConnectionURI("1.2.3.4:443", "abc:def")
	if uri == "" {
		t.Fatal("empty uri")
	}
	server, token, ok := ParseConnection(uri)
	if !ok {
		t.Fatal("parse uri failed")
	}
	if server != "1.2.3.4:443" || token != "abc:def" {
		t.Fatalf("got %q %q", server, token)
	}
}

func TestProtection(t *testing.T) {
	dir := t.TempDir()
	os.Setenv("XDG_CONFIG_HOME", dir)
	defer os.Unsetenv("XDG_CONFIG_HOME")

	p := ProtectionOptions{PadS1: 32, JunkCount: 3}
	if err := SaveProtection(p); err != nil {
		t.Fatal(err)
	}
	got, err := LoadProtection()
	if err != nil {
		t.Fatal(err)
	}
	if got.PadS1 != p.PadS1 || got.JunkCount != p.JunkCount {
		t.Errorf("got %+v", got)
	}
}

func TestLoadProtectionNotExist(t *testing.T) {
	dir := t.TempDir()
	os.RemoveAll(filepath.Join(dir, "volter"))
	os.Setenv("XDG_CONFIG_HOME", dir)
	defer os.Unsetenv("XDG_CONFIG_HOME")

	got, err := LoadProtection()
	if err != nil {
		t.Fatal(err)
	}
	if got.PadS1 != 0 || got.JunkCount != 0 {
		t.Errorf("want zero value, got %+v", got)
	}
}

func TestMergeProbeObfsIntoProtection(t *testing.T) {
	p := &ProtectionOptions{JunkCount: 3}
	caps := &protocol.ServerHelloCaps{
		ObfsProfileID: 9,
		FeatureBits:   protocol.FeaturePolyHandshake | protocol.FeatureRelayServer,
		PathTTL:       3,
		RelayFlags:    1,
	}
	out := MergeProbeObfsIntoProtection(p, caps)
	if out.JunkCount != 3 || out.ProbeObfsProfileID != 9 || out.PreambleProfile != protocol.PreambleRotate || !out.PreambleRotate {
		t.Fatalf("got %+v", out)
	}
	if out.RelayHop != 1 || out.RelayMaxHop != 3 || out.RelayBudgetKbps == 0 {
		t.Fatalf("got %+v", out)
	}
	out2 := MergeProbeObfsIntoProtection(nil, caps)
	if out2.ProbeObfsProfileID != 9 || out2.PreambleProfile != protocol.PreambleRotate || !out2.PreambleRotate {
		t.Fatalf("got %+v", out2)
	}
}

func TestMergeProbeObfsDoesNotForceRotateWithoutFeature(t *testing.T) {
	p := &ProtectionOptions{}
	caps := &protocol.ServerHelloCaps{ObfsProfileID: 4}
	out := MergeProbeObfsIntoProtection(p, caps)
	if out.ProbeObfsProfileID != 4 {
		t.Fatalf("got %+v", out)
	}
	if out.PreambleProfile != "" || out.PreambleRotate {
		t.Fatalf("rotate must stay off without poly feature: %+v", out)
	}
	if out.RelayHop != 0 || out.RelayMaxHop != 0 {
		t.Fatalf("relay must stay off without relay feature: %+v", out)
	}
}
