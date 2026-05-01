//go:build !windows

package proxy

import (
	"os/exec"
	"syscall"
	"time"
)

func prepareByedpiCmd(cmd *exec.Cmd) {
	if cmd == nil {
		return
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
}

func killProc(cmd *exec.Cmd) {
	if cmd == nil || cmd.Process == nil {
		return
	}
	pid := cmd.Process.Pid
	_ = syscall.Kill(-pid, syscall.SIGTERM)
	done := make(chan struct{})
	go func() {
		_ = cmd.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(400 * time.Millisecond):
		_ = syscall.Kill(-pid, syscall.SIGKILL)
	}
}
