package shaper

type State int

const (
	StateIdle State = iota
	StateBurst
	StateSustained
	StateTail
	numStates
)

func (s State) String() string {
	switch s {
	case StateIdle:
		return "idle"
	case StateBurst:
		return "burst"
	case StateSustained:
		return "sustained"
	case StateTail:
		return "tail"
	default:
		return "unknown"
	}
}

type dist struct {
	kind     distKind
	min, max float64
	mean     float64
	std      float64
}

type distKind int

const (
	distUniform distKind = iota
	distExp
	distNormal
)

type stateParams struct {
	frameLen dist
	delayMs  dist
}

type Profile struct {
	Name    string
	trans   [numStates][numStates]float64
	params  [numStates]stateParams
	initial State
}

var builtins = map[string]Profile{

	"web": {
		Name:    "web",
		initial: StateIdle,
		trans: [numStates][numStates]float64{
			StateIdle:      {StateIdle: 0.55, StateBurst: 0.35, StateSustained: 0.08, StateTail: 0.02},
			StateBurst:     {StateIdle: 0.05, StateBurst: 0.45, StateSustained: 0.30, StateTail: 0.20},
			StateSustained: {StateIdle: 0.10, StateBurst: 0.20, StateSustained: 0.50, StateTail: 0.20},
			StateTail:      {StateIdle: 0.55, StateBurst: 0.10, StateSustained: 0.15, StateTail: 0.20},
		},
		params: [numStates]stateParams{
			StateIdle:      {frameLen: dist{distExp, 40, 300, 80, 0}, delayMs: dist{distExp, 5, 800, 120, 0}},
			StateBurst:     {frameLen: dist{distNormal, 800, 1400, 1300, 200}, delayMs: dist{distExp, 0, 20, 2, 0}},
			StateSustained: {frameLen: dist{distNormal, 400, 1400, 1000, 300}, delayMs: dist{distExp, 1, 60, 10, 0}},
			StateTail:      {frameLen: dist{distExp, 100, 900, 300, 0}, delayMs: dist{distExp, 2, 150, 25, 0}},
		},
	},

	"video": {
		Name:    "video",
		initial: StateSustained,
		trans: [numStates][numStates]float64{
			StateIdle:      {StateIdle: 0.30, StateBurst: 0.20, StateSustained: 0.45, StateTail: 0.05},
			StateBurst:     {StateIdle: 0.02, StateBurst: 0.40, StateSustained: 0.53, StateTail: 0.05},
			StateSustained: {StateIdle: 0.05, StateBurst: 0.25, StateSustained: 0.65, StateTail: 0.05},
			StateTail:      {StateIdle: 0.40, StateBurst: 0.15, StateSustained: 0.40, StateTail: 0.05},
		},
		params: [numStates]stateParams{
			StateIdle:      {frameLen: dist{distExp, 60, 400, 120, 0}, delayMs: dist{distExp, 5, 500, 80, 0}},
			StateBurst:     {frameLen: dist{distNormal, 1000, 1400, 1350, 120}, delayMs: dist{distUniform, 0, 8, 0, 0}},
			StateSustained: {frameLen: dist{distNormal, 900, 1400, 1250, 200}, delayMs: dist{distNormal, 8, 60, 33, 10}},
			StateTail:      {frameLen: dist{distExp, 200, 1000, 400, 0}, delayMs: dist{distExp, 5, 120, 30, 0}},
		},
	},

	"game": {
		Name:    "game",
		initial: StateSustained,
		trans: [numStates][numStates]float64{
			StateIdle:      {StateIdle: 0.40, StateBurst: 0.15, StateSustained: 0.43, StateTail: 0.02},
			StateBurst:     {StateIdle: 0.05, StateBurst: 0.35, StateSustained: 0.55, StateTail: 0.05},
			StateSustained: {StateIdle: 0.08, StateBurst: 0.17, StateSustained: 0.70, StateTail: 0.05},
			StateTail:      {StateIdle: 0.45, StateBurst: 0.10, StateSustained: 0.40, StateTail: 0.05},
		},
		params: [numStates]stateParams{
			StateIdle:      {frameLen: dist{distExp, 30, 200, 60, 0}, delayMs: dist{distExp, 5, 300, 50, 0}},
			StateBurst:     {frameLen: dist{distNormal, 150, 600, 350, 100}, delayMs: dist{distExp, 0, 15, 3, 0}},
			StateSustained: {frameLen: dist{distNormal, 60, 400, 180, 80}, delayMs: dist{distNormal, 8, 50, 22, 8}},
			StateTail:      {frameLen: dist{distExp, 40, 250, 90, 0}, delayMs: dist{distExp, 3, 80, 18, 0}},
		},
	},

	"bulk": {
		Name:    "bulk",
		initial: StateBurst,
		trans: [numStates][numStates]float64{
			StateIdle:      {StateIdle: 0.20, StateBurst: 0.60, StateSustained: 0.18, StateTail: 0.02},
			StateBurst:     {StateIdle: 0.01, StateBurst: 0.80, StateSustained: 0.17, StateTail: 0.02},
			StateSustained: {StateIdle: 0.03, StateBurst: 0.55, StateSustained: 0.40, StateTail: 0.02},
			StateTail:      {StateIdle: 0.30, StateBurst: 0.45, StateSustained: 0.20, StateTail: 0.05},
		},
		params: [numStates]stateParams{
			StateIdle:      {frameLen: dist{distExp, 100, 800, 300, 0}, delayMs: dist{distExp, 2, 200, 30, 0}},
			StateBurst:     {frameLen: dist{distNormal, 1200, 1400, 1400, 80}, delayMs: dist{distUniform, 0, 3, 0, 0}},
			StateSustained: {frameLen: dist{distNormal, 800, 1400, 1200, 200}, delayMs: dist{distExp, 0, 25, 4, 0}},
			StateTail:      {frameLen: dist{distExp, 200, 1000, 500, 0}, delayMs: dist{distExp, 1, 60, 12, 0}},
		},
	},
}

func ProfileByName(name string) (Profile, bool) {
	p, ok := builtins[name]
	return p, ok
}

func ProfileNames() []string {
	return []string{"web", "video", "game", "bulk"}
}
