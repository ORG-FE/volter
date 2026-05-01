package volunteer

import (
	"encoding/json"
	"sync"
	"time"
)

type Entry struct {
	ClientUUID   string
	Volunteer    bool
	DpiPresetRef string
	LastSeen     time.Time
}

type Registry struct {
	mu sync.RWMutex
	m  map[string]Entry
}

func NewRegistry() *Registry {
	return &Registry{m: make(map[string]Entry)}
}

func (r *Registry) Upsert(e Entry) {
	if r == nil || e.ClientUUID == "" {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.m[e.ClientUUID] = e
}

func (r *Registry) Snapshot() []Entry {
	if r == nil {
		return nil
	}
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]Entry, 0, len(r.m))
	for _, v := range r.m {
		out = append(out, v)
	}
	return out
}

type clusterVolunteersDoc struct {
	NodeID      string `json:"nodeId"`
	GeneratedAt int64  `json:"generatedAt"`
	Volunteers  []struct {
		ClientUUID   string `json:"clientUuid"`
		Volunteer    bool   `json:"volunteer"`
		DpiPresetRef string `json:"dpiPresetRef,omitempty"`
		LastSeenMs   int64  `json:"lastSeenMs"`
	} `json:"volunteers"`
}

func (r *Registry) ExportClusterJSON(nodeID string, now time.Time) ([]byte, error) {
	var doc clusterVolunteersDoc
	doc.NodeID = nodeID
	doc.GeneratedAt = now.UnixMilli()
	if r != nil {
		for _, e := range r.Snapshot() {
			doc.Volunteers = append(doc.Volunteers, struct {
				ClientUUID   string `json:"clientUuid"`
				Volunteer    bool   `json:"volunteer"`
				DpiPresetRef string `json:"dpiPresetRef,omitempty"`
				LastSeenMs   int64  `json:"lastSeenMs"`
			}{
				ClientUUID:   e.ClientUUID,
				Volunteer:    e.Volunteer,
				DpiPresetRef: e.DpiPresetRef,
				LastSeenMs:   e.LastSeen.UnixMilli(),
			})
		}
	}
	return json.MarshalIndent(doc, "", "  ")
}
