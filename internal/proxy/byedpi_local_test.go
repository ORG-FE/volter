package proxy

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestFindByedpiInDir(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "ciadpi")
	if err := os.WriteFile(p, []byte("#fake\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := FindByedpiInDir(dir); got != p {
		t.Fatalf("want %q got %q", p, got)
	}
	if FindByedpiInDir(filepath.Join(dir, "missing")) != "" {
		t.Fatal("expected empty for missing subdir")
	}
}

func TestAppendPresetWithoutListen(t *testing.T) {
	base := []string{"/bin/ciadpi", "-i", "127.0.0.1", "-p", "9"}
	got := appendPresetWithoutListen(base, "--disorder 1 -i 9.9.9.9 -p 999 --ttl 8")
	s := strings.Join(got, " ")
	if strings.Contains(s, "9.9.9.9") || strings.Contains(s, " 999") {
		t.Fatalf("preset listen leaked: %s", s)
	}
	if !strings.Contains(s, "--disorder") || !strings.Contains(s, "--ttl") {
		t.Fatalf("lost dpi flags: %s", s)
	}
}
