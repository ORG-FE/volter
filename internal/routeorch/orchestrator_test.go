package routeorch

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestOrchestratorRun_DirectOk(t *testing.T) {
	o := NewOrchestrator()
	o.ProbeTimeout = 50 * time.Millisecond
	st, out, detail := o.Run(context.Background(), Target{HostPort: "198.51.100.1:443"}, "10.0.0.1:443",
		func(ctx context.Context, hostPort string) error {
			if hostPort != "198.51.100.1:443" {
				t.Fatalf("probe host %q", hostPort)
			}
			return nil
		})
	if st != StageMigrate || out != OutcomeMigrated || detail != "198.51.100.1:443" {
		t.Fatalf("got stage=%s outcome=%s detail=%q", st, out, detail)
	}
}

func TestOrchestratorRun_DirectFailNoEntry(t *testing.T) {
	o := NewOrchestrator()
	o.ProbeTimeout = 50 * time.Millisecond
	st, out, _ := o.Run(context.Background(), Target{HostPort: "198.51.100.2:443"}, "",
		func(ctx context.Context, hostPort string) error {
			return errors.New("nope")
		})
	if st != StageFallback || out != OutcomeFallback {
		t.Fatalf("got stage=%s outcome=%s", st, out)
	}
}

func TestOrchestratorRun_DirectFailWithEntry(t *testing.T) {
	o := NewOrchestrator()
	o.ProbeTimeout = 50 * time.Millisecond
	st, out, detail := o.Run(context.Background(), Target{HostPort: "198.51.100.3:443"}, "10.0.0.2:443",
		func(ctx context.Context, hostPort string) error {
			return errors.New("nope")
		})
	if st != StageSignal || out != OutcomeFallback || detail != "direct_failed_signal_pending" {
		t.Fatalf("got stage=%s outcome=%s detail=%q", st, out, detail)
	}
}
