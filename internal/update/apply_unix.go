//go:build !windows

package update

import (
	"fmt"
	"os"
	"path/filepath"
	"syscall"
)

func Apply(destExe string, downloadURL string) error {
	dest, err := filepath.EvalSymlinks(destExe)
	if err != nil {
		dest = destExe
	}
	destAbs, err := filepath.Abs(dest)
	if err != nil {
		return err
	}
	tmp := destAbs + ".new"
	_ = os.Remove(tmp)
	if err := downloadToFile(downloadURL, tmp); err != nil {
		return err
	}
	if err := os.Chmod(tmp, 0755); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, destAbs); err != nil {
		argv := append([]string{destAbs}, os.Args[1:]...)
		if execErr := syscall.Exec(tmp, argv, os.Environ()); execErr != nil {
			_ = os.Remove(tmp)
			return fmt.Errorf("replace binary: %w; exec %s: %v", err, tmp, execErr)
		}
		return nil
	}
	return syscall.Exec(destAbs, os.Args, os.Environ())
}
