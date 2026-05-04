package ipc

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"sync"
)

type State struct {
	Connected bool   `json:"connected"`
	Profile   string `json:"profile"`
	PID       int    `json:"pid"`
	mu        sync.Mutex
}

var globalState *State
var stateFile string

func InitState() error {
	home := os.Getenv("HOME")
	if home == "" {
		home = "/tmp"
	}
	stateFile = filepath.Join(home, ".volter-state.json")
	
	globalState = &State{}
	
	if data, err := os.ReadFile(stateFile); err == nil {
		if err := json.Unmarshal(data, globalState); err != nil {
			return err
		}
	}
	
	if globalState.Connected && globalState.PID > 0 {
		pidPath := filepath.Join("/proc", strconv.Itoa(globalState.PID))
		if _, err := os.Stat(pidPath); err != nil {
			globalState.Connected = false
			globalState.Profile = ""
			globalState.PID = 0
			saveState()
		}
	}
	
	return nil
}

func SetConnected(connected bool, profile string) {
	if globalState == nil {
		return
	}
	globalState.mu.Lock()
	defer globalState.mu.Unlock()
	
	globalState.Connected = connected
	globalState.Profile = profile
	if connected {
		globalState.PID = os.Getpid()
	} else {
		globalState.PID = 0
	}
	
	saveState()
}

func GetState() State {
	if globalState == nil {
		return State{}
	}
	globalState.mu.Lock()
	defer globalState.mu.Unlock()
	
	return *globalState
}

func saveState() {
	if globalState == nil || stateFile == "" {
		return
	}
	
	data, err := json.Marshal(globalState)
	if err != nil {
		return
	}
	
	_ = os.WriteFile(stateFile, data, 0644)
}

func GetStateFile() string {
	if stateFile != "" {
		return stateFile
	}
	home := os.Getenv("HOME")
	if home == "" {
		home = "/tmp"
	}
	return filepath.Join(home, ".volter-state.json")
}
