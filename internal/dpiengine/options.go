package dpiengine

type Options struct {
	SplitAfter  int
	SplitAfter2 int
	TTLMillis   int
	TTL2Millis  int
	Disorder    bool
	JitterMaxMs int
	LeadInMs    int
}
