package update

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
)

func Apply(destExe string, downloadURL string) error {
	destExe, err := filepath.EvalSymlinks(destExe)
	if err != nil {
		return err
	}
	destAbs, err := filepath.Abs(destExe)
	if err != nil {
		return err
	}
	dir := filepath.Dir(destAbs)
	base := filepath.Base(destAbs)
	newPath := filepath.Join(dir, base+".new")
	_ = os.Remove(newPath)
	if err := downloadToFile(downloadURL, newPath); err != nil {
		return err
	}
	batName := fmt.Sprintf("volter-self-update-%d.bat", os.Getpid())
	batPath := filepath.Join(dir, batName)
	argsLine := batchArgs(os.Args[1:])
	bat := buildSelfUpdateBat(destAbs, newPath, batPath, argsLine)
	if err := os.WriteFile(batPath, []byte(bat), 0600); err != nil {
		_ = os.Remove(newPath)
		return err
	}
	cmd := exec.Command("cmd.exe", "/C", batPath)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	if err := cmd.Start(); err != nil {
		_ = os.Remove(newPath)
		_ = os.Remove(batPath)
		return err
	}
	return nil
}

func buildSelfUpdateBat(destAbs, newPath, batPath, argsLine string) string {
	q := func(p string) string { return `"` + strings.ReplaceAll(p, `"`, `""`) + `"` }
	var b strings.Builder
	b.WriteString("@echo off\r\n")
	b.WriteString("setlocal\r\n")
	b.WriteString("REM Wait for the updater process to exit and release the exe.\r\n")
	b.WriteString("ping -n 4 127.0.0.1 >nul\r\n")
	b.WriteString("set attempts=0\r\n")
	b.WriteString(":retry\r\n")
	b.WriteString("set /a attempts+=1\r\n")
	fmt.Fprintf(&b, "move /Y %s %s >nul 2>&1\r\n", q(newPath), q(destAbs))
	b.WriteString("if %errorlevel%==0 goto launch\r\n")
	b.WriteString("if %attempts% geq 60 goto trycopy\r\n")
	b.WriteString("ping -n 2 127.0.0.1 >nul\r\n")
	b.WriteString("goto retry\r\n")
	b.WriteString(":trycopy\r\n")
	fmt.Fprintf(&b, "copy /B /Y %s %s >nul 2>&1\r\n", q(newPath), q(destAbs))
	b.WriteString("if errorlevel 1 goto fail\r\n")
	fmt.Fprintf(&b, "del /F /Q %s >nul 2>&1\r\n", q(newPath))
	b.WriteString("goto launch\r\n")
	b.WriteString(":launch\r\n")
	fmt.Fprintf(&b, `start "" %s%s`+"\r\n", q(destAbs), argsLine)
	fmt.Fprintf(&b, "del /F /Q %s >nul 2>&1\r\n", q(batPath))
	b.WriteString("goto eof\r\n")
	b.WriteString(":fail\r\n")
	fmt.Fprintf(&b, "del /F /Q %s >nul 2>&1\r\n", q(batPath))
	b.WriteString(":eof\r\n")
	b.WriteString("endlocal\r\n")
	return b.String()
}

func batchArgs(osArgs []string) string {
	if len(osArgs) == 0 {
		return ""
	}
	var b strings.Builder
	for _, a := range osArgs {
		if strings.ContainsAny(a, " \t\"") {
			b.WriteString(` "`)
			b.WriteString(strings.ReplaceAll(a, `"`, `\"`))
			b.WriteString(`"`)
			continue
		}
		b.WriteString(" ")
		b.WriteString(a)
	}
	return b.String()
}
