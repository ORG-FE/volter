package tunnel

import (
	"sync/atomic"
)

var globalClusterPeerTCP atomic.Value

func SetGlobalClusterPeerTCPHints(h []string) {
	if len(h) == 0 {
		globalClusterPeerTCP.Store([]string(nil))
		return
	}
	cp := append([]string(nil), h...)
	globalClusterPeerTCP.Store(cp)
}

func snapshotClusterPeerTCPHints() []string {
	v := globalClusterPeerTCP.Load()
	if v == nil {
		return nil
	}
	h, _ := v.([]string)
	return h
}

func GlobalClusterPeerTCPHintCount() int {
	return len(snapshotClusterPeerTCPHints())
}
