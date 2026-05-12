package tunnel

import (
	"bufio"
	"bytes"
	"encoding/json"
	"strings"
	"testing"

	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/protocol"
)

func TestClusterPreferredServerInHandshake(t *testing.T) {
	tests := []struct {
		name                   string
		clusterPreferredServer string
		relayHop               int
		relayMaxHop            int
		peerID                 string
		wantInJSON             bool
	}{
		{
			name:                   "cluster server without relay",
			clusterPreferredServer: "ru-1.example:443",
			relayHop:               0,
			relayMaxHop:            0,
			peerID:                 "",
			wantInJSON:             true,
		},
		{
			name:                   "cluster server with relay",
			clusterPreferredServer: "ru-1.example:443",
			relayHop:               1,
			relayMaxHop:            2,
			peerID:                 "",
			wantInJSON:             true,
		},
		{
			name:                   "no cluster server",
			clusterPreferredServer: "",
			relayHop:               1,
			relayMaxHop:            2,
			peerID:                 "",
			wantInJSON:             false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			prot := &config.ProtectionOptions{
				ClusterPreferredServer: tt.clusterPreferredServer,
				RelayHop:               tt.relayHop,
				RelayMaxHop:            tt.relayMaxHop,
				PeerID:                 tt.peerID,
			}

			var buf bytes.Buffer
			w := bufio.NewWriter(&buf)

			// вызываем tcpRelayPreamble
			err := tcpRelayPreamble(w, "test-token", prot, 0)
			if err != nil {
				t.Fatalf("tcpRelayPreamble failed: %v", err)
			}
			_ = w.Flush()

			// парсим handshake
			r := bufio.NewReader(&buf)
			_, optsJSON, err := protocol.ReadHandshakeAfterSkipWithOpts(r)
			if err != nil {
				t.Fatalf("ReadHandshakeAfterSkipWithOpts failed: %v", err)
			}

			if len(optsJSON) == 0 {
				if tt.wantInJSON {
					t.Fatalf("expected optsJSON, got empty")
				}
				return
			}

			// проверяем JSON
			var opts map[string]interface{}
			if err := json.Unmarshal(optsJSON, &opts); err != nil {
				t.Fatalf("json.Unmarshal failed: %v", err)
			}

			clusterVal, hasCluster := opts["clusterPreferredServer"]
			if tt.wantInJSON {
				if !hasCluster {
					t.Errorf("clusterPreferredServer not found in JSON: %s", string(optsJSON))
				} else {
					clusterStr, ok := clusterVal.(string)
					if !ok {
						t.Errorf("clusterPreferredServer is not string: %v", clusterVal)
					} else if strings.TrimSpace(clusterStr) != strings.TrimSpace(tt.clusterPreferredServer) {
						t.Errorf("clusterPreferredServer mismatch: got %q, want %q", clusterStr, tt.clusterPreferredServer)
					}
				}
			} else {
				if hasCluster {
					t.Errorf("clusterPreferredServer should not be in JSON, but found: %v", clusterVal)
				}
			}
		})
	}
}

func TestRelayOptsForHandshakePreservesClusterServer(t *testing.T) {
	prot := &config.ProtectionOptions{
		ClusterPreferredServer: "ru-1.example:443",
		RelayHop:               0,
		RelayMaxHop:            0,
		PeerID:                 "",
	}

	eff := relayOptsForHandshake(prot, "test-token")
	if eff == nil {
		t.Fatal("relayOptsForHandshake returned nil")
	}

	if eff.ClusterPreferredServer != prot.ClusterPreferredServer {
		t.Errorf("ClusterPreferredServer lost: got %q, want %q", eff.ClusterPreferredServer, prot.ClusterPreferredServer)
	}
}

func TestProtForServerRelayRoutePreservesClusterServer(t *testing.T) {
	base := &config.ProtectionOptions{
		ClusterPreferredServer: "ru-1.example:443",
		RouteMode:              "server_relay",
	}

	result := protForServerRelayRoute(base, nil)
	if result == nil {
		t.Fatal("protForServerRelayRoute returned nil")
	}

	if result.ClusterPreferredServer != base.ClusterPreferredServer {
		t.Errorf("ClusterPreferredServer lost: got %q, want %q", result.ClusterPreferredServer, base.ClusterPreferredServer)
	}

	// должны быть установлены relay параметры
	if result.RelayHop <= 0 {
		t.Errorf("RelayHop not set: got %d", result.RelayHop)
	}
	if result.RelayMaxHop <= 0 {
		t.Errorf("RelayMaxHop not set: got %d", result.RelayMaxHop)
	}

	// PeerID, RelayNonce, RelaySig должны быть пустыми для cluster server
	if result.PeerID != "" {
		t.Errorf("PeerID should be empty for cluster server, got %q", result.PeerID)
	}
	if result.RelayNonce != "" {
		t.Errorf("RelayNonce should be empty for cluster server, got %q", result.RelayNonce)
	}
	if result.RelaySig != "" {
		t.Errorf("RelaySig should be empty for cluster server, got %q", result.RelaySig)
	}
}
