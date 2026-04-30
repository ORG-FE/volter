package meshstatus

import (
	"strings"
	"testing"

	"dev.c0redev.volter/internal/dht"
	"dev.c0redev.volter/internal/discovery"
)

func TestFormat_snapshotSmoke(t *testing.T) {
	dht.DefaultTable().Insert(discovery.RelayNode{
		ID:        "test-peer",
		Class:     "peer",
		Endpoints: []string{"192.0.2.1:12345"},
	})
	s := Gather()
	txt := Format(s)
	if !strings.Contains(txt, "DHT self") || !strings.Contains(txt, "test-peer") {
		t.Fatal(txt)
	}
}
