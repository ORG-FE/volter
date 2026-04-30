package stake

import (
	"encoding/json"
	"os"
	"strings"

	"dev.c0redev.volter/internal/discovery"
)

func MergeReputation(nodes []discovery.RelayNode, path string) []discovery.RelayNode {
	path = strings.TrimSpace(path)
	if path == "" || len(nodes) == 0 {
		return nodes
	}
	b, err := os.ReadFile(path)
	if err != nil || len(b) == 0 {
		return nodes
	}
	var doc struct {
		Scores map[string]int `json:"scores"`
	}
	if err := json.Unmarshal(b, &doc); err != nil || len(doc.Scores) == 0 {
		return nodes
	}
	out := make([]discovery.RelayNode, len(nodes))
	copy(out, nodes)
	for i := range out {
		id := strings.TrimSpace(out[i].ID)
		if v, ok := doc.Scores[id]; ok && v >= 0 {
			out[i].Stake += v
		}
	}
	return out
}
