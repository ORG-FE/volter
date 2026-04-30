package ice

type CandidateKind byte

const (
	CandidateUnknown CandidateKind = 0
	CandidateHost    CandidateKind = 1
	CandidateSrflx   CandidateKind = 2
	CandidateRelay   CandidateKind = 3
)

func (k CandidateKind) PathWeight() float64 {
	switch k {
	case CandidateHost:
		return 1.0
	case CandidateSrflx:
		return 0.92
	case CandidateRelay:
		return 0.72
	default:
		return 1.0
	}
}
