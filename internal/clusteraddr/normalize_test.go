package clusteraddr

import "testing"

func TestCanonicalHostPort(t *testing.T) {
	cases := []struct {
		in, want string
	}{
		{"", ""},
		{"10.0.0.1:25565", "10.0.0.1:25565"},
		{"http://89.1.2.3:25565/volter/foo", "89.1.2.3:25565"},
		{"https://[::1]:443/path", "[::1]:443"},
		{"ru-1 (http://192.0.2.1:25565/volter/cluster-map.json)", "192.0.2.1:25565"},
		{"de (http://10.0.0.2:25565)", "10.0.0.2:25565"},
	}
	for _, tc := range cases {
		if got := CanonicalHostPort(tc.in); got != tc.want {
			t.Errorf("CanonicalHostPort(%q) = %q want %q", tc.in, got, tc.want)
		}
	}
}

func TestMatchPreferred(t *testing.T) {
	addrs := []string{"de2:25565", "10.0.0.1:25565", "192.0.2.1:25565"}
	if i := MatchPreferred(addrs, "http://192.0.2.1:25565/x"); i != 2 {
		t.Fatalf("MatchPreferred got %d want 2", i)
	}
}
