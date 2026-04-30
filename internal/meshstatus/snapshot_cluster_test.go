package meshstatus

import (
	"strings"
	"testing"
)

func TestParseClusterMap(t *testing.T) {
	raw := `{"v":1,"nodeId":"ru-1","nodes":[{"id":"ru-1","endpoint":"http://ru:25565/volter/cluster-map.json","alive":true},{"id":"de-1","endpoint":"http://de:25565/volter/cluster-map.json","alive":false}]}`
	nodeID, nodes, _, ok := parseClusterMap(raw)
	if !ok {
		t.Fatal("expected ok")
	}
	if nodeID != "ru-1" {
		t.Fatalf("node id: got %q", nodeID)
	}
	if len(nodes) != 2 {
		t.Fatalf("nodes len: got %d", len(nodes))
	}
	if !strings.Contains(nodes[1], "[down]") {
		t.Fatalf("down marker not rendered: %q", nodes[1])
	}
}

func TestParseClusterSessions(t *testing.T) {
	raw := `{"v":1,"nodeId":"ru-1","generatedAt":1700000000000,"sessions":[{"sessionId":"s-a","resumeToken":"t","owner":"x","ts":1},{"sessionId":"s-b","resumeToken":"u","owner":"y","ts":2}]}`
	node, cnt, at, ok := parseClusterSessions(raw)
	if !ok {
		t.Fatal("expected ok")
	}
	if node != "ru-1" {
		t.Fatalf("node: %q", node)
	}
	if cnt != 2 {
		t.Fatalf("count: %d", cnt)
	}
	if at != 1700000000000 {
		t.Fatalf("at: %d", at)
	}
}
