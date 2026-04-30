package discovery

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

const MaxBootstrapBodyBytes = 2 << 20

func FetchBootstrapBody(ctx context.Context, rawURL string) ([]byte, error) {
	rawURL = strings.TrimSpace(rawURL)
	if rawURL == "" {
		return nil, fmt.Errorf("empty url")
	}
	c := &http.Client{Timeout: 45 * time.Second}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "volter-discovery/1")
	resp, err := c.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("http status %d", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, MaxBootstrapBodyBytes+1))
	if err != nil {
		return nil, err
	}
	if len(b) > MaxBootstrapBodyBytes {
		return nil, fmt.Errorf("bootstrap body too large")
	}
	return b, nil
}
