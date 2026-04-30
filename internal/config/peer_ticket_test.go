package config

import (
	"testing"
	"time"
)

func TestPeerTicketRoundTrip(t *testing.T) {
	in := CreatePeerTicket("peer-a", "pub-1", []string{"1.2.3.4:25565", "1.2.3.4:25565"}, time.Hour)
	uri := BuildPeerTicketURI(in)
	if uri == "" {
		t.Fatal("empty peer ticket uri")
	}
	out, ok := ParsePeerTicketURI(uri)
	if !ok {
		t.Fatal("parse peer ticket failed")
	}
	if out.PeerID != in.PeerID {
		t.Fatalf("peer id mismatch: %q vs %q", out.PeerID, in.PeerID)
	}
	if out.PubKey != in.PubKey {
		t.Fatalf("pub key mismatch: %q vs %q", out.PubKey, in.PubKey)
	}
	if len(out.Addrs) != 1 {
		t.Fatalf("dedupe addrs failed: %v", out.Addrs)
	}
}

func TestPeerTicketRejectExpired(t *testing.T) {
	in := CreatePeerTicket("peer-z", "pub-z", []string{"z:1"}, 2*time.Second)
	in.ExpiresAt = time.Now().Add(-time.Second).UnixMilli()
	in.Sig = peerTicketSig(in.PeerID, in.PubKey, in.Addrs, in.ExpiresAt, in.Nonce)
	if _, ok := ParsePeerTicketURI(BuildPeerTicketURI(in)); ok {
		t.Fatal("expected expired ticket rejection")
	}
}
