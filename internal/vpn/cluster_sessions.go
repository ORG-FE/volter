package vpn

import (
	"context"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"time"
)

var lastClusterSessions atomic.Value

func LastClusterSessions() string {
	v := lastClusterSessions.Load()
	if v == nil {
		return ""
	}
	return v.(string)
}

func runClusterSessionsPoll(ctx context.Context, serverAddr, headerKey, httpPath string) {
	serverAddr = strings.TrimSpace(serverAddr)
	if serverAddr == "" {
		return
	}
	path := strings.TrimSpace(httpPath)
	if path == "" {
		path = defaultClusterSessionsPath
	}
	u := "http://" + serverAddr + path
	cl := &http.Client{Timeout: 6 * time.Second}
	tk := time.NewTicker(5 * time.Second)
	defer tk.Stop()
	fetch := func() {
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
		if strings.TrimSpace(headerKey) != "" {
			req.Header.Set("X-Volter-Cluster-Key", strings.TrimSpace(headerKey))
		}
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
		lastClusterSessions.Store(string(b))
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
