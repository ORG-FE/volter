package update

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

func downloadToFile(url, destPath string) error {
	return downloadAssetToFile(Asset{URL: url}, destPath)
}

func downloadAssetToFile(asset Asset, destPath string) (err error) {
	downloadURL := strings.TrimSpace(asset.URL)
	if downloadURL == "" {
		return fmt.Errorf("empty download url")
	}
	client := &http.Client{Timeout: 120 * time.Second}
	req, err := http.NewRequest("GET", downloadURL, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "*/*")
	req.Header.Set("User-Agent", "volter-client-updater")
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("download: %s", resp.Status)
	}
	tmp := destPath + ".partial"
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0600)
	if err != nil {
		return err
	}
	h := sha256.New()
	n, copyErr := io.Copy(io.MultiWriter(f, h), resp.Body)
	closeErr := f.Close()
	if copyErr != nil {
		_ = os.Remove(tmp)
		return copyErr
	}
	if closeErr != nil {
		_ = os.Remove(tmp)
		return closeErr
	}
	if n == 0 {
		_ = os.Remove(tmp)
		return fmt.Errorf("empty download")
	}
	if want := strings.TrimSpace(asset.SHA256); want != "" {
		got := hex.EncodeToString(h.Sum(nil))
		if !strings.EqualFold(got, want) {
			_ = os.Remove(tmp)
			return fmt.Errorf("sha256 mismatch for %s", asset.Name)
		}
	}
	if err := os.Rename(tmp, destPath); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}
