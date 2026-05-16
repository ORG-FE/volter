package vpn

import "testing"

func TestClusterPollAcceptSourceEmptyActive(t *testing.T) {
	if !clusterPollAcceptSource("1.2.3.4:443") {
		t.Fatal("empty active must accept")
	}
}

func TestReorderServerAddrsActiveFirst(t *testing.T) {
	in := []string{"b:2", "a:1", "c:3"}
	out := reorderServerAddrsActiveFirst(in, "c:3")
	if len(out) != 3 || out[0] != "c:3" {
		t.Fatalf("got %v", out)
	}
}
