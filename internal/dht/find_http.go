package dht

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"dev.c0redev.volter/internal/discovery"
)

var findHTTP = &http.Client{Timeout: 45 * time.Second}

func FetchFindNearest(ctx context.Context, baseURL string, targetID [32]byte, limit int) ([]discovery.RelayNode, error) {
	baseURL = strings.TrimSpace(baseURL)
	if baseURL == "" {
		return nil, fmt.Errorf("dht find: empty url")
	}
	if limit <= 0 {
		limit = 16
	}
	if limit > 256 {
		limit = 256
	}
	u := strings.TrimRight(baseURL, "?&")
	sep := "?"
	if strings.Contains(u, "?") {
		sep = "&"
	}
	u = u + sep + "target=" + hex.EncodeToString(targetID[:]) + "&limit=" + strconv.Itoa(limit)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	resp, err := findHTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("dht find: http %d", resp.StatusCode)
	}
	var env struct {
		Nodes []discovery.RelayNode `json:"nodes"`
	}
	if err := json.Unmarshal(body, &env); err != nil {
		return nil, err
	}
	return env.Nodes, nil
}
