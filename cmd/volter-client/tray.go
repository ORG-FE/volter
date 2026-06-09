package main

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/ipc"
	"fyne.io/systray"
)

//go:embed tray_icon.png
var trayIconPNG []byte

//go:embed volter.ico
var trayIconICO []byte

var (
	trayMu         sync.Mutex
	trayConnected  bool
	trayConnLabel  string
	trayConnecting bool
	ipcServer      *ipc.Server
)

func runTray() error {
	systray.Run(trayOnReady, trayOnExit)
	return nil
}

func trayOnExit() {
	if ipcServer != nil {
		_ = ipcServer.Broadcast(ipc.Message{
			Type: ipc.MsgTypeQuit,
		})
		ipcServer.Close()
	}
}

func trayOnReady() {
	if runtime.GOOS == "windows" && len(trayIconICO) > 0 {
		systray.SetIcon(trayIconICO)
	} else if len(trayIconPNG) > 0 {
		systray.SetIcon(trayIconPNG)
	}
	systray.SetTooltip("Volter VPN")

	if runtime.GOOS == "linux" {
		_ = ipc.InitState()
		st := ipc.GetState()
		trayMu.Lock()
		trayConnected = st.Connected
		trayConnLabel = st.Profile
		trayMu.Unlock()
		go trayStateSyncLoop()
	}

	if runtime.GOOS == "linux" {
		socketPath := ipc.GetSocketPath()
		server, err := ipc.NewServer(socketPath, trayHandleIPCMessage)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Failed to start IPC server: %v\n", err)
		} else {
			ipcServer = server
			go ipcServer.Start()
		}
	}

	trayRefreshMenu()
}

func trayStateSyncLoop() {
	tick := time.NewTicker(2 * time.Second)
	defer tick.Stop()
	for range tick.C {
		if traySyncStateFromFile() {
			trayRefreshMenu()
		}
	}
}

func traySyncStateFromFile() bool {
	type stateOnDisk struct {
		Connected bool   `json:"connected"`
		Profile   string `json:"profile"`
		PID       int    `json:"pid"`
	}
	data, err := os.ReadFile(ipc.GetStateFile())
	if err != nil {
		return false
	}
	var st stateOnDisk
	if json.Unmarshal(data, &st) != nil {
		return false
	}
	if st.Connected && st.PID > 0 {
		if _, err := os.Stat(fmt.Sprintf("/proc/%d", st.PID)); err != nil {
			st.Connected = false
			st.Profile = ""
		}
	}
	trayMu.Lock()
	defer trayMu.Unlock()
	if trayConnected == st.Connected && trayConnLabel == st.Profile {
		return false
	}
	trayConnected = st.Connected
	trayConnLabel = st.Profile
	return true
}

func trayHandleIPCMessage(msg ipc.Message) {
	switch msg.Type {
	case ipc.MsgTypeStatus:
		trayMu.Lock()
		trayConnected = msg.Connected
		trayConnLabel = msg.Profile
		trayMu.Unlock()
		trayRefreshMenu()
		if msg.Connected {
			trayNotify("Volter", "Подключено: "+msg.Profile)
		} else if msg.Error != "" {
			trayNotify("Volter", "Ошибка: "+msg.Error)
		}
	case ipc.MsgTypeDisconnect:
		trayMu.Lock()
		trayConnected = false
		trayConnLabel = ""
		trayMu.Unlock()
		trayRefreshMenu()
		trayNotify("Volter", "Отключено")
	}
}

func trayNotify(title, msg string) {
	msg = strings.TrimSpace(msg)
	if msg == "" {
		return
	}
	if runtime.GOOS == "linux" {
		if path, err := exec.LookPath("notify-send"); err == nil {
			_ = exec.Command(path, title, msg).Run()
		}
	}
}

func trayDisconnectAll() {
	trayMu.Lock()
	trayConnected = false
	trayConnLabel = ""
	trayMu.Unlock()
	if runtime.GOOS == "linux" {
		ipc.SetConnected(false, "")
	}

	if ipcServer != nil {
		_ = ipcServer.Broadcast(ipc.Message{
			Type: ipc.MsgTypeDisconnect,
		})
	}

	trayRefreshMenu()
}

func trayRefreshMenu() {
	trayMu.Lock()
	defer trayMu.Unlock()
	systray.ResetMenu()

	st, _ := config.LoadClientSettings()
	modeLabel := "TUN"
	if st.Mode == "proxy" {
		modeLabel = "SOCKS5"
	}

	status := "Отключено"
	if trayConnected {
		status = "Подключено: " + trayConnLabel
	}
	mStat := systray.AddMenuItem("Статус: "+status, "Volter "+version)
	mStat.Disable()

	if strings.TrimSpace(st.LastProfile) != "" {
		last := strings.TrimSpace(st.LastProfile)
		mLast := systray.AddMenuItem("Последний профиль: "+last, "Подключить последний выбранный профиль")
		go trayWatchItem(mLast, func() { trayConnectProfile(last) })
	}

	profRoot := systray.AddMenuItem("Конфигурации", "Сохранённые профили")
	_, names, err := config.List()
	if err != nil {
		bad := profRoot.AddSubMenuItem("(ошибка списка)", err.Error())
		bad.Disable()
	} else if len(names) == 0 {
		empty := profRoot.AddSubMenuItem("(пусто)", "Создай профиль в TUI")
		empty.Disable()
	} else {
		sort.Strings(names)
		for _, n := range names {
			name := n
			mi := profRoot.AddSubMenuItem(name, "Подключить "+name)
			go trayWatchItem(mi, func() { trayConnectProfile(name) })
		}
	}

	mMode := systray.AddMenuItem("Режим: "+modeLabel, "Переключить TUN или SOCKS5 (как в настройках TUI)")
	go trayWatchItem(mMode, trayToggleMode)

	mDisc := systray.AddMenuItem("Отключить", "Остановить VPN")
	if !trayConnected {
		mDisc.Disable()
	}
	go trayWatchItem(mDisc, trayDisconnectAll)

	systray.AddSeparator()

	mTUI := systray.AddMenuItem("Открыть TUI", "Полный интерфейс в терминале")
	go trayWatchItem(mTUI, trayOpenTUI)

	mRef := systray.AddMenuItem("Обновить меню", "Перечитать список профилей")
	go trayWatchItem(mRef, trayRefreshMenu)

	systray.AddSeparator()

	mQuit := systray.AddMenuItem("Выход", "Закрыть трей и отключить VPN")
	go trayWatchItem(mQuit, func() { systray.Quit() })

	systray.SetTitle("Volter")
}

func trayWatchItem(mi *systray.MenuItem, fn func()) {
	go func() {
		for range mi.ClickedCh {
			fn()
		}
	}()
}

func trayToggleMode() {
	st, err := config.LoadClientSettings()
	if err != nil {
		trayNotify("Volter", err.Error())
		return
	}
	if st.Mode == "proxy" {
		st.Mode = "tun"
	} else {
		st.Mode = "proxy"
	}
	if err := config.SaveClientSettings(st); err != nil {
		trayNotify("Volter", err.Error())
		return
	}
	trayRefreshMenu()
}

func trayOpenTUI() {
	trayLaunchTUI("")
}

func trayConnectProfile(name string) {
	name = strings.TrimSpace(name)
	if name == "" {
		return
	}

	trayMu.Lock()
	if trayConnecting {
		trayMu.Unlock()
		return
	}
	trayConnecting = true
	trayMu.Unlock()

	cfg, err := config.LoadByName(name)
	if err != nil {
		trayMu.Lock()
		trayConnecting = false
		trayMu.Unlock()
		trayNotify("Volter", err.Error())
		return
	}
	if cfg.Server == "" || cfg.Token == "" {
		trayMu.Lock()
		trayConnecting = false
		trayMu.Unlock()
		trayNotify("Volter", "В профиле нет server/token")
		return
	}

	st, err := config.LoadClientSettings()
	if err != nil {
		trayMu.Lock()
		trayConnecting = false
		trayMu.Unlock()
		trayNotify("Volter", err.Error())
		return
	}
	st.LastProfile = name
	if err := config.SaveClientSettings(st); err != nil {
		trayMu.Lock()
		trayConnecting = false
		trayMu.Unlock()
		trayNotify("Volter", err.Error())
		return
	}

	trayDisconnectAll()

	if runtime.GOOS == "linux" {
		trayConnectViaTUI(name)
		trayMu.Lock()
		trayConnecting = false
		trayMu.Unlock()
		return
	}

	trayMu.Lock()
	trayConnecting = false
	trayMu.Unlock()
	trayNotify("Volter", "Use TUI to connect on this platform")
}

func trayConnectViaTUI(profile string) {
	if ipcServer != nil {
		_ = ipcServer.Broadcast(ipc.Message{
			Type:    ipc.MsgTypeConnect,
			Profile: profile,
		})
		return
	}

	trayLaunchTUI(profile)
}

func trayLaunchTUI(autoConnect string) {
	exe, err := os.Executable()
	if err != nil {
		trayNotify("Volter", err.Error())
		return
	}

	socketPath := ipc.GetSocketPath()
	home := os.Getenv("HOME")

	if runtime.GOOS == "windows" {
		args := []string{exe, "-tui", "-ipc-socket=" + socketPath}
		if autoConnect != "" {
			args = append(args, "-auto-connect="+autoConnect)
		}

		_ = exec.Command("cmd", append([]string{"/C", "start", ""}, args...)...).Start()
		return
	}

	usePkexec := false
	if runtime.GOOS == "linux" {
		if _, err := exec.LookPath("pkexec"); err == nil {
			usePkexec = true
		}
	}

	var tuiArgs []string
	if usePkexec {
		bin := "volter-client"
		if _, err := exec.LookPath(bin); err != nil {
			bin = exe
		}
		tuiArgs = []string{"pkexec", "env", "HOME=" + home, "USER=" + os.Getenv("USER"),
			"LOGNAME=" + os.Getenv("LOGNAME"), bin, "-tui", "-ipc-socket=" + socketPath}
	} else {
		tuiArgs = []string{exe, "-tui", "-ipc-socket=" + socketPath}
	}
	if autoConnect != "" {
		tuiArgs = append(tuiArgs, "-auto-connect="+autoConnect)
	}

	if term := strings.TrimSpace(os.Getenv("VOLTER_TERMINAL")); term != "" {
		_ = exec.Command(term, "-e", "sh", "-lc", "exec env HOME="+home+" "+strings.Join(quoteArgs(tuiArgs), " ")).Start()
		return
	}
	candidates := []struct {
		bin  string
		args []string
	}{
		{"x-terminal-emulator", append([]string{"-e"}, tuiArgs...)},
		{"konsole", append([]string{"-e"}, tuiArgs...)},
		{"gnome-terminal", append([]string{"--"}, tuiArgs...)},
		{"kitty", append([]string{"-e"}, tuiArgs...)},
		{"alacritty", append([]string{"-e"}, tuiArgs...)},
		{"foot", append([]string{"-e"}, tuiArgs...)},
	}
	for _, c := range candidates {
		if path, err := exec.LookPath(c.bin); err == nil {
			_ = exec.Command(path, c.args...).Start()
			return
		}
	}
	trayNotify("Volter", "Не найден терминал. Задай VOLTER_TERMINAL или поставь konsole/gnome-terminal.")
}

func quoteArgs(args []string) []string {
	quoted := make([]string, len(args))
	for i, a := range args {
		if strings.ContainsAny(a, ` '"\$`) {
			quoted[i] = fmt.Sprintf("%q", a)
		} else {
			quoted[i] = a
		}
	}
	return quoted
}
