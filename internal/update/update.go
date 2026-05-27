package update

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const (
	defaultAPIBase = "https://api.github.com"
	checksumAsset  = "SHA256SUMS"
)

var Repository = "ORG-FE/volter"

type ghReleaseFull struct {
	TagName string    `json:"tag_name"`
	Assets  []ghAsset `json:"assets"`
}

type ghAsset struct {
	Name               string `json:"name"`
	BrowserDownloadURL string `json:"browser_download_url"`
}

type Asset struct {
	Name   string
	URL    string
	SHA256 string
}

func apiURL(path string) (string, error) {
	repo := strings.Trim(strings.TrimSpace(os.Getenv("VOLTER_UPDATE_REPO")), "/")
	if repo == "" {
		repo = strings.Trim(strings.TrimSpace(Repository), "/")
	}
	if repo == "" || strings.Count(repo, "/") != 1 {
		return "", fmt.Errorf("bad update repo %q", repo)
	}
	base := strings.TrimRight(strings.TrimSpace(os.Getenv("VOLTER_UPDATE_API")), "/")
	if base == "" {
		base = defaultAPIBase
	}
	return base + "/repos/" + repo + path, nil
}

func getRelease(endpoint string) (*ghReleaseFull, error) {
	client := &http.Client{Timeout: 15 * time.Second}
	req, err := http.NewRequest("GET", endpoint, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/vnd.github.v3+json")
	req.Header.Set("User-Agent", "volter-client-updater")
	if token := strings.TrimSpace(os.Getenv("GITHUB_TOKEN")); token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("github api: %s", resp.Status)
	}
	var r ghReleaseFull
	if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
		return nil, err
	}
	return &r, nil
}

func CheckLatest(current string) (latest string, err error) {
	u, err := apiURL("/releases/latest")
	if err != nil {
		return "", err
	}
	r, err := getRelease(u)
	if err != nil {
		return "", err
	}
	latest = strings.TrimSpace(r.TagName)
	if latest == "" {
		return "", fmt.Errorf("empty tag")
	}
	if current == "dev" || current == "" {
		return latest, nil
	}
	if Newer(latest, current) {
		return latest, nil
	}
	return "", nil
}

func AssetDownloadURLForTag(tag string) (string, error) {
	a, err := AssetForTag(tag)
	if err != nil {
		return "", err
	}
	return a.URL, nil
}

func AssetForTag(tag string) (Asset, error) {
	tag = strings.TrimSpace(tag)
	if tag == "" {
		return Asset{}, fmt.Errorf("empty tag")
	}
	endpoint, err := apiURL("/releases/tags/" + url.PathEscape(tag))
	if err != nil {
		return Asset{}, err
	}
	r, err := getRelease(endpoint)
	if err != nil {
		return Asset{}, err
	}
	a, err := pickAsset(r)
	if err != nil {
		return Asset{}, err
	}
	a.SHA256 = checksumForAsset(r, a.Name)
	return a, nil
}

func pickAssetURL(r *ghReleaseFull) (string, error) {
	a, err := pickAsset(r)
	if err != nil {
		return "", err
	}
	return a.URL, nil
}

func pickAsset(r *ghReleaseFull) (Asset, error) {
	want, err := expectedAssetName()
	if err != nil {
		return Asset{}, err
	}
	for _, a := range r.Assets {
		if a.Name == want {
			if a.BrowserDownloadURL == "" {
				return Asset{}, fmt.Errorf("empty download url for %s", want)
			}
			return Asset{Name: a.Name, URL: a.BrowserDownloadURL}, nil
		}
	}
	return Asset{}, fmt.Errorf("release has no %s", want)
}

func expectedAssetName() (string, error) {
	switch runtime.GOOS + "/" + runtime.GOARCH {
	case "windows/amd64":
		return "volter-client-windows-amd64.exe", nil
	case "linux/amd64":
		return "volter-client-linux-amd64", nil
	case "linux/arm64":
		return "volter-client-linux-arm64", nil
	default:
		return "", fmt.Errorf("unsupported platform %s/%s", runtime.GOOS, runtime.GOARCH)
	}
}

func checksumForAsset(r *ghReleaseFull, name string) string {
	for _, a := range r.Assets {
		if a.Name == name+".sha256" || a.Name == checksumAsset {
			if sum, err := fetchChecksum(a.BrowserDownloadURL, name); err == nil {
				return sum
			}
		}
	}
	return ""
}

func fetchChecksum(downloadURL, assetName string) (string, error) {
	if strings.TrimSpace(downloadURL) == "" {
		return "", fmt.Errorf("empty checksum url")
	}
	client := &http.Client{Timeout: 15 * time.Second}
	req, err := http.NewRequest("GET", downloadURL, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Accept", "text/plain,*/*")
	req.Header.Set("User-Agent", "volter-client-updater")
	if token := strings.TrimSpace(os.Getenv("GITHUB_TOKEN")); token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("checksum download: %s", resp.Status)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", err
	}
	return parseChecksum(string(b), assetName)
}

func parseChecksum(text, assetName string) (string, error) {
	for _, line := range strings.Split(text, "\n") {
		fields := strings.Fields(strings.TrimSpace(line))
		if len(fields) == 0 || len(fields[0]) != 64 || !isHex(fields[0]) {
			continue
		}
		if len(fields) == 1 || strings.TrimPrefix(fields[len(fields)-1], "*") == assetName {
			return strings.ToLower(fields[0]), nil
		}
	}
	return "", fmt.Errorf("checksum for %s not found", assetName)
}

func isHex(s string) bool {
	for _, r := range s {
		if (r >= '0' && r <= '9') || (r >= 'a' && r <= 'f') || (r >= 'A' && r <= 'F') {
			continue
		}
		return false
	}
	return true
}

func Newer(a, b string) bool {
	va := parseVersion(a)
	vb := parseVersion(b)
	for i := 0; i < 3; i++ {
		na := 0
		nb := 0
		if i < len(va) {
			na = va[i]
		}
		if i < len(vb) {
			nb = vb[i]
		}
		if na > nb {
			return true
		}
		if na < nb {
			return false
		}
	}
	return false
}

func parseVersion(s string) []int {
	s = strings.TrimPrefix(strings.TrimSpace(s), "v")
	parts := strings.Split(s, ".")
	var out []int
	for _, p := range parts {
		n, _ := strconv.Atoi(strings.TrimSpace(p))
		out = append(out, n)
		if len(out) >= 3 {
			break
		}
	}
	return out
}
