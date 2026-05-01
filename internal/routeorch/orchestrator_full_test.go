package routeorch

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestOrchestratorRunFull_AssistedProbeOK(t *testing.T) {
	var inviteHits atomic.Int32
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(405)
			return
		}
		switch r.URL.Path {
		case "/volter/cluster-invite":
			inviteHits.Add(1)
			b, _ := io.ReadAll(r.Body)
			if !strings.Contains(string(b), `"targetNodeId":"ru-1"`) {
				t.Fatalf("body %s", string(b))
			}
			_, _ = io.WriteString(w, `{"status":"accepted","redirectHostPort":"198.51.100.4:443"}`+"\n")
		case "/volter/cluster-peer-handshake":
			_, _ = io.WriteString(w, `{"status":"accepted"}`+"\n")
		default:
			w.WriteHeader(404)
		}
	}))
	defer ts.Close()
	entry := strings.TrimPrefix(strings.TrimPrefix(ts.URL, "http://"), "https://")

	o := NewOrchestrator()
	o.AssistWait = 15 * time.Millisecond
	o.ProbeTimeout = 100 * time.Millisecond

	var n atomic.Int32
	probe := func(ctx context.Context, hostPort string) error {
		c := n.Add(1)
		if c == 1 {
			return errors.New("direct_fail")
		}
		if hostPort != "198.51.100.4:443" {
			t.Fatalf("probe host %q", hostPort)
		}
		return nil
	}
	st, out, detail := o.RunFull(context.Background(), Target{HostPort: "198.51.100.4:443"}, entry, probe, &AssistConfig{
		ClusterKey:    "",
		InvitePath:    "/volter/cluster-invite",
		HandshakePath: "/volter/cluster-peer-handshake",
		TargetNodeID:  "ru-1",
		ClientID:      "c1",
	})
	if inviteHits.Load() != 1 {
		t.Fatalf("invite hits %d", inviteHits.Load())
	}
	if st != StageMigrate || out != OutcomeMigrated || detail != "198.51.100.4:443" {
		t.Fatalf("got stage=%s out=%s detail=%q", st, out, detail)
	}
}
