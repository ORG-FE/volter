package discovery

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestFetchBootstrapBody(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("User-Agent") == "" {
			t.Fatal("missing ua")
		}
		_, _ = io.WriteString(w, `{"epochSec":1}`)
	}))
	defer ts.Close()
	b, err := FetchBootstrapBody(context.Background(), ts.URL)
	if err != nil || !strings.Contains(string(b), "epochSec") {
		t.Fatalf("got %q err=%v", b, err)
	}
}
