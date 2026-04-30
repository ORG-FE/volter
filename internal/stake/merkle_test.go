package stake

import (
	"context"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"dev.c0redev.volter/internal/discovery"
)

func TestMerkleVerifyTwoLeaves(t *testing.T) {
	la := leafHash("a", 10)
	lb := leafHash("b", 20)
	root := parentHash(la, lb)
	proofB := hex.EncodeToString(lb)
	if !verifyToRoot(root, la, []string{proofB}) {
		t.Fatal("proof a")
	}
	proofA := hex.EncodeToString(la)
	if !verifyToRoot(root, lb, []string{proofA}) {
		t.Fatal("proof b")
	}
}

func TestMergeMerkle(t *testing.T) {
	la := leafHash("n1", 100)
	lb := leafHash("n2", 1)
	root := parentHash(la, lb)
	f := t.TempDir() + "/m.json"
	raw := `{"root":"` + hex.EncodeToString(root) + `","nodes":{"n1":{"stake":100,"proof":["` + hex.EncodeToString(lb) + `"]}}}`
	if err := os.WriteFile(f, []byte(raw), 0600); err != nil {
		t.Fatal(err)
	}
	nodes := []discovery.RelayNode{{ID: "n1", Stake: 0}, {ID: "n2", Stake: 0}}
	out := MergeMerkle(nodes, f)
	if out[0].Stake != 100 {
		t.Fatalf("stake %d", out[0].Stake)
	}
}

func TestMergeMerkleFromURLRootOverridesFile(t *testing.T) {
	la := leafHash("n1", 100)
	sib := leafHash("sib", 1)
	trueRoot := parentHash(la, sib)
	wrong := make([]byte, 32)
	for i := range wrong {
		wrong[i] = byte(0x25 + i)
	}
	f := t.TempDir() + "/m.json"
	raw := `{"root":"` + hex.EncodeToString(wrong) + `","nodes":{"n1":{"stake":100,"proof":["` + hex.EncodeToString(sib) + `"]}}}`
	if err := os.WriteFile(f, []byte(raw), 0600); err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"root":"` + hex.EncodeToString(trueRoot) + `"}`))
	}))
	defer ts.Close()
	nodes := []discovery.RelayNode{{ID: "n1", Stake: 0}}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	out := MergeMerkleFromSources(ctx, nodes, f, ts.URL)
	if out[0].Stake != 100 {
		t.Fatalf("expected stake 100 with URL root, got %d", out[0].Stake)
	}
	outBad := MergeMerkle(nodes, f)
	if outBad[0].Stake != 0 {
		t.Fatalf("wrong file root should not verify without URL")
	}
}
