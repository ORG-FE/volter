//go:build windows

package main

import (
	"syscall"
)

func detachConsole() {
	kernel32 := syscall.NewLazyDLL("kernel32.dll")
	freeConsole := kernel32.NewProc("FreeConsole")
	_, _, _ = freeConsole.Call()

	user32 := syscall.NewLazyDLL("user32.dll")
	getConsoleWindow := kernel32.NewProc("GetConsoleWindow")
	showWindow := user32.NewProc("ShowWindow")
	const swHide = 0
	if hwnd, _, _ := getConsoleWindow.Call(); hwnd != 0 {
		_, _, _ = showWindow.Call(hwnd, uintptr(swHide))
	}
}
