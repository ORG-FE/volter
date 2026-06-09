//go:build linux

package main

import (
	"github.com/godbus/dbus/v5"
)

func statusNotifierWatcherAvailable() bool {
	conn, err := dbus.SessionBus()
	if err != nil {
		return false
	}

	obj := conn.Object("org.freedesktop.DBus", "/org/freedesktop/DBus")
	call := obj.Call("org.freedesktop.DBus.NameHasOwner", 0, "org.kde.StatusNotifierWatcher")
	if call.Err != nil {
		return false
	}
	var has bool
	if err := call.Store(&has); err != nil {
		return false
	}
	return has
}
