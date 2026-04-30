package dht

import (
	"context"
	"testing"
	"time"

	"dev.c0redev.volter/internal/discovery"
)

func TestIterativeFindNode_LocalRPC(t *testing.T) {
	srvTab := NewTable("srv-test")
	srvTab.Insert(discovery.RelayNode{
		ID:        "relay-a",
		Class:     "peer",
		Endpoints: []string{"192.0.2.10:443"},
		UpdatedAt: time.Now().Unix(),
	})
	pc, err := ListenRPCUDP("127.0.0.1:0", "", srvTab, DefaultKVStore())
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = pc.Close() }()
	addr := pc.LocalAddr().String()

	cliTab := NewTable("cli-test")
	target := cliTab.SelfID()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	IterativeFindNode(ctx, cliTab, "", target, []string{addr}, 16, 3, 4, nil)
	if cliTab.Len() < 1 {
		t.Fatalf("expected merged nodes, len=%d", cliTab.Len())
	}
}

func TestIterativeFindNode_NoOp(t *testing.T) {
	tab := NewTable("x")
	IterativeFindNode(context.Background(), tab, "", tab.SelfID(), nil, 8, 2, 0, nil)
}
