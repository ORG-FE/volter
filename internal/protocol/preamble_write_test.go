package protocol

import (
	"bytes"
	"testing"
)

func TestResolvePreambleKind_tlsJunkStyle(t *testing.T) {
	got := ResolvePreambleKind("tls", "", false, false, 1, "tok", 0)
	if got != PreambleTLSRecord {
		t.Fatalf("got %q want tls_record", got)
	}
}

func TestResolvePreambleKind_explicit(t *testing.T) {
	for _, tc := range []struct {
		prof string
		want string
	}{
		{"tls_record", PreambleTLSRecord},
		{"TLS_CH_SHAPE", PreambleTLSCHShape},
		{"smb1_shape", PreambleSMB1Shape},
		{"mc_frame", PreambleMcFrame},
	} {
		got := ResolvePreambleKind("", tc.prof, false, false, 0, "", 0)
		if got != tc.want {
			t.Errorf("profile %q: got %q want %q", tc.prof, got, tc.want)
		}
	}
}

func TestWritePreamble_kindsNonEmpty(t *testing.T) {
	for _, kind := range []string{PreambleTLSRecord, PreambleTLSCHShape, PreambleSMB1Shape, PreambleMcFrame} {
		var buf bytes.Buffer
		if err := WritePreamble(&buf, kind, 2, 80, 200, "", nil); err != nil {
			t.Fatalf("%s: %v", kind, err)
		}
		if buf.Len() < 50 {
			t.Fatalf("%s: too short %d", kind, buf.Len())
		}
	}
}

func TestWritePreamble_rotateDeterministic(t *testing.T) {
	a := ResolvePreambleKind("", PreambleRotate, false, false, 42, "mytoken", 0)
	b := ResolvePreambleKind("", PreambleRotate, false, false, 42, "mytoken", 0)
	if a != b {
		t.Fatalf("rotate not stable: %q vs %q", a, b)
	}
}
