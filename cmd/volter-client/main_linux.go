//go:build linux

package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"time"

	"dev.c0redev.volter/internal/clientlog"
	"dev.c0redev.volter/internal/ipc"
	"dev.c0redev.volter/internal/netcfg"
	"dev.c0redev.volter/internal/proxy"
	"dev.c0redev.volter/internal/tun"
	"dev.c0redev.volter/internal/tunnel"
	"dev.c0redev.volter/internal/vpn"
	"github.com/xjasonlyu/tun2socks/v2/core/device"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
)

func autoInstallDesktopIntegration() {
	if os.Getenv("VOLTER_SKIP_DESKTOP_INSTALL") == "1" {
		return
	}
	if desktopIntegrationInstalled() {
		return
	}
	installer := findDesktopInstaller()
	if installer != "" && os.Geteuid() == 0 {
		_ = exec.Command(installer).Run()
		return
	}
	if installer == "" && os.Geteuid() == 0 {
		_ = installDesktopIntegration()
		return
	}
	if _, err := exec.LookPath("pkexec"); err != nil {
		return
	}
	if installer != "" {
		_ = exec.Command("pkexec", installer).Run()
		return
	}
	exe, err := os.Executable()
	if err != nil {
		return
	}
	_ = exec.Command("pkexec", exe, "--install-desktop").Run()
}

func desktopIntegrationInstalled() bool {
	for _, p := range []string{
		"/usr/share/volter/volter-tui-launcher",
		"/usr/share/applications/dev.c0redev.volter.desktop",
		"/usr/share/polkit-1/actions/dev.c0redev.volter.policy",
		"/usr/share/icons/hicolor/scalable/apps/volter.svg",
	} {
		if _, err := os.Stat(p); err != nil {
			return false
		}
	}
	return true
}

func findDesktopInstaller() string {
	exe, _ := os.Executable()
	exeDir := filepath.Dir(exe)
	wd, _ := os.Getwd()
	candidates := []string{
		filepath.Join(wd, "contrib/scripts/install-linux-desktop"),
		filepath.Join(exeDir, "contrib/scripts/install-linux-desktop"),
		filepath.Join(exeDir, "../contrib/scripts/install-linux-desktop"),
		filepath.Join(exeDir, "../../contrib/scripts/install-linux-desktop"),
	}
	for _, p := range candidates {
		if st, err := os.Stat(p); err == nil && !st.IsDir() {
			return p
		}
	}
	return ""
}

func installDesktopIntegration() error {
	if os.Geteuid() != 0 {
		return fmt.Errorf("desktop install requires root")
	}
	files := map[string]struct {
		mode os.FileMode
		data string
	}{
		"/usr/share/volter/volter-tui-launcher":                 {0755, embeddedLauncher},
		"/usr/share/applications/dev.c0redev.volter.desktop":    {0644, embeddedDesktop},
		"/usr/share/polkit-1/actions/dev.c0redev.volter.policy": {0644, embeddedPolicy},
		"/usr/share/icons/hicolor/scalable/apps/volter.svg":     {0644, embeddedIconSVG},
	}
	for path, f := range files {
		if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
			return err
		}
		if err := os.WriteFile(path, []byte(f.data), f.mode); err != nil {
			return err
		}
	}
	_ = exec.Command("gtk-update-icon-cache", "-f", "/usr/share/icons/hicolor").Run()
	_ = exec.Command("update-desktop-database", "/usr/share/applications").Run()
	return nil
}

const embeddedDesktop = `[Desktop Entry]
Type=Application
Name=Volter VPN
Comment=Open Volter TUI with administrator privileges
Exec=/usr/share/volter/volter-tui-launcher
Icon=volter
Terminal=false
Categories=Network;Security;
StartupNotify=true
`

const embeddedLauncher = `#!/bin/sh
set -eu
VOLTER_BIN="${VOLTER_BIN:-/usr/bin/volter-client}"
if [ ! -x "$VOLTER_BIN" ] && command -v volter-client >/dev/null 2>&1; then
  VOLTER_BIN="$(command -v volter-client)"
fi
CMD="exec pkexec \"$VOLTER_BIN\""
if [ -t 0 ] && [ -t 1 ]; then
  exec pkexec "$VOLTER_BIN"
fi
if [ -n "${VOLTER_TERMINAL:-}" ]; then exec "$VOLTER_TERMINAL" -e sh -lc "$CMD"; fi
if command -v x-terminal-emulator >/dev/null 2>&1; then exec x-terminal-emulator -e sh -lc "$CMD"; fi
if command -v konsole >/dev/null 2>&1; then exec konsole -e sh -lc "$CMD"; fi
if command -v gnome-terminal >/dev/null 2>&1; then exec gnome-terminal -- sh -lc "$CMD"; fi
if command -v kitty >/dev/null 2>&1; then exec kitty sh -lc "$CMD"; fi
if command -v alacritty >/dev/null 2>&1; then exec alacritty -e sh -lc "$CMD"; fi
if command -v foot >/dev/null 2>&1; then exec foot sh -lc "$CMD"; fi
echo "Volter: no terminal emulator found. Set VOLTER_TERMINAL." >&2
exit 127
`

const embeddedPolicy = `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE policyconfig PUBLIC "-//freedesktop//DTD PolicyKit Policy Configuration 1.0//EN" "http://www.freedesktop.org/standards/PolicyKit/1/policyconfig.dtd">
<policyconfig>
  <vendor>Volter</vendor>
  <vendor_url>https://github.com/ORG-FE/volter</vendor_url>
  <action id="dev.c0redev.volter.pkexec">
    <description>Run Volter VPN</description>
    <message>Authentication is required to run Volter VPN with network privileges.</message>
    <defaults>
      <allow_any>auth_admin</allow_any>
      <allow_inactive>auth_admin</allow_inactive>
      <allow_active>auth_admin_keep</allow_active>
    </defaults>
    <annotate key="org.freedesktop.policykit.exec.path">/usr/bin/volter-client</annotate>
    <annotate key="org.freedesktop.policykit.exec.allow_gui">true</annotate>
  </action>
</policyconfig>
`

const embeddedIconSVG = `<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">
  <rect width="108" height="108" fill="#050505"/>
  <path d="M54 18 L84 34 L84 72 L54 90 L24 72 L24 34 Z" fill="none" stroke="#f5f5f5" stroke-width="2.4"/>
  <path fill="#f5f5f5" d="M38 30 L54 76 L70 30 L62 34 L54 66 L46 34 Z"/>
</svg>
`

func dedupeIPs(ips []net.IP) []net.IP {
	seen := make(map[string]struct{})
	var out []net.IP
	for _, ip := range ips {
		if ip == nil {
			continue
		}
		k := ip.String()
		if _, ok := seen[k]; ok {
			continue
		}
		seen[k] = struct{}{}
		out = append(out, ip)
	}
	return out
}

func runPlatform(ctx context.Context, addrs []string, opts runOpts, onReady func()) error {
	if os.Geteuid() != 0 {
		return fmt.Errorf("run as root: sudo volter-client ...")
	}
	
	_ = ipc.InitState()
	
	var ipcClient *ipc.Client
	if opts.ipcSocket != "" {
		client, err := ipc.NewClient(opts.ipcSocket)
		if err != nil {
			clientlog.Warn("Failed to connect to IPC socket: %v", err)
		} else {
			ipcClient = client
			defer ipcClient.Close()
		}
	}
	
	sendStatus := func(connected bool, errMsg string) {
		profile := opts.profileName
		if profile == "" {
			profile = "vpn"
		}
		ipc.SetConnected(connected, profile)
		if ipcClient != nil {
			msg := ipc.Message{
				Type:      ipc.MsgTypeStatus,
				Profile:   profile,
				Connected: connected,
			}
			if errMsg != "" {
				msg.Error = errMsg
			}
			_ = ipcClient.Send(msg)
		}
	}
	
	if opts.proxy {
		sigCtx, stop := signal.NotifyContext(ctx, syscall.SIGINT, syscall.SIGTERM)
		defer stop()
		clientlog.Info("proxy mode: listening on %s", opts.proxyListen)
		if onReady != nil {
			onReady()
		}
		sendStatus(true, "")
		tunnel.SetQUICTrace(opts.quicTraceLog)
		err := proxy.Run(sigCtx, opts.proxyListen, addrs, opts.token, opts.protection, opts.transport, opts.quicServer, opts.quicServerName, opts.quicSkipVerify, opts.quicCertPinSHA256, opts.quicTLSRoots)
		sendStatus(false, err.Error())
		return err
	}
	sigCtx, stop := signal.NotifyContext(ctx, syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	dr, err := netcfg.GetDefaultRoute()
	if err != nil {
		return err
	}
	bypassIPs := []net.IP{opts.serverIP}
	bypassIPs = append(bypassIPs, resolveBypassHosts(relayBypassHosts(opts.relay))...)
	if tunnel.UsesQUICTransport(opts.transport, opts.quicServer) {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		extra, err := tunnel.QUICDialTargetIPs(ctx, addrs, opts.quicServer)
		cancel()
		if err != nil {
			clientlog.Warn("vpn: QUIC bypass resolve: %v", err)
		} else {
			bypassIPs = append(bypassIPs, extra...)
		}
	}
	bypassIPs = dedupeIPs(bypassIPs)
	for _, ip := range bypassIPs {
		if err := netcfg.AddBypass(ip, dr); err != nil {
			return err
		}
	}
	if err := netcfg.AddExcludeRoutes(dr, opts.excludeCIDRs); err != nil {
		return err
	}

	defer func() {
		netcfg.DelExcludeRoutes(opts.excludeCIDRs)
		for _, ip := range bypassIPs {
			netcfg.DelBypass(ip)
		}
	}()

	createDevice := func() (device.Device, func(), error) {
		f, name, err := tun.Create(opts.tunName)
		if err != nil {
			return nil, nil, err
		}
		if err := tun.Configure(name, opts.tunCIDR, opts.mtu); err != nil {
			_ = f.Close()
			return nil, nil, err
		}
		if opts.tunCIDR6 != "" {
			if err := tun.AddAddr(name, opts.tunCIDR6); err != nil {
				_ = f.Close()
				return nil, nil, err
			}
		}
		dev, err := fdbased.Open(strconv.Itoa(int(f.Fd())), uint32(opts.mtu), 0)
		if err != nil {
			_ = f.Close()
			return nil, nil, err
		}
		cleanup := func() {
			netcfg.DelRoutesViaTun(name, opts.routeCIDRs)
			if opts.tunCIDR6 != "" && len(opts.routeCIDRs) == 0 {
				netcfg.DelDefaultViaTun6(name)
			}
			if opts.tunCIDR6 != "" {
				tun.DelAddr(name, opts.tunCIDR6)
			}
			tun.Teardown(name, opts.tunCIDR)
			dev.Close()
			_ = f.Close()
		}
		return dev, cleanup, nil
	}

	ready := make(chan struct{})
	errCh := make(chan error, 1)
	vpnCtx, vpnCancel := context.WithCancel(sigCtx)
	defer vpnCancel()
	go func() {
		vo := vpn.Options{
			CreateDevice:      createDevice,
			Token:             opts.token,
			ServerAddrs:       addrs,
			Transport:         opts.transport,
			QuicServer:        opts.quicServer,
			QuicServerName:    opts.quicServerName,
			QuicSkipVerify:    opts.quicSkipVerify,
			QuicCertPinSHA256: opts.quicCertPinSHA256,
			QuicTLSRoots:      opts.quicTLSRoots,
			QuicTraceLog:      opts.quicTraceLog,
			DualTransport:     opts.dualTransport,
			PathManager:       tunnel.NewPathManagerFromRelay(opts.relay),
			Relay:             opts.relay,
			Ready:             func() { close(ready) },
			Protection:        opts.protection,
		}
		if opts.watchdogFail != nil {
			vo.WatchdogInterval = time.Minute
			vo.WatchdogServerPingTimeout = 2 * time.Second
			vo.OnWatchdogFail = func() {
				if opts.watchdogMark != nil {
					opts.watchdogMark.Store(true)
				}
				if opts.watchdogFail != nil {
					select {
					case opts.watchdogFail <- struct{}{}:
					default:
					}
				}
				vpnCancel()
			}
		}
		errCh <- vpn.Run(vpnCtx, vo)
	}()

	select {
	case <-ready:
		clientlog.OK("Tunnel ready, switching routes to %s", opts.tunName)
		sendStatus(true, "")
		if err := netcfg.AddRoutesViaTun(opts.tunName, opts.routeCIDRs, 5); err != nil {
			sendStatus(false, err.Error())
			return err
		}
		if opts.tunCIDR6 != "" && len(opts.routeCIDRs) == 0 {

			if gw, err := deriveIPv6Gateway(opts.tunCIDR6); err == nil {
				if err := netcfg.AddDefaultViaTun6(opts.tunName, gw, 5); err != nil {
					sendStatus(false, err.Error())
					return err
				}
			}
		}
		if onReady != nil {
			onReady()
		}
	case err := <-errCh:
		sendStatus(false, err.Error())
		return err
	case <-sigCtx.Done():
		<-errCh
		sendStatus(false, "cancelled")
		return nil
	}

	select {
	case <-sigCtx.Done():
		<-errCh
		sendStatus(false, "cancelled")
		return nil
	case err := <-errCh:
		sendStatus(false, err.Error())
		return err
	}
}

func deriveIPv6Gateway(cidr string) (string, error) {
	ip, ipNet, err := net.ParseCIDR(cidr)
	if err != nil {
		return "", err
	}
	ip = ip.Mask(ipNet.Mask)
	b := ip.To16()
	if b == nil {
		return "", fmt.Errorf("not ipv6: %s", cidr)
	}
	b[15] = 1
	return net.IP(b).String(), nil
}
