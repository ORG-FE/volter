package meshstatus

import (
	"encoding/hex"
	"encoding/json"
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
	StoreForwardSent  uint64    `json:"storeForwardSent"`
	StoreForwardRecv  uint64    `json:"storeForwardRecv"`
	ClusterNodeID     string    `json:"clusterNodeId,omitempty"`
	ClusterNodes      []string  `json:"clusterNodes,omitempty"`
	ClusterSessionsNodeID string `json:"clusterSessionsNodeId,omitempty"`
	ClusterSessionsCount    int   `json:"clusterSessionsCount"`
	ClusterSessionsAtMs     int64 `json:"clusterSessionsAtMs,omitempty"`
	ClusterClientsNodeID string   `json:"clusterClientsNodeId,omitempty"`
	ClusterClientsCount  int      `json:"clusterClientsCount"`
	ClusterClients       []string `json:"clusterClients,omitempty"`
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
	clusterNodeID, clusterNodes := parseClusterMap(vpn.LastClusterMap())
	csNode, csCnt, csAt, csOk := parseClusterSessions(vpn.LastClusterSessions())
	ccNode, ccList, ccOk := parseClusterClients(vpn.LastClusterClients())
	sf := vpn.StoreForwardStats()
	out := Status{
		DhtSelfIDHex:      hex.EncodeToString(self[:]),
		ClientSrflx:       vpn.LastClientSrflx(),
		IceSrflxRttEwmaMs: telemetry.IceSrflxRttEwmaMs(),
		StoreForwardSent:  sf.Sent,
		StoreForwardRecv:  sf.Received,
		ClusterNodeID:     clusterNodeID,
		ClusterNodes:      clusterNodes,
		ClusterSessionsCount: -1,
		ClusterClientsCount: -1,
		Nodes:             rows,
		PathEvents:        outPe,
		CollectedAt:       time.Now(),
	}
	if csOk {
		out.ClusterSessionsNodeID = csNode
		out.ClusterSessionsCount = csCnt
		out.ClusterSessionsAtMs = csAt
	}
	if ccOk {
		out.ClusterClientsNodeID = ccNode
		out.ClusterClients = ccList
		out.ClusterClientsCount = len(ccList)
	}
	return out
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
	b.WriteString(fmt.Sprintf("Store-forward sent/recv: %d/%d\n", s.StoreForwardSent, s.StoreForwardRecv))
	if s.ClusterNodeID != "" {
		b.WriteString(fmt.Sprintf("Кластер узел: %s\n", s.ClusterNodeID))
	}
	if len(s.ClusterNodes) > 0 {
		b.WriteString("Кластер серверы:\n")
		for _, n := range s.ClusterNodes {
			b.WriteString("  • " + n + "\n")
		}
	}
	if s.ClusterSessionsCount >= 0 {
		line := fmt.Sprintf("Снимок resume кластера: узел %s, сессий %d", strings.TrimSpace(s.ClusterSessionsNodeID), s.ClusterSessionsCount)
		if s.ClusterSessionsAtMs > 0 {
			line += fmt.Sprintf(" (сервер ts %s)", time.UnixMilli(s.ClusterSessionsAtMs).Format("15:04:05"))
		}
		b.WriteString(line + "\n")
	}
	if s.ClusterClientsCount >= 0 {
		line := fmt.Sprintf("Снимок клиентов кластера: узел %s, клиентов %d", strings.TrimSpace(s.ClusterClientsNodeID), s.ClusterClientsCount)
		b.WriteString(line + "\n")
	}
	b.WriteString("\n")

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

func parseClusterMap(raw string) (string, []string) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", nil
	}
	var doc struct {
		NodeID string `json:"nodeId"`
		Nodes  []struct {
			ID       string `json:"id"`
			Endpoint string `json:"endpoint"`
			Alive    bool   `json:"alive"`
		} `json:"nodes"`
	}
	if json.Unmarshal([]byte(raw), &doc) != nil {
		return "", nil
	}
	out := make([]string, 0, len(doc.Nodes))
	for _, n := range doc.Nodes {
		id := strings.TrimSpace(n.ID)
		if id == "" {
			continue
		}
		ep := strings.TrimSpace(n.Endpoint)
		if ep != "" {
			id += " (" + ep + ")"
		}
		if !n.Alive {
			id += " [down]"
		}
		out = append(out, id)
	}
	return strings.TrimSpace(doc.NodeID), out
}

func parseClusterSessions(raw string) (nodeID string, count int, atMs int64, ok bool) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", 0, 0, false
	}
	var doc struct {
		NodeID      string `json:"nodeId"`
		GeneratedAt int64  `json:"generatedAt"`
		Sessions    []struct {
			SessionID string `json:"sessionId"`
		} `json:"sessions"`
	}
	if json.Unmarshal([]byte(raw), &doc) != nil {
		return "", 0, 0, false
	}
	return strings.TrimSpace(doc.NodeID), len(doc.Sessions), doc.GeneratedAt, true
}

func parseClusterClients(raw string) (nodeID string, clients []string, ok bool) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", nil, false
	}
	var doc struct {
		NodeID  string `json:"nodeId"`
		Clients []struct {
			ID     string `json:"id"`
			PeerID string `json:"peerId"`
			Remote string `json:"remote"`
			Owner  string `json:"owner"`
		} `json:"clients"`
	}
	if json.Unmarshal([]byte(raw), &doc) != nil {
		return "", nil, false
	}
	out := make([]string, 0, len(doc.Clients))
	for _, c := range doc.Clients {
		id := strings.TrimSpace(c.ID)
		if id == "" {
			continue
		}
		peer := strings.TrimSpace(c.PeerID)
		remote := strings.TrimSpace(c.Remote)
		owner := strings.TrimSpace(c.Owner)
		line := id
		if peer != "" && peer != id {
			line += " peerId=" + peer
		}
		if remote != "" {
			line += " remote=" + remote
		}
		if owner != "" {
			line += " owner=" + owner
		}
		out = append(out, line)
	}
	return strings.TrimSpace(doc.NodeID), out, true
}
