//go:build !linux

package main

func autoInstallDesktopIntegration() {}

func installDesktopIntegration() error { return nil }
