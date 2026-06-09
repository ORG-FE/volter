package proxy

import (
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"dev.c0redev.volter/internal/clientlog"
)

func resolveByedpiBin() (string, error) {
	if e := strings.TrimSpace(os.Getenv("VOLTER_BYEDPI_BIN")); e != "" {
		if filepath.IsAbs(e) || strings.ContainsRune(e, os.PathSeparator) {
			st, err := os.Stat(e)
			if err != nil {
				return "", err
			}
			if st.IsDir() {
				return "", errors.New("VOLTER_BYEDPI_BIN is directory")
			}
			return filepath.Clean(e), nil
		}
		return exec.LookPath(e)
	}
	for _, name := range []string{"ciadpi", "byedpi"} {
		p, err := exec.LookPath(name)
		if err == nil {
			return p, nil
		}
	}
	return "", errors.New("нет ciadpi/byedpi в PATH: установи byedpi или задай VOLTER_BYEDPI_BIN")
}

func FindByedpiInDir(dir string) string {
	dir = strings.TrimSpace(dir)
	if dir == "" {
		return ""
	}
	for _, name := range []string{"ciadpi", "byedpi"} {
		p := filepath.Join(dir, name)
		st, err := os.Stat(p)
		if err != nil || st.IsDir() {
			continue
		}
		return p
	}
	return ""
}

func appendPresetWithoutListen(argv []string, presetLine string) []string {
	f := strings.Fields(strings.TrimSpace(presetLine))
	for i := 0; i < len(f); i++ {
		t := f[i]
		if t == "-i" || t == "--ip" || t == "-p" || t == "--port" {
			i++
			continue
		}
		argv = append(argv, t)
	}
	return argv
}

func waitDialTCP(addr string, d time.Duration) bool {
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		c, err := net.DialTimeout("tcp", addr, 80*time.Millisecond)
		if err == nil {
			_ = c.Close()
			return true
		}
		time.Sleep(40 * time.Millisecond)
	}
	return false
}

func StartByedpiLocalSocks(ctx context.Context, presetLine string, binOverride string) (socksListenAddr string, stop func(), err error) {
	var bin string
	if b := strings.TrimSpace(binOverride); b != "" {
		st, e := os.Stat(b)
		if e != nil {
			return "", nil, e
		}
		if st.IsDir() {
			return "", nil, errors.New("byedpi bin path is directory")
		}
		bin = filepath.Clean(b)
	} else {
		bin, err = resolveByedpiBin()
		if err != nil {
			return "", nil, err
		}
	}
	if ctx == nil {
		ctx = context.Background()
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", nil, err
	}
	ap := ln.Addr().(*net.TCPAddr)
	host := ap.IP.String()
	port := ap.Port
	_ = ln.Close()

	args := []string{bin, "-i", host, "-p", strconv.Itoa(port)}
	args = appendPresetWithoutListen(args, presetLine)
	cctx, cancel := context.WithCancel(ctx)
	cmd := exec.CommandContext(cctx, args[0], args[1:]...)
	prepareByedpiCmd(cmd)
	cmd.Stdout = nil
	cmd.Stderr = nil
	if err := cmd.Start(); err != nil {
		cancel()
		return "", nil, err
	}
	addr := net.JoinHostPort(host, strconv.Itoa(port))
	if !waitDialTCP(addr, 8*time.Second) {
		killProc(cmd)
		cancel()
		return "", nil, fmt.Errorf("ciadpi не поднял SOCKS за 8s на %s", addr)
	}
	clientlog.OK("proxy: byedpi слушает %s (%s)", addr, bin)
	stop = func() {
		killProc(cmd)
		cancel()
	}
	return addr, stop, nil
}
