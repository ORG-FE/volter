package config

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"unicode/utf8"

	"dev.c0redev.volter/internal/dpi"
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

func TestMeshConfigVolunteerIsExplicit(t *testing.T) {
	raw := `{
		"server":"h:1",
		"token":"t",
		"mesh":{
			"enabled":true,
			"volunteer":{"enabled":false},
			"p2p":{"enabled":true},
			"serverRelay":{"enabled":true},
			"stun":{"enabled":true,"servers":["stun.l.google.com:19302"]},
			"discovery":{"dhtRpcSeedPeers":["1.2.3.4:4001"]}
		}
	}`
	var c Config
	if err := json.Unmarshal([]byte(raw), &c); err != nil {
		t.Fatal(err)
	}
	if c.Mesh == nil || !c.Mesh.Enabled {
		t.Fatalf("mesh must be enabled: %+v", c.Mesh)
	}
	if c.Mesh.Volunteer.Enabled {
		t.Fatalf("volunteer must stay explicitly disabled: %+v", c.Mesh.Volunteer)
	}
	if !c.Mesh.P2P.Enabled || !c.Mesh.ServerRelay.Enabled || !c.Mesh.STUN.Enabled {
		t.Fatalf("mesh route blocks lost: %+v", c.Mesh)
	}
	b, err := json.Marshal(c)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(b), `"relay"`) {
		t.Fatalf("new configs must not emit legacy relay: %s", string(b))
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

func TestMergeAntiDpiTransportTopUpInPlace(t *testing.T) {
	t.Setenv("VOLTER_NO_ANTIDPI_ENRICH", "")
	t.Setenv("VOLTER_ANTIDPI_PRESET", "")
	
	out := MergeAntiDpiTransportTopUpInPlace(nil, "tcp")
	if out == nil || out.JunkCount != 6 || !out.DpiVolterTransportObfuscate || !strings.EqualFold(out.DpiLocalEngine, "embedded") {
		t.Fatalf("tcp baseline: %+v", out)
	}
	custom := &ProtectionOptions{JunkCount: 9, JunkMin: 400, JunkMax: 800}
	out2 := MergeAntiDpiTransportTopUpInPlace(custom, "tcp")
	if out2.JunkCount != 9 || out2.JunkMin != 400 {
		t.Fatalf("expected preserved junk: %+v", out2)
	}

	t.Setenv("VOLTER_NO_ANTIDPI_ENRICH", "1")
	out3 := MergeAntiDpiTransportTopUpInPlace(nil, "tcp")
	if out3 != nil {
		t.Fatalf("disabled + nil want nil, got %+v", out3)
	}
}

func TestApplyAntiDpiPreset(t *testing.T) {
	tests := []struct {
		preset    AntiDpiPreset
		transport string
		wantJunk  int
		wantEmbed bool
	}{
		{AntiDpiPresetNone, "tcp", 0, false},
		{AntiDpiPresetLight, "tcp", 3, true},
		{AntiDpiPresetModerate, "tcp", 6, true},
		{AntiDpiPresetAggressive, "tcp", 12, true},
		{AntiDpiPresetParanoid, "tcp", 20, true},
	}
	for _, tt := range tests {
		t.Run(string(tt.preset), func(t *testing.T) {
			p := ApplyAntiDpiPreset(tt.preset, tt.transport)
			if p == nil {
				t.Fatal("nil result")
			}
			if p.JunkCount != tt.wantJunk {
				t.Errorf("junk: got %d, want %d", p.JunkCount, tt.wantJunk)
			}
			if tt.wantEmbed && p.DpiLocalEmbedded == nil {
				t.Error("expected embedded config")
			}
		})
	}
}

func TestAntiDpiPresetEnv(t *testing.T) {
	t.Setenv("VOLTER_ANTIDPI_PRESET", "aggressive")
	p := MergeAntiDpiTransportTopUpInPlace(nil, "tcp")
	if p == nil || p.JunkCount != 12 {
		t.Fatalf("aggressive preset: %+v", p)
	}
	
	// User override должен работать
	custom := &ProtectionOptions{JunkCount: 99}
	p2 := MergeAntiDpiTransportTopUpInPlace(custom, "tcp")
	if p2.JunkCount != 99 {
		t.Errorf("user override failed: got %d", p2.JunkCount)
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

func TestClampDpiLocalPreset(t *testing.T) {
	short := "-ttl 8 --foo"
	if ClampDpiLocalPreset(short) != short {
		t.Fatal("short string changed")
	}
	var huge strings.Builder
	for i := 0; i < dpi.MaxGossipPresetRunes+50; i++ {
		huge.WriteByte('a')
	}
	out := ClampDpiLocalPreset(huge.String())
	if utf8.RuneCountInString(out) != dpi.MaxGossipPresetRunes {
		t.Fatalf("rune count got %d want %d", utf8.RuneCountInString(out), dpi.MaxGossipPresetRunes)
	}
	multi := strings.Repeat("ж", dpi.MaxGossipPresetRunes+10)
	out2 := ClampDpiLocalPreset(multi)
	if utf8.RuneCountInString(out2) != dpi.MaxGossipPresetRunes {
		t.Fatalf("multibyte rune count got %d", utf8.RuneCountInString(out2))
	}
}

func TestMergeDpiLocalEmbeddedDefaults(t *testing.T) {
	got := MergeDpiLocalEmbeddedDefaults(nil)
	if got.SplitAfter != 1 || got.TTLMillis != 8 || got.Disorder {
		t.Fatalf("nil defaults %+v", got)
	}
	got = MergeDpiLocalEmbeddedDefaults(&DpiLocalEmbedded{SplitAfter: 0, TTLMillis: 0})
	if got.SplitAfter != 1 || got.TTLMillis != 8 {
		t.Fatalf("zeros should fall back %+v", got)
	}
	got = MergeDpiLocalEmbeddedDefaults(&DpiLocalEmbedded{SplitAfter: 100, TTLMillis: 20, Disorder: true})
	if got.SplitAfter != 100 || got.TTLMillis != 20 || !got.Disorder {
		t.Fatalf("nonzero merge %+v", got)
	}
	got = MergeDpiLocalEmbeddedDefaults(&DpiLocalEmbedded{SplitAfter: 100_000, TTLMillis: 100_000})
	if got.SplitAfter != 65536 || got.TTLMillis != 60_000 {
		t.Fatalf("caps %+v", got)
	}
	got = MergeDpiLocalEmbeddedDefaults(&DpiLocalEmbedded{SplitAfter: 3, SplitAfter2: 2})
	if got.SplitAfter2 != 0 {
		t.Fatalf("split2 must clear when not after split1 %+v", got)
	}
	got = MergeDpiLocalEmbeddedDefaults(&DpiLocalEmbedded{SplitAfter: 2, SplitAfter2: 5, TTL2Millis: 9, JitterMaxMs: 10, LeadInMs: 3})
	if got.SplitAfter != 2 || got.SplitAfter2 != 5 || got.TTL2Millis != 9 || got.JitterMaxMs != 10 || got.LeadInMs != 3 {
		t.Fatalf("extra fields %+v", got)
	}
	got = MergeDpiLocalEmbeddedDefaults(&DpiLocalEmbedded{
		SplitAfter: 2, TTLMillis: 8,
		FakeSNI: true, FakeSNIHost: "example.com", SplitPosition: "sni",
		AutoTTL: true, TCPSegment: 3, OOBData: true, MultiSplit: 2,
	})
	if !got.FakeSNI || got.FakeSNIHost != "example.com" || got.SplitPosition != "sni" || !got.AutoTTL || !got.OOBData {
		t.Fatalf("dpi extras bool/string %+v", got)
	}
	if got.TCPSegment != 3 || got.MultiSplit != 2 {
		t.Fatalf("dpi extras int %+v", got)
	}
}

func TestStandaloneDpiUseExternalBin(t *testing.T) {
	ext := func(s string) *ProtectionOptions {
		return &ProtectionOptions{DpiLocalEngine: s}
	}
	for _, tc := range []struct {
		name string
		cfg  *Config
		want bool
	}{
		{"nil", nil, false},
		{"no protection", &Config{Server: "x", Token: "t"}, false},
		{"external", &Config{Server: "x", Token: "t", Protection: ext("external")}, true},
		{"embedded", &Config{Server: "x", Token: "t", Protection: &ProtectionOptions{DpiLocalEngine: "embedded", DpiLocalPreset: "--foo"}}, false},
		{"legacy preset only", &Config{Server: "x", Token: "t", Protection: &ProtectionOptions{DpiLocalPreset: "-p 1080"}}, true},
		{"empty engine no preset", &Config{Server: "x", Token: "t", Protection: &ProtectionOptions{}}, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := StandaloneDpiUseExternalBin(tc.cfg); got != tc.want {
				t.Fatalf("got %v want %v", got, tc.want)
			}
		})
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
