package vpn

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"time"

	"dev.c0redev.volter/internal/tunnel"
)

type ClusterRefreshResult struct {
	OK         bool   `json:"ok"`
	MapOK      bool   `json:"mapOk"`
	SessionsOK bool   `json:"sessionsOk"`
	ClientsOK  bool   `json:"clientsOk"`
	Error      string `json:"error,omitempty"`
	ServerUsed string `json:"serverUsed,omitempty"`
}

func RefreshClusterEndpointsJSON(ctx context.Context, opt Options) string {
	r := ClusterRefreshResult{}
	if len(opt.ServerAddrs) == 0 {
		r.Error = "server addrs empty"
		return marshalRefresh(r)
	}
	ck := clusterPollHeaderKey(opt)
	mapPath, sessPath, clientsPath := clusterPollPaths(opt.Protection)
	ctx, cancel := context.WithTimeout(ctx, 25*time.Second)
	defer cancel()
	for _, raw := range opt.ServerAddrs {
		addr := strings.TrimSpace(raw)
		if addr == "" {
			continue
		}
		r.ServerUsed = addr
		if refreshClusterHTTP(ctx, addr, ck, mapPath, 256*1024, func(body string) {
			lastClusterMap.Store(body)
		}) {
			r.MapOK = true
		}
		if refreshClusterHTTP(ctx, addr, ck, sessPath, 512*1024, func(body string) {
			lastClusterSessions.Store(body)
		}) {
			r.SessionsOK = true
		}
		if refreshClusterHTTP(ctx, addr, ck, clientsPath, 512*1024, func(body string) {
			lastClusterClients.Store(body)
			tunnel.SetGlobalClusterPeerTCPHints(peerHintsFromClusterRaw(body))
		}) {
			r.ClientsOK = true
		}
		if r.MapOK || r.SessionsOK || r.ClientsOK {
			r.OK = true
			return marshalRefresh(r)
		}
	}
	if r.Error == "" {
		r.Error = "cluster HTTP unreachable"
	}
	return marshalRefresh(r)
}

func marshalRefresh(r ClusterRefreshResult) string {
	b, _ := json.Marshal(r)
	return string(b)
}

func refreshClusterHTTP(ctx context.Context, serverAddr, headerKey, path string, limit int64, store func(string)) bool {
	path = strings.TrimSpace(path)
	if path == "" {
		return false
	}
	u := "http://" + strings.TrimSpace(serverAddr) + path
	cl := &http.Client{Timeout: 8 * time.Second}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return false
	}
	if strings.TrimSpace(headerKey) != "" {
		req.Header.Set("X-Volter-Cluster-Key", strings.TrimSpace(headerKey))
	}
	req.Header.Set("X-Volter-Cluster-Pull", "1")
	resp, err := cl.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return false
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, limit))
	if err != nil || len(b) == 0 {
		return false
	}
	store(string(b))
	return true
}
