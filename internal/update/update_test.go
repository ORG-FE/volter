package update

import "testing"

func TestNewer(t *testing.T) {
	t.Parallel()

	cases := []struct {
		a    string
		b    string
		want bool
	}{
		{a: "v1.2.4", b: "v1.2.3", want: true},
		{a: "v1.3.0", b: "1.2.9", want: true},
		{a: "v2.0.0", b: "v10.0.0", want: false},
		{a: "v1.2.3", b: "v1.2.3", want: false},
	}

	for _, tc := range cases {
		if got := Newer(tc.a, tc.b); got != tc.want {
			t.Fatalf("Newer(%q, %q) = %v, want %v", tc.a, tc.b, got, tc.want)
		}
	}
}

func TestParseChecksum(t *testing.T) {
	t.Parallel()

	sum := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	got, err := parseChecksum("bad line\n"+sum+"  volter-client-linux-amd64\n", "volter-client-linux-amd64")
	if err != nil {
		t.Fatal(err)
	}
	if got != sum {
		t.Fatalf("checksum = %q, want %q", got, sum)
	}
}

func TestParseChecksumSingleHashFile(t *testing.T) {
	t.Parallel()

	sum := "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"
	got, err := parseChecksum(sum+"\n", "volter-client-windows-amd64.exe")
	if err != nil {
		t.Fatal(err)
	}
	if got != sum {
		t.Fatalf("checksum = %q, want %q", got, sum)
	}
}

func TestParseChecksumMissing(t *testing.T) {
	t.Parallel()

	_, err := parseChecksum("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  other\n", "volter")
	if err == nil {
		t.Fatal("expected error")
	}
}
