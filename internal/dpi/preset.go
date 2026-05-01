package dpi

type PresetRef struct {
	ID      string `json:"id,omitempty"`
	Version int    `json:"version,omitempty"`
	Raw     string `json:"raw,omitempty"`
}

const MaxGossipPresetRunes = 8192
