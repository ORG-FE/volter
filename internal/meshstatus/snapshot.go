package meshstatus

import (
	"encoding/hex"
	"fmt"
	"strings"
	"time"

	"dev.c0redev.volter/internal/dht"
	"dev.c0redev.volter/internal/discovery"
	"dev.c0redev.volter/internal/telemetry"
	"dev.c0redev.volter/internal/vpn"
)

type NodeRow struct {
	ID        string `json:"id"`
	Class     string `json:"class"`
	Endpoints string `json:"endpoints"`
	Quic      string `json:"quic,omitempty"`
	DhtRPC    string `json:"dhtRpc,omitempty"`
	Srflx     string `json:"srflx,omitempty"`
	Turn      string `json:"turn,omitempty"`
	Stake     int    `json:"stake,omitempty"`
}

type PathEvt struct {
	Ts   time.Time `json:"ts"`
	Kind string    `json:"kind"`
	Note string    `json:"note"`
}

type Status struct {
	DhtSelfIDHex      string    `json:"dhtSelfIdHex"`
	ClientSrflx       string    `json:"clientSrflx,omitempty"`
	IceSrflxRttEwmaMs float64   `json:"iceSrflxRttEwmaMs"`
	Nodes             []NodeRow `json:"nodes"`
	PathEvents        []PathEvt `json:"pathEvents"`
	CollectedAt       time.Time `json:"collectedAt"`
}

func Gather() Status { return GatherNearest(48) }

func GatherNearest(kNearest int) Status {
	if kNearest <= 0 {
		kNearest = 48
	}
	tab := dht.DefaultTable()
	self := tab.SelfID()
	raw := tab.Nearest(kNearest)
	rows := make([]NodeRow, 0, len(raw))
	for _, n := range raw {
		rows = append(rows, nodeRowFrom(n))
	}
	pe := telemetry.PathSnapshot()
	outPe := make([]PathEvt, 0, len(pe))
	for _, e := range pe {
		outPe = append(outPe, PathEvt{Ts: e.Ts, Kind: string(e.Kind), Note: e.Note})
	}
	return Status{
		DhtSelfIDHex:      hex.EncodeToString(self[:]),
		ClientSrflx:       vpn.LastClientSrflx(),
		IceSrflxRttEwmaMs: telemetry.IceSrflxRttEwmaMs(),
		Nodes:             rows,
		PathEvents:        outPe,
		CollectedAt:       time.Now(),
	}
}

func nodeRowFrom(n discovery.RelayNode) NodeRow {
	return NodeRow{
		ID:        n.ID,
		Class:     n.Class,
		Endpoints: strings.Join(n.Endpoints, ", "),
		Quic:      n.Quic,
		DhtRPC:    n.DhtRPC,
		Srflx:     n.Srflx,
		Turn:      n.Turn,
		Stake:     n.Stake,
	}
}

func Format(s Status) string {
	var b strings.Builder
	b.WriteString(fmt.Sprintf("DHT self (xor target): %s\n", s.DhtSelfIDHex))
	if s.ClientSrflx != "" {
		b.WriteString(fmt.Sprintf("Клиент srflx (последний): %s\n", s.ClientSrflx))
	} else {
		b.WriteString("Клиент srflx: — (ещё не собран или mesh выкл)\n")
	}
	b.WriteString(fmt.Sprintf("ICE srflx RTT EWMA: %.1f ms\n\n", s.IceSrflxRttEwmaMs))

	b.WriteString(fmt.Sprintf("Узлы в таблице (nearest, %d):\n", len(s.Nodes)))
	if len(s.Nodes) == 0 {
		b.WriteString("  (пусто — подключись с relay.discoveryURL / DHT)\n")
	} else {
		for _, r := range s.Nodes {
			line := fmt.Sprintf("  • %s [%s]", r.ID, r.Class)
			if r.Endpoints != "" {
				line += " ep=" + r.Endpoints
			}
			if r.Quic != "" {
				line += " quic=" + r.Quic
			}
			if r.DhtRPC != "" {
				line += " dhtRpc=" + r.DhtRPC
			}
			if r.Srflx != "" {
				line += " srflx=" + r.Srflx
			}
			if r.Turn != "" {
				line += " turn=" + r.Turn
			}
			b.WriteString(line + "\n")
		}
	}

	b.WriteString("\nПоследние переключения пути / ICE / relay:\n")
	if len(s.PathEvents) == 0 {
		b.WriteString("  (пока нет событий)\n")
	} else {
		show := s.PathEvents
		if len(show) > 24 {
			show = show[len(show)-24:]
		}
		for _, e := range show {
			b.WriteString(fmt.Sprintf("  [%s] %s — %s\n", e.Ts.Format("15:04:05"), e.Kind, e.Note))
		}
	}
	b.WriteString(fmt.Sprintf("\nОбновлено: %s\n", s.CollectedAt.Format(time.RFC3339)))
	return b.String()
}
