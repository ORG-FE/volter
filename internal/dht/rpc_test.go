package dht

import (
	"context"
	"testing"

	"dev.c0redev.volter/internal/discovery"
)

func TestUDPFindNodeRoundTrip(t *testing.T) {
	tab := NewTable("rpc-test-seed")
	tab.Insert(discovery.RelayNode{ID: "n1", Endpoints: []string{"10.0.0.1:443"}, Class: "peer", UpdatedAt: 100})
	uc, err := ListenRPCUDP("127.0.0.1:0", "", tab, NewKVStore())
	if err != nil {
		t.Fatal(err)
	}
	defer uc.Close()
	addr := uc.LocalAddr().String()
	target := tab.SelfID()
	nodes, err := UDPFindNode(context.Background(), addr, "", target, 8)
	if err != nil {
		t.Fatal(err)
	}
	if len(nodes) != 1 || nodes[0].ID != "n1" {
		t.Fatalf("nodes=%+v", nodes)
	}
}

func TestUDPStoreGetRoundTrip(t *testing.T) {
	tab := NewTable("store-test")
	kv := NewKVStore()
	uc, err := ListenRPCUDP("127.0.0.1:0", "", tab, kv)
	if err != nil {
		t.Fatal(err)
	}
	defer uc.Close()
	addr := uc.LocalAddr().String()
	var key [32]byte
	key[0] = 7
	ctx := context.Background()
	ok, err := UDPStore(ctx, addr, "", key, 120, []byte("hello"))
	if err != nil || !ok {
		t.Fatalf("store err=%v ok=%v", err, ok)
	}
	val, found, err := UDPGet(ctx, addr, "", key)
	if err != nil || !found || string(val) != "hello" {
		t.Fatalf("get err=%v found=%v val=%q", err, found, val)
	}
}
