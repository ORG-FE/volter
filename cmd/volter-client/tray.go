package main

import (
	_ "embed"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"

	"dev.c0redev.volter/internal/config"
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
	trayStop       func()
	trayChild      *exec.Cmd
	trayConnecting bool
)

func runTray() error {
	systray.Run(trayOnReady, trayOnExit)
	return nil
}

func trayOnExit() {
	trayDisconnectAll()
}

func trayOnReady() {
	if runtime.GOOS == "windows" && len(trayIconICO) > 0 {
		systray.SetIcon(trayIconICO)
	} else if len(trayIconPNG) > 0 {
		systray.SetIcon(trayIconPNG)
	}
	systray.SetTooltip("Volter VPN")
	trayRefreshMenu()
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
	stop := trayStop
	child := trayChild
	trayStop = nil
	trayChild = nil
	trayConnected = false
	trayConnLabel = ""
	trayMu.Unlock()

	if stop != nil {
		stop()
	}
	if child != nil && child.Process != nil {
		_ = child.Process.Kill()
		go func(c *exec.Cmd) { _ = c.Wait() }(child)
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
	exe, err := os.Executable()
	if err != nil {
		trayNotify("Volter", err.Error())
		return
	}
	switch runtime.GOOS {
	case "windows":
		c := exec.Command("cmd", "/c", "start", "Volter", exe, "-tui")
		_ = c.Start()
	case "linux":
		cmdline := fmt.Sprintf(`exec pkexec %q -tui`, exe)
		if term := strings.TrimSpace(os.Getenv("VOLTER_TERMINAL")); term != "" {
			_ = exec.Command(term, "-e", "sh", "-lc", cmdline).Start()
			return
		}
		candidates := []struct {
			bin  string
			args []string
		}{
			{"x-terminal-emulator", []string{"-e", "sh", "-lc", cmdline}},
			{"konsole", []string{"-e", "sh", "-lc", cmdline}},
			{"gnome-terminal", []string{"--", "sh", "-lc", cmdline}},
			{"kitty", []string{"sh", "-lc", cmdline}},
			{"alacritty", []string{"-e", "sh", "-lc", cmdline}},
			{"foot", []string{"sh", "-lc", cmdline}},
		}
		for _, c := range candidates {
			if path, err := exec.LookPath(c.bin); err == nil {
				args := append([]string{path}, c.args...)
				_ = exec.Command(args[0], args[1:]...).Start()
				return
			}
		}
		trayNotify("Volter", "Не найден терминал. Задай VOLTER_TERMINAL или поставь konsole/gnome-terminal.")
	default:
		_ = exec.Command(exe, "-tui").Start()
	}
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

	if runtime.GOOS == "linux" && os.Geteuid() != 0 {
		err := trayConnectPkexec(name)
		trayMu.Lock()
		trayConnecting = false
		trayMu.Unlock()
		if err != nil {
			trayNotify("Volter", err.Error())
		}
		return
	}

	go func() {
		defer func() {
			trayMu.Lock()
			trayConnecting = false
			trayMu.Unlock()
		}()
		stop, err := connectVPN(cfg, name, 0, st, nil)
		if err != nil {
			trayNotify("Volter", err.Error())
			trayMu.Lock()
			trayConnected = false
			trayConnLabel = ""
			trayStop = nil
			trayMu.Unlock()
			trayRefreshMenu()
			return
		}
		trayMu.Lock()
		trayStop = stop
		trayConnected = true
		trayConnLabel = name
		trayMu.Unlock()
		trayRefreshMenu()
	}()
}

func trayConnectPkexec(profile string) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	if _, err := exec.LookPath("pkexec"); err != nil {
		return errors.New("нужен pkexec для VPN без root; запусти sudo volter-client -tray или клиент из TUI")
	}
	cmd := exec.Command("pkexec", exe, "--profile", profile)
	cmd.Env = trayPkexecEnv()
	if err := cmd.Start(); err != nil {
		return err
	}
	trayMu.Lock()
	trayChild = cmd
	trayConnected = true
	trayConnLabel = profile
	trayMu.Unlock()
	trayRefreshMenu()

	go func(c *exec.Cmd) {
		waitErr := c.Wait()
		trayMu.Lock()
		active := trayChild == c
		trayMu.Unlock()
		if !active {
			return
		}
		trayMu.Lock()
		if trayChild == c {
			trayChild = nil
			trayConnected = false
			trayConnLabel = ""
		}
		trayMu.Unlock()
		if waitErr != nil && !strings.Contains(strings.ToLower(waitErr.Error()), "signal") {
			trayNotify("Volter", "VPN завершился: "+waitErr.Error())
		}
		trayRefreshMenu()
	}(cmd)
	return nil
}

func trayPkexecEnv() []string {
	base := os.Environ()
	home := strings.TrimSpace(os.Getenv("HOME"))
	if home == "" {
		return base
	}
	cfgDir := strings.TrimSpace(os.Getenv("XDG_CONFIG_HOME"))
	if cfgDir == "" {
		cfgDir = filepath.Join(home, ".config")
	}
	extra := []string{
		"HOME=" + home,
		"XDG_CONFIG_HOME=" + cfgDir,
	}
	return append(base, extra...)
}
