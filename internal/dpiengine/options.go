package dpiengine

type Options struct {
	SplitAfter    int
	SplitAfter2   int
	TTLMillis     int
	TTL2Millis    int
	Disorder      bool
	JitterMaxMs   int
	LeadInMs      int
	FakeSNI       bool
	FakeSNIHost   string
	SplitPosition string
	AutoTTL       bool
	TCPSegment    int
	OOBData       bool
	MultiSplit    int
}
