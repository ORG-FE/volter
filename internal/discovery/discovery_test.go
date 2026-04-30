package discovery

import (
	"crypto/ed25519"
	"encoding/base64"
	"testing"
	"time"
)

func TestSignedBootstrapVerify(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(nil)
	nodes := []RelayNode{{ID: "a", Endpoints: []string{"1.1.1.1:443"}, Class: "server", UpdatedAt: time.Now().Unix()}}
	body, err := canonicalBootstrapBody(1, time.Now().Add(time.Hour).Unix(), nodes)
	if err != nil {
		t.Fatal(err)
	}
	b := SignedBootstrap{
		EpochSec:  1,
		ExpiresAt: time.Now().Add(time.Hour).Unix(),
		Nodes:     nodes,
		Signature: base64.StdEncoding.EncodeToString(ed25519.Sign(priv, body)),
	}
	if err := b.Verify(pub, time.Now()); err != nil {
		t.Fatal(err)
	}
}

func TestSignBootstrapRoundTrip(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(nil)
	nodes := []RelayNode{{ID: "x", Endpoints: []string{"9.9.9.9:443"}, Class: "server", UpdatedAt: 123}}
	exp := time.Now().Add(time.Hour).Unix()
	sb, err := SignBootstrap(1, exp, nodes, priv)
	if err != nil {
		t.Fatal(err)
	}
	if err := sb.Verify(pub, time.Now()); err != nil {
		t.Fatal(err)
	}
}

func TestFilterRelayNodesByClass(t *testing.T) {
	nodes := []RelayNode{
		{ID: "a", Endpoints: []string{"1:1"}, Class: "server"},
		{ID: "b", Endpoints: []string{"2:2"}, Class: "peer"},
	}
	got := FilterRelayNodesByClass(nodes, []string{"server"})
	if len(got) != 1 || got[0].ID != "a" {
		t.Fatalf("filter: %+v", got)
	}
	if len(FilterRelayNodesByClass(nodes, nil)) != 2 {
		t.Fatal("nil allowed")
	}
}

func TestFilterRelayNodesByStake(t *testing.T) {
	nodes := []RelayNode{
		{ID: "a", Endpoints: []string{"1:1"}, Stake: 5},
		{ID: "b", Endpoints: []string{"2:2"}, Stake: 1},
	}
	got := FilterRelayNodesByStake(nodes, 3)
	if len(got) != 1 || got[0].ID != "a" {
		t.Fatalf("stake: %+v", got)
	}
	if len(FilterRelayNodesByStake(nodes, 0)) != 2 {
		t.Fatal()
	}
}

func TestRelayHasContact(t *testing.T) {
	if !RelayHasContact(RelayNode{ID: "x", Quic: "h:1"}) {
		t.Fatal("quic")
	}
	if RelayHasContact(RelayNode{ID: "x"}) {
		t.Fatal("empty")
	}
}

func TestMergeGossip(t *testing.T) {
	now := time.Now()
	base := []RelayNode{{ID: "a", Endpoints: []string{"1.1.1.1:443"}, UpdatedAt: now.Add(-time.Minute).Unix()}}
	up := []RelayNode{{ID: "a", Endpoints: []string{"2.2.2.2:443"}, UpdatedAt: now.Unix()}}
	got := MergeGossip(base, up, now, 10*time.Minute)
	if len(got) != 1 || got[0].Endpoints[0] != "2.2.2.2:443" {
		t.Fatalf("unexpected gossip merge: %+v", got)
	}
}
