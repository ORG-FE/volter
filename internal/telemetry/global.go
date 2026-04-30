package telemetry

func RecordPath(kind PathSwitchKind, note string) {
	pathRing.Add(kind, note)
}

func PathSnapshot() []PathEvent {
	return pathRing.Snapshot()
}

var pathRing PathRing
