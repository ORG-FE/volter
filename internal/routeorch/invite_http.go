package routeorch

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type InviteRequest struct {
	ClientID      string `json:"clientId"`
	Nonce         string `json:"nonce"`
	TargetNodeID  string `json:"targetNodeId"`
	CorrelationID string `json:"correlationId"`
	DeadlineMs    int64  `json:"deadlineMs"`
}

type InviteResponse struct {
	Status           string `json:"status"`
	RedirectHostPort string `json:"redirectHostPort,omitempty"`
	Reason           string `json:"reason,omitempty"`
}

func PostClusterInvite(ctx context.Context, baseHostPort string, clusterKey string, httpPath string, req InviteRequest) (*InviteResponse, error) {
	baseHostPort = strings.TrimSpace(baseHostPort)
	if baseHostPort == "" {
		return nil, fmt.Errorf("empty host")
	}
	if !strings.Contains(baseHostPort, ":") {
		baseHostPort = baseHostPort + ":80"
	}
	httpPath = strings.TrimSpace(httpPath)
	if httpPath == "" {
		httpPath = "/volter/cluster-invite"
	}
	u := "http://" + baseHostPort + httpPath
	body, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}
	cctx, cancel := context.WithTimeout(ctx, ClusterHandshakeTimeout()+2*time.Second)
	defer cancel()
	hreq, err := http.NewRequestWithContext(cctx, http.MethodPost, u, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	hreq.Header.Set("Content-Type", "application/json")
	if strings.TrimSpace(clusterKey) != "" {
		hreq.Header.Set("X-Volter-Cluster-Key", strings.TrimSpace(clusterKey))
	}
	resp, err := http.DefaultClient.Do(hreq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		return nil, err
	}
	var out InviteResponse
	if json.Unmarshal(b, &out) != nil {
		return nil, fmt.Errorf("invite: bad json status=%d body=%s", resp.StatusCode, string(b))
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return &out, fmt.Errorf("invite: http %d", resp.StatusCode)
	}
	return &out, nil
}

type PeerHandshakeRequest struct {
	InviteID      string `json:"inviteId"`
	TargetNodeID  string `json:"targetNodeId"`
	CorrelationID string `json:"correlationId"`
	DeadlineMs    int64  `json:"deadlineMs"`
}

type PeerHandshakeResponse struct {
	Status           string `json:"status"`
	RedirectHostPort string `json:"redirectHostPort,omitempty"`
	Reason           string `json:"reason,omitempty"`
}

func PostClusterPeerHandshake(ctx context.Context, baseHostPort string, clusterKey string, httpPath string, req PeerHandshakeRequest) (*PeerHandshakeResponse, error) {
	baseHostPort = strings.TrimSpace(baseHostPort)
	if baseHostPort == "" {
		return nil, fmt.Errorf("empty host")
	}
	if !strings.Contains(baseHostPort, ":") {
		baseHostPort = baseHostPort + ":80"
	}
	httpPath = strings.TrimSpace(httpPath)
	if httpPath == "" {
		httpPath = "/volter/cluster-peer-handshake"
	}
	u := "http://" + baseHostPort + httpPath
	body, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}
	cctx, cancel := context.WithTimeout(ctx, ClusterHandshakeTimeout()+2*time.Second)
	defer cancel()
	hreq, err := http.NewRequestWithContext(cctx, http.MethodPost, u, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	hreq.Header.Set("Content-Type", "application/json")
	if strings.TrimSpace(clusterKey) != "" {
		hreq.Header.Set("X-Volter-Cluster-Key", strings.TrimSpace(clusterKey))
	}
	resp, err := http.DefaultClient.Do(hreq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		return nil, err
	}
	var out PeerHandshakeResponse
	if json.Unmarshal(b, &out) != nil {
		return nil, fmt.Errorf("peer handshake: bad json status=%d", resp.StatusCode)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return &out, fmt.Errorf("peer handshake: http %d", resp.StatusCode)
	}
	return &out, nil
}
