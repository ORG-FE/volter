package vpn

import (
	"context"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"time"

	"dev.c0redev.volter/internal/tunnel"
)

var lastClusterClients atomic.Value

func LastClusterClients() string {
	s, stale := clusterLoad(&lastClusterClients, 45*time.Second)
	if stale {
		tunnel.SetGlobalClusterPeerTCPHints(nil)
	}
	return s
}

func runClusterClientsPoll(ctx context.Context, entryAddr, headerKey, httpPath string) {
	entryAddr = strings.TrimSpace(entryAddr)
	path := strings.TrimSpace(httpPath)
	if path == "" {
		path = defaultClusterClientsPath
	}
	cl := &http.Client{Timeout: 6 * time.Second}
	tk := time.NewTicker(5 * time.Second)
	defer tk.Stop()
	fetch := func() {
		serverAddr := clusterHTTPPollTarget([]string{entryAddr}, nil)
		if serverAddr == "" {
			return
		}
		u := "http://" + serverAddr + path
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
		if strings.TrimSpace(headerKey) != "" {
			req.Header.Set("X-Volter-Cluster-Key", strings.TrimSpace(headerKey))
		}
		req.Header.Set("X-Volter-Cluster-Pull", "1")
		resp, err := cl.Do(req)
		if err != nil {
			return
		}
		defer resp.Body.Close()
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			return
		}
		b, err := io.ReadAll(io.LimitReader(resp.Body, 512*1024))
		if err != nil || len(b) == 0 {
			return
		}
		if !clusterPollAcceptSource(serverAddr) {
			return
		}
		s := string(b)
		clusterStore(&lastClusterClients, s)
		tunnel.SetGlobalClusterPeerTCPHints(peerHintsFromClusterRaw(s))
	}
	fetch()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tk.C:
			fetch()
		}
	}
}
