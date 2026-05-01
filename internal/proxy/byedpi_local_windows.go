//go:build windows

package proxy

import "os/exec"

func prepareByedpiCmd(cmd *exec.Cmd) {}

func killProc(cmd *exec.Cmd) {
	if cmd == nil || cmd.Process == nil {
		return
	}
	_ = cmd.Process.Kill()
	_ = cmd.Wait()
}
