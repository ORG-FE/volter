package dpi

type ExitViaPeerPhase2 struct {
	Enabled bool `json:"enabled,omitempty"`
}

func (p ExitViaPeerPhase2) Allowed() bool { return p.Enabled }
