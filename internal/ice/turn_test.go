package ice

import "testing"

func TestParseTurnURL(t *testing.T) {
	h, u, p, err := parseTurnURL("turn:me:secret@example.com:3478")
	if err != nil || h != "example.com:3478" || u != "me" || p != "secret" {
		t.Fatalf("got %q %q %q err=%v", h, u, p, err)
	}
	h2, _, _, err := parseTurnURL("turn:stun.example.com:3478")
	if err != nil || h2 != "stun.example.com:3478" {
		t.Fatal(h2, err)
	}
}

func TestParseErrorCodeAttr(t *testing.T) {
	v := []byte{0, 0, 4, 1}
	if c := parseErrorCodeAttr(v); c != 401 {
		t.Fatal(c)
	}
}
