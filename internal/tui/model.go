package tui

import (
	"bytes"
	"context"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"dev.c0redev.volter/internal/clientlog"
	"dev.c0redev.volter/internal/clusteraddr"
	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/geo"
	"dev.c0redev.volter/internal/ice"
	"dev.c0redev.volter/internal/meshstatus"
	"dev.c0redev.volter/internal/metrics"
	"dev.c0redev.volter/internal/probe"
	"dev.c0redev.volter/internal/protocol"
	"dev.c0redev.volter/internal/update"
	"github.com/charmbracelet/bubbles/list"
	"github.com/charmbracelet/bubbles/textinput"
	"github.com/charmbracelet/bubbles/viewport"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

const (
	success                  = "10"
	errCol                   = "9"
	dim                      = "8"
	pingGreen                = "2"
	pingYellow               = "11"
	pingRed                  = "1"
	probeTimeout             = 5 * time.Second
	tabCount                 = 8
	protectionFormFieldCount = 31
)

type tab int

const (
	tabHome tab = iota
	tabConfig
	tabCloud
	tabLogs
	tabMesh
	tabCluster
	tabProtection
	tabSettings
)

var tabNames = []string{"Главная", "Конфигурации", "Облако", "Логи", "Mesh", "Кластер", "Защита", "Настройки"}

type meshRefreshMsg struct{}
type meshSelfTestMsg struct{ report string }
type ipcCommandMsg struct{ command string }
type autoConnectMsg struct{ profile string }

type status int

const (
	statusDisconnected status = iota
	statusConnecting
	statusConnected
)

type ConnectFn func(cfg config.Config, configName string, reconnectCount int, settings config.ClientSettings) (stop func(), err error)

type Opts struct {
	ConnectFn    ConnectFn
	Version      string
	IPCCommandCh <-chan string
	InitialState State
	AutoConnect  string
	IPCStatusCh  chan<- string
}

type State struct {
	Connected bool
	Profile   string
}

type item struct {
	cfg         config.Config
	name        string
	pingMs      int64
	pterovpn    int
	ipv6Support bool
	serverMode  string
}

func (i item) Title() string {
	if i.ipv6Support {
		return i.name + "  · IPv6"
	}
	return i.name
}
func (i item) Description() string {
	var ping, ptr string
	switch {
	case i.pingMs >= 0:
		s := fmt.Sprintf("%dms", i.pingMs)
		switch {
		case i.pingMs < 100:
			ping = lipgloss.NewStyle().Foreground(lipgloss.Color(pingGreen)).Render("✓ " + s)
		case i.pingMs < 300:
			ping = lipgloss.NewStyle().Foreground(lipgloss.Color(pingYellow)).Render("✓ " + s)
		default:
			ping = lipgloss.NewStyle().Foreground(lipgloss.Color(pingRed)).Render("✓ " + s)
		}
	case i.pingMs == -2:
		ping = lipgloss.NewStyle().Foreground(lipgloss.Color(errCol)).Render("✗")
	default:
		ping = lipgloss.NewStyle().Foreground(lipgloss.Color(dim)).Render("○")
	}
	switch i.pterovpn {
	case 1:
		ptr = lipgloss.NewStyle().Foreground(lipgloss.Color(success)).Render("✓")
	case 0:
		ptr = lipgloss.NewStyle().Foreground(lipgloss.Color(dim)).Render("✗")
	case 2:
		ptr = lipgloss.NewStyle().Foreground(lipgloss.Color(errCol)).Render("⚠")
	default:
		ptr = lipgloss.NewStyle().Foreground(lipgloss.Color(dim)).Render("○")
	}
	ipv6 := ""
	if i.ipv6Support {
		ipv6 = " " + lipgloss.NewStyle().Foreground(lipgloss.Color("6")).Render("IPv6")
	}
	mode := ""
	if i.serverMode != "" {
		mode = " " + lipgloss.NewStyle().Foreground(lipgloss.Color("14")).Render(i.serverMode)
	}
	return fmt.Sprintf("[%s] [%s]%s%s %s", ping, ptr, ipv6, mode, i.cfg.Server)
}
func (i item) FilterValue() string { return i.name + " " + i.cfg.Server }

type Model struct {
	opts          Opts
	tab           tab
	status        status
	activeCfg     string
	err           string
	stop          func()
	cfgList       list.Model
	cfgs          []config.Config
	names         []string
	pingResults   map[string]time.Duration
	pingFailed    map[string]bool
	pterovpnRes   map[string]int
	cfgIPv6       map[string]bool
	cfgMode       map[string]string
	logBuf        *bytes.Buffer
	logs          []string
	logsMu        sync.Mutex
	logViewport   viewport.Model
	logAutoScroll bool
	adding        bool
	addInputs     []textinput.Model
	addFocus      int
	editing       bool
	editingName   string
	editInputs    []textinput.Model
	editFocus     int
	deletingCfg   string

	cloudList     list.Model
	cloudCfgs     []config.Config
	cloudNames    []string
	cloudGeo      map[string]geo.Info
	cloudIPv6     map[string]bool
	cloudMode     map[string]string
	cloudLoading  bool
	cloudFetchErr string

	meshViewport        viewport.Model
	clusterViewport     viewport.Model
	protectionViewport  viewport.Model
	protectionEditing   bool
	protectionFormFocus int
	protectionInputs    []textinput.Model
	protectionTarget    string
	protectionClientIdx int

	meshEditing      bool
	meshFormFocus    int
	meshInputs       []textinput.Model
	meshSelfTest     string
	clusterServerIdx int

	clientSettings    config.ClientSettings
	settingsEditing   bool
	settingsFormFocus int
	settingsInputs    []textinput.Model

	updateAvailable string

	updateAwaitingConfirm bool
	updateBusy            string
	updateErr             string

	connectCount int
}

var (
	titleStyle = lipgloss.NewStyle().
			Bold(true).
			Foreground(lipgloss.Color("14")).
			Padding(0, 1)
	statusStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color(success)).
			Bold(true)
	errStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color(errCol)).
			Bold(true)
	tabStyle = lipgloss.NewStyle().
			Foreground(lipgloss.Color(dim)).
			Padding(0, 1)
	activeTabStyle = lipgloss.NewStyle().
			Padding(0, 1).
			Bold(true).
			Foreground(lipgloss.Color("0")).
			Background(lipgloss.Color("14"))
	contentBox = lipgloss.NewStyle().
			Padding(1, 2).
			Border(lipgloss.RoundedBorder()).
			BorderForeground(lipgloss.Color("14"))
	logLineStyle    = lipgloss.NewStyle().Foreground(lipgloss.Color(dim))
	logErrStyle     = lipgloss.NewStyle().Foreground(lipgloss.Color(errCol))
	logOKStyle      = lipgloss.NewStyle().Foreground(lipgloss.Color("2"))
	logTrafficStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("6"))
	logDropStyle    = lipgloss.NewStyle().Foreground(lipgloss.Color("11"))
	logDPIStyle     = lipgloss.NewStyle().Foreground(lipgloss.Color("13")).Bold(true)
	logWarnStyle    = lipgloss.NewStyle().Foreground(lipgloss.Color("11"))
	footerStyle     = lipgloss.NewStyle().
			Foreground(lipgloss.Color(dim)).
			Padding(0, 1)
	sectionTitle = lipgloss.NewStyle().
			Bold(true).
			Foreground(lipgloss.Color("14")).
			Padding(0, 0, 0, 1).
			BorderLeft(true).
			BorderForeground(lipgloss.Color("14"))
	emptyState = lipgloss.NewStyle().
			Foreground(lipgloss.Color(dim)).
			Italic(true)
	hintKey = lipgloss.NewStyle().
		Bold(true).
		Foreground(lipgloss.Color("14"))
	hintText = lipgloss.NewStyle().
			Foreground(lipgloss.Color(dim))
	kvLabel = lipgloss.NewStyle().
		Foreground(lipgloss.Color("7"))
	kvValue = lipgloss.NewStyle().
		Foreground(lipgloss.Color("15"))
	cloudDetailStyle = lipgloss.NewStyle().
				Foreground(lipgloss.Color(dim)).
				MarginTop(1)
)

func NewModel(opts Opts) *Model {
	m := &Model{
		opts:               opts,
		tab:                tabHome,
		status:             statusDisconnected,
		activeCfg:          "",
		logBuf:             bytes.NewBuffer(nil),
		logs:               []string{},
		pingResults:        make(map[string]time.Duration),
		pingFailed:         make(map[string]bool),
		pterovpnRes:        make(map[string]int),
		cfgIPv6:            make(map[string]bool),
		cfgMode:            make(map[string]string),
		logViewport:        viewport.New(60, 14),
		logAutoScroll:      true,
		meshViewport:       viewport.New(60, 14),
		clusterViewport:    viewport.New(60, 14),
		protectionViewport: viewport.New(60, 14),
	}
	if opts.InitialState.Connected {
		m.status = statusConnected
		m.activeCfg = strings.TrimSpace(opts.InitialState.Profile)
	}
	m.clientSettings, _ = config.LoadClientSettings()
	m.reloadCfgs()
	m.reloadCloud(false)
	m.cloudLoading = true
	m.cloudFetchErr = ""
	m.cloudIPv6 = make(map[string]bool)
	m.cloudMode = make(map[string]string)
	return m
}

func meshTickCmd() tea.Cmd {
	return tea.Tick(2*time.Second, func(time.Time) tea.Msg {
		return meshRefreshMsg{}
	})
}

func (m *Model) batchTabSwitch() tea.Cmd {
	var cmds []tea.Cmd
	if m.tab == tabMesh {
		m.setMeshViewportContent()
		cmds = append(cmds, meshTickCmd())
	}
	if m.tab == tabCluster {
		m.setClusterViewportContent()
		cmds = append(cmds, meshTickCmd())
	}
	if m.tab == tabConfig && len(m.cfgs) > 0 {
		cmds = append(cmds, autoProbeCmds(m.cfgs, m.names))
	}
	if m.tab == tabCloud && len(m.cloudCfgs) > 0 {
		cmds = append(cmds, autoProbeCmds(m.cloudCfgs, m.cloudNames))
	}
	if len(cmds) == 0 {
		return nil
	}
	return tea.Batch(cmds...)
}

func (m *Model) meshRelayEffectiveTarget() string {
	if m.protectionTarget != "" {
		return m.protectionTarget
	}
	if len(m.names) > 0 {
		return m.names[0]
	}
	return ""
}

func (m *Model) setMeshViewportContent() {
	m.meshViewport.SetContent(m.meshFullContent())
}

func (m *Model) setClusterViewportContent() {
	m.clusterViewport.SetContent(m.clusterFullContent())
}

func (m *Model) meshFullContent() string {
	var b strings.Builder
	b.WriteString(sectionTitle.Render("Relay / mesh") + "\n")
	target := "—"
	if m.protectionTarget != "" {
		target = m.protectionTarget
	} else if len(m.names) > 0 {
		target = "«" + m.names[0] + "» (цель не выбрана — как глобальная)"
	}
	b.WriteString("  Цель профиля: " + target)
	if len(m.names) > 0 {
		b.WriteString("   ")
		b.WriteString(hintKey.Render("Ctrl+←/→"))
		b.WriteString(hintText.Render(" цель (та же, что «Защита»)"))
	}
	b.WriteString("\n")
	if len(m.names) == 0 {
		b.WriteString(emptyState.Render("Нет локальных конфигов — добавь профиль во вкладке «Конфигурации».") + "\n")
	}

	if m.meshEditing && len(m.meshInputs) == meshRelayInputCount {
		b.WriteString("\n")
		for i := range m.meshInputs {
			b.WriteString("  ")
			b.WriteString(kvLabel.Render(meshRelayLabels[i]+":") + " ")
			b.WriteString(m.meshInputs[i].View())
			b.WriteString("\n")
		}
		b.WriteString("\n  ")
		b.WriteString(hintKey.Render("Tab") + hintText.Render(" поле  ") + hintKey.Render("Enter") + hintText.Render(" сохранить  ") + hintKey.Render("Esc") + hintText.Render(" отмена\n"))
	} else {
		name := m.meshRelayEffectiveTarget()
		var r *config.RelayOptions
		if name != "" {
			if cfg, err := config.LoadByName(name); err == nil {
				r = config.EffectiveRelayOptions(&cfg)
			}
		}
		b.WriteString(relaySummaryShort(r))
		b.WriteString("  ")
		b.WriteString(hintKey.Render("E") + hintText.Render(" форма relay/mesh (STUN/TURN/DHT…)\n"))
	}

	b.WriteString("\n")
	b.WriteString(sectionTitle.Render("Статус Mesh / DHT / ICE") + "\n\n")
	if m.status != statusConnected {
		b.WriteString(emptyState.Render("Не подключено — таблица узлов и srflx после VPN-сессии с relay.") + "\n\n")
	}
	b.WriteString(meshstatus.Format(meshstatus.Gather()))
	if strings.TrimSpace(m.meshSelfTest) != "" {
		b.WriteString("\n\n")
		b.WriteString(sectionTitle.Render("Self-test mesh") + "\n")
		b.WriteString("  " + m.meshSelfTest + "\n")
	}
	return b.String()
}

func (m *Model) clusterFullContent() string {
	s := meshstatus.Gather()
	var b strings.Builder
	b.WriteString(sectionTitle.Render("Кластер серверов") + "\n\n")
	if s.ClusterNodeID != "" {
		b.WriteString("Текущий узел: " + s.ClusterNodeID + "\n")
	} else {
		b.WriteString("Текущий узел: —\n")
	}
	if len(s.ClusterNodes) == 0 {
		b.WriteString(emptyState.Render("Кластерные серверы пока не найдены") + "\n")
	} else {
		b.WriteString("Серверы:\n")
		for _, n := range s.ClusterNodes {
			b.WriteString("  • " + n + "\n")
		}
	}
	if s.ClusterSessionsCount >= 0 {
		b.WriteString(fmt.Sprintf("\nResume-сессии: %d", s.ClusterSessionsCount))
		if s.ClusterSessionsNodeID != "" {
			b.WriteString(" (узел " + s.ClusterSessionsNodeID + ")")
		}
		b.WriteString("\n")
	}
	if s.ClusterMapAgeMs > 0 || s.ClusterSessionsAgeMs > 0 || s.ClusterClientsAgeMs > 0 {
		b.WriteString(fmt.Sprintf("Sync age ms (map/sessions/clients): %d/%d/%d\n", s.ClusterMapAgeMs, s.ClusterSessionsAgeMs, s.ClusterClientsAgeMs))
	}
	b.WriteString(fmt.Sprintf("Store-forward sent/recv: %d/%d\n", s.StoreForwardSent, s.StoreForwardRecv))
	if s.ClientsSource != "" {
		b.WriteString("Clients source: " + s.ClientsSource + "\n")
	}
	b.WriteString("Route mode hotkeys: 1=auto 2=direct 3=peer_relay 4=server_relay\n")
	b.WriteString("Preferred server: S (cycle)\n")
	b.WriteString("\n")
	b.WriteString(sectionTitle.Render("Mesh клиенты (DHT nearest)") + "\n")
	if s.ClusterClientsCount > 0 && len(s.ClusterClients) > 0 {
		show := s.ClusterClients
		if len(show) > 64 {
			show = show[:64]
		}
		for _, n := range show {
			b.WriteString("  • " + n + "\n")
		}
	} else {
		if len(s.Nodes) == 0 {
			b.WriteString(emptyState.Render("Клиенты пока не собраны") + "\n")
		} else {
			show := s.Nodes
			if len(show) > 48 {
				show = show[:48]
			}
			for _, n := range show {
				line := "  • " + n.ID
				if n.Class != "" {
					line += " [" + n.Class + "]"
				}
				if n.Endpoints != "" {
					line += " ep=" + n.Endpoints
				}
				b.WriteString(line + "\n")
			}
		}
	}
	b.WriteString(fmt.Sprintf("\nОбновлено: %s\n", s.CollectedAt.Format(time.RFC3339)))
	return b.String()
}

func (m *Model) setClusterRouteMode(mode string) {
	name := m.meshRelayEffectiveTarget()
	if name == "" {
		return
	}
	cfg, err := config.LoadByName(name)
	if err != nil {
		return
	}
	opts := config.ProtectionOptions{}
	if cfg.Protection != nil {
		opts = *cfg.Protection
	}
	opts.RouteMode = strings.TrimSpace(mode)
	cfg.Protection = &opts
	_ = config.Save(name, cfg)
}

func (m *Model) cycleClusterPreferredServer() {
	name := m.meshRelayEffectiveTarget()
	if name == "" {
		return
	}
	s := meshstatus.Gather()
	if len(s.ClusterNodes) == 0 {
		return
	}
	m.clusterServerIdx = (m.clusterServerIdx + 1) % len(s.ClusterNodes)
	raw := strings.TrimSpace(s.ClusterNodes[m.clusterServerIdx])
	server := raw
	if strings.Contains(raw, "(") && strings.Contains(raw, ")") {
		server = strings.TrimSpace(raw[strings.Index(raw, "(")+1 : strings.LastIndex(raw, ")")])
	}
	if server == "" {
		return
	}
	cfg, err := config.LoadByName(name)
	if err != nil {
		return
	}
	opts := config.ProtectionOptions{}
	if cfg.Protection != nil {
		opts = *cfg.Protection
	}
	opts.ClusterPreferredServer = clusteraddr.CanonicalHostPort(server)
	opts.RouteMode = "server_relay"
	cfg.Protection = &opts
	_ = config.Save(name, cfg)
}

func (m *Model) configSnapshotForActive() (config.Config, bool) {
	name := m.activeCfg
	if name == "" {
		return config.Config{}, false
	}
	if saved, err := config.LoadByName(name); err == nil {
		return saved, true
	}
	for i, n := range m.names {
		if n == name {
			return m.cfgs[i], true
		}
	}
	for i, n := range m.cloudNames {
		if n != name {
			continue
		}
		cfg := m.cloudCfgs[i]
		if saved, err := config.LoadByName(n); err == nil && saved.Server == cfg.Server && saved.Token == cfg.Token {
			cfg = saved
		}
		mode := ""
		if m.cloudMode != nil {
			mode = m.cloudMode[n]
		}
		probeV6 := m.cloudIPv6 != nil && m.cloudIPv6[n]
		config.ApplyCloudConnectDefaults(&cfg, mode, probeV6)
		return cfg, true
	}
	return config.Config{}, false
}

func (m *Model) buildItems() []list.Item {
	items := make([]list.Item, len(m.cfgs))
	for i := range m.cfgs {
		pingMs := int64(-1)
		if m.pingFailed[m.names[i]] {
			pingMs = -2
		} else if d, ok := m.pingResults[m.names[i]]; ok {
			pingMs = d.Milliseconds()
		}
		pterovpn := -1
		if v, exists := m.pterovpnRes[m.names[i]]; exists {
			pterovpn = v
		}
		ipv6 := m.cfgIPv6 != nil && m.cfgIPv6[m.names[i]]
		mode := ""
		if m.cfgMode != nil {
			mode = m.cfgMode[m.names[i]]
		}
		items[i] = item{cfg: m.cfgs[i], name: m.names[i], pingMs: pingMs, pterovpn: pterovpn, ipv6Support: ipv6, serverMode: mode}
	}
	return items
}

func (m *Model) reloadCfgs() {
	cfgs, names, _ := config.List()
	m.cfgs = cfgs
	m.names = names
	items := m.buildItems()
	l := list.New(items, list.NewDefaultDelegate(), 40, 14)
	l.Title = "Конфигурации"
	l.SetShowStatusBar(false)
	m.cfgList = l
}

func (m *Model) refreshCfgItems() {
	idx := m.cfgList.Index()
	if idx < 0 {
		idx = 0
	}
	m.cfgList.SetItems(m.buildItems())
	m.cfgList.Select(idx)
}

func cloudHost(server string) string {
	host, _, err := net.SplitHostPort(server)
	if err != nil {
		return server
	}
	return host
}

func formatGeoName(g geo.Info, fallback string) string {
	if g.Org != "" {
		if g.CountryCode != "" {
			return g.Org + " (" + g.CountryCode + ")"
		}
		return g.Org
	}
	if g.CountryCode != "" || g.ASN != "" {
		return strings.TrimSpace(g.CountryCode + " " + g.ASN)
	}
	return fallback
}

func (m *Model) buildCloudItems() []list.Item {
	items := make([]list.Item, len(m.cloudCfgs))
	for i := range m.cloudCfgs {
		pingMs := int64(-1)
		if m.pingFailed[m.cloudNames[i]] {
			pingMs = -2
		} else if d, ok := m.pingResults[m.cloudNames[i]]; ok {
			pingMs = d.Milliseconds()
		}
		pterovpn := -1
		if v, exists := m.pterovpnRes[m.cloudNames[i]]; exists {
			pterovpn = v
		}
		name := m.cloudNames[i]
		if m.cloudGeo != nil {
			if g, ok := m.cloudGeo[cloudHost(m.cloudCfgs[i].Server)]; ok {
				name = formatGeoName(g, m.cloudNames[i])
			}
		}
		ipv6Support := m.cloudIPv6 != nil && m.cloudIPv6[m.cloudNames[i]]
		mode := ""
		if m.cloudMode != nil {
			mode = m.cloudMode[m.cloudNames[i]]
		}
		items[i] = item{cfg: m.cloudCfgs[i], name: name, pingMs: pingMs, pterovpn: pterovpn, ipv6Support: ipv6Support, serverMode: mode}
	}
	return items
}

func (m *Model) reloadCloud(fetch bool) tea.Cmd {
	if fetch {
		m.cloudLoading = true
		m.cloudFetchErr = ""
		return runFetchCloud()
	}
	cfgs, names, _ := config.CloudList(false)
	m.cloudCfgs = cfgs
	m.cloudNames = names
	if m.cloudGeo == nil {
		m.cloudGeo = make(map[string]geo.Info)
	}
	items := m.buildCloudItems()
	l := list.New(items, list.NewDefaultDelegate(), 40, 14)
	l.Title = "Облачные конфиги"
	l.SetShowStatusBar(false)
	m.cloudList = l
	return runGeoFetches(m.cloudCfgs)
}

func (m *Model) refreshCloudItems() {
	if len(m.cloudCfgs) == 0 {
		return
	}
	idx := m.cloudList.Index()
	if idx < 0 {
		idx = 0
	}
	m.cloudList.SetItems(m.buildCloudItems())
	m.cloudList.Select(idx)
}

func newAddInputs() []textinput.Model {
	return newInputsWithValues("", "", "", "", "", "", "", "", "", "", "")
}

func newProtectionInputs(opts config.ProtectionOptions) []textinput.Model {
	ti := func(pl, val string) textinput.Model {
		t := textinput.New()
		t.Placeholder = pl
		t.SetValue(val)
		return t
	}
	obf := opts.Obfuscation
	if obf == "" {
		obf = "default"
	}
	magicSplit := opts.MagicSplit
	if magicSplit == "" {
		magicSplit = "0"
	}
	junkStyle := opts.JunkStyle
	if junkStyle == "" {
		junkStyle = "random"
	}
	flushPolicy := opts.FlushPolicy
	if flushPolicy == "" {
		flushPolicy = "once"
	}
	pp := opts.PreambleProfile
	stand := "false"
	if opts.StandaloneDpiOnly {
		stand = "true"
	}
	eng := strings.TrimSpace(opts.DpiLocalEngine)
	if strings.EqualFold(eng, "external") {
		eng = "external"
	} else {
		eng = "embedded"
	}
	mergedEmb := config.MergeDpiLocalEmbeddedDefaults(opts.DpiLocalEmbedded)
	return []textinput.Model{
		ti("default|enhanced", obf),
		ti("0-12", strconv.Itoa(opts.JunkCount)),
		ti("64-1024", strconv.Itoa(opts.JunkMin)),
		ti("64-1024", strconv.Itoa(opts.JunkMax)),
		ti("0-64", strconv.Itoa(opts.PadS1)),
		ti("0-64", strconv.Itoa(opts.PadS2)),
		ti("0-64", strconv.Itoa(opts.PadS3)),
		ti("0-64", strconv.Itoa(opts.PadS4)),
		ti("true|false", strconv.FormatBool(opts.PreCheck)),
		ti("2,3 or 0", magicSplit),
		ti("random|tls", junkStyle),
		ti("once|perChunk", flushPolicy),
		ti("none|rotate|tls_record|tls_ch_shape|smb1_shape|mc_frame", pp),
		ti("preambleRotate true|false", strconv.FormatBool(opts.PreambleRotate)),
		ti("standaloneDpi true|false", stand),
		ti("embedded|external", eng),
		ti("splitAfter", strconv.Itoa(mergedEmb.SplitAfter)),
		ti("ttlMillis", strconv.Itoa(mergedEmb.TTLMillis)),
		ti("disorder true|false", strconv.FormatBool(mergedEmb.Disorder)),
		ti("splitAfter2 0=off", strconv.Itoa(mergedEmb.SplitAfter2)),
		ti("ttl2Millis 0=same", strconv.Itoa(mergedEmb.TTL2Millis)),
		ti("jitterMaxMs", strconv.Itoa(mergedEmb.JitterMaxMs)),
		ti("leadInMs", strconv.Itoa(mergedEmb.LeadInMs)),
		ti("dpiLocalPreset", strings.TrimSpace(opts.DpiLocalPreset)),
		ti("fakeSni true|false", strconv.FormatBool(mergedEmb.FakeSNI)),
		ti("fakeSniHost", mergedEmb.FakeSNIHost),
		ti("splitPosition sni|method|host|random", mergedEmb.SplitPosition),
		ti("autoTtl true|false", strconv.FormatBool(mergedEmb.AutoTTL)),
		ti("tcpSegment 0=off", strconv.Itoa(mergedEmb.TCPSegment)),
		ti("oobData true|false", strconv.FormatBool(mergedEmb.OOBData)),
		ti("multiSplit 0-10", strconv.Itoa(mergedEmb.MultiSplit)),
	}
}

func newSettingsInputs(s config.ClientSettings) []textinput.Model {
	ti := func(pl, val string) textinput.Model {
		t := textinput.New()
		t.Placeholder = pl
		t.SetValue(val)
		return t
	}
	mode := s.Mode
	if mode == "" {
		mode = "tun"
	}
	sysProxy := "false"
	if s.SystemProxy {
		sysProxy = "true"
	}
	return []textinput.Model{
		ti("tun|proxy", mode),
		ti("127.0.0.1:1080", s.ProxyListen),
		ti("true|false", sysProxy),
	}
}

func settingsFromInputs(inputs []textinput.Model) config.ClientSettings {
	mode := strings.ToLower(strings.TrimSpace(inputs[0].Value()))
	if mode != "proxy" {
		mode = "tun"
	}
	listen := strings.TrimSpace(inputs[1].Value())
	if listen == "" {
		listen = "127.0.0.1:1080"
	}
	sysProxy := strings.ToLower(strings.TrimSpace(inputs[2].Value())) == "true"
	return config.ClientSettings{Mode: mode, ProxyListen: listen, SystemProxy: sysProxy}
}

func protectionOptsFromInputs(inputs []textinput.Model) config.ProtectionOptions {
	clamp := func(v, lo, hi int) int {
		if v < lo {
			return lo
		}
		if v > hi {
			return hi
		}
		return v
	}
	atoi := func(s string) int {
		v, _ := strconv.Atoi(strings.TrimSpace(s))
		return v
	}
	obf := strings.TrimSpace(inputs[0].Value())
	if obf != "enhanced" && obf != "default" {
		obf = "default"
	}
	magicSplit, junkStyle, flushPolicy := "", "random", "once"
	preambleProfile := ""
	preambleRotate := false
	if len(inputs) >= 12 {
		magicSplit = strings.TrimSpace(inputs[9].Value())
		if magicSplit == "0" {
			magicSplit = ""
		}
		junkStyle = strings.ToLower(strings.TrimSpace(inputs[10].Value()))
		if junkStyle != "tls" {
			junkStyle = "random"
		}
		flushPolicy = strings.ToLower(strings.TrimSpace(inputs[11].Value()))
	}
	if len(inputs) >= 14 {
		preambleProfile = strings.TrimSpace(strings.ToLower(inputs[12].Value()))
		switch preambleProfile {
		case "", "none", "rotate", "tls_record", "tls_ch_shape", "smb1_shape", "mc_frame":
			if preambleProfile == "none" {
				preambleProfile = ""
			}
		default:
			preambleProfile = ""
		}
		preambleRotate = strings.ToLower(strings.TrimSpace(inputs[13].Value())) == "true"
	}
	if flushPolicy == "perchunk" {
		flushPolicy = "perChunk"
	} else {
		flushPolicy = "once"
	}
	standalone := false
	dpiEngineStr := ""
	splitA, ttl := 1, 8
	disorder := false
	splitA2, ttl2, jitter, leadIn := 0, 0, 0, 0
	fakeSni := false
	fakeSniHost := ""
	splitPos := ""
	autoTtl := false
	tcpSeg := 0
	oobData := false
	multiSplit := 0
	presetLine := ""
	if len(inputs) >= protectionFormFieldCount {
		standalone = strings.ToLower(strings.TrimSpace(inputs[14].Value())) == "true"
		if strings.ToLower(strings.TrimSpace(inputs[15].Value())) == "external" {
			dpiEngineStr = "external"
		} else {
			dpiEngineStr = "embedded"
		}
		splitA = clamp(atoi(inputs[16].Value()), 1, 65536)
		ttl = clamp(atoi(inputs[17].Value()), 1, 60000)
		disorder = strings.ToLower(strings.TrimSpace(inputs[18].Value())) == "true"
		splitA2 = clamp(atoi(inputs[19].Value()), 0, 65536)
		ttl2 = clamp(atoi(inputs[20].Value()), 0, 60000)
		jitter = clamp(atoi(inputs[21].Value()), 0, 5000)
		leadIn = clamp(atoi(inputs[22].Value()), 0, 60000)
		if len(inputs) > 23 {
			presetLine = strings.TrimSpace(inputs[23].Value())
		}
		if len(inputs) > 24 {
			fakeSni = strings.ToLower(strings.TrimSpace(inputs[24].Value())) == "true"
		}
		if len(inputs) > 25 {
			fakeSniHost = strings.TrimSpace(inputs[25].Value())
		}
		if len(inputs) > 26 {
			splitPos = strings.TrimSpace(inputs[26].Value())
		}
		if len(inputs) > 27 {
			autoTtl = strings.ToLower(strings.TrimSpace(inputs[27].Value())) == "true"
		}
		if len(inputs) > 28 {
			tcpSeg = clamp(atoi(inputs[28].Value()), 0, 65536)
		}
		if len(inputs) > 29 {
			oobData = strings.ToLower(strings.TrimSpace(inputs[29].Value())) == "true"
		}
		if len(inputs) > 30 {
			multiSplit = clamp(atoi(inputs[30].Value()), 0, 10)
		}
	}
	defEmb := config.DpiLocalEmbedded{
		SplitAfter: 1, TTLMillis: 8, Disorder: false,
		SplitAfter2: 0, TTL2Millis: 0, JitterMaxMs: 0, LeadInMs: 0,
		FakeSNI: false, FakeSNIHost: "", SplitPosition: "",
		AutoTTL: false, TCPSegment: 0, OOBData: false, MultiSplit: 0,
	}
	curEmb := config.DpiLocalEmbedded{
		SplitAfter: splitA, TTLMillis: ttl, Disorder: disorder,
		SplitAfter2: splitA2, TTL2Millis: ttl2, JitterMaxMs: jitter, LeadInMs: leadIn,
		FakeSNI: fakeSni, FakeSNIHost: fakeSniHost, SplitPosition: splitPos,
		AutoTTL: autoTtl, TCPSegment: tcpSeg, OOBData: oobData, MultiSplit: multiSplit,
	}
	var embPtr *config.DpiLocalEmbedded
	if curEmb != defEmb {
		embPtr = &curEmb
	}
	return config.ProtectionOptions{
		Obfuscation:       obf,
		JunkCount:         clamp(atoi(inputs[1].Value()), 0, 12),
		JunkMin:           clamp(atoi(inputs[2].Value()), 64, 1024),
		JunkMax:           clamp(atoi(inputs[3].Value()), 64, 1024),
		PadS1:             clamp(atoi(inputs[4].Value()), 0, 64),
		PadS2:             clamp(atoi(inputs[5].Value()), 0, 64),
		PadS3:             clamp(atoi(inputs[6].Value()), 0, 64),
		PadS4:             clamp(atoi(inputs[7].Value()), 0, 64),
		PreCheck:          strings.ToLower(strings.TrimSpace(inputs[8].Value())) == "true",
		MagicSplit:        magicSplit,
		JunkStyle:         junkStyle,
		FlushPolicy:       flushPolicy,
		PreambleProfile:   preambleProfile,
		PreambleRotate:    preambleRotate,
		StandaloneDpiOnly: standalone,
		DpiLocalEngine:    dpiEngineStr,
		DpiLocalEmbedded:  embPtr,
		DpiLocalPreset:    presetLine,
	}
}

func applyProtectionPresetToInputs(inputs []textinput.Model, preset config.ProtectionOptions) []textinput.Model {
	if len(inputs) < protectionFormFieldCount {
		return inputs
	}
	set := func(i int, v string) {
		inputs[i].SetValue(v)
	}
	set(0, orEmpty(preset.Obfuscation, "default"))
	set(1, strconv.Itoa(preset.JunkCount))
	set(2, strconv.Itoa(preset.JunkMin))
	set(3, strconv.Itoa(preset.JunkMax))
	set(4, strconv.Itoa(preset.PadS1))
	set(5, strconv.Itoa(preset.PadS2))
	set(6, strconv.Itoa(preset.PadS3))
	set(7, strconv.Itoa(preset.PadS4))
	set(8, strconv.FormatBool(preset.PreCheck))
	set(9, orEmpty(preset.MagicSplit, "0"))
	set(10, orEmpty(preset.JunkStyle, "random"))
	set(11, orEmpty(preset.FlushPolicy, "once"))
	set(12, preset.PreambleProfile)
	set(13, strconv.FormatBool(preset.PreambleRotate))
	return inputs
}

func protectionPresetBalanced() config.ProtectionOptions {
	return config.ProtectionOptions{
		Obfuscation: "default",
		JunkCount:   4,
		JunkMin:     64,
		JunkMax:     512,
		PadS1:       4,
		PadS2:       4,
		PadS3:       4,
		PadS4:       24,
		JunkStyle:   "random",
		FlushPolicy: "once",
	}
}

func protectionPresetStrict() config.ProtectionOptions {
	return config.ProtectionOptions{
		Obfuscation:     "enhanced",
		JunkCount:       8,
		JunkMin:         128,
		JunkMax:         896,
		PadS1:           12,
		PadS2:           12,
		PadS3:           12,
		PadS4:           48,
		JunkStyle:       "tls",
		FlushPolicy:     "once",
		PreambleProfile: "rotate",
		PreambleRotate:  true,
	}
}

func protectionPresetAutoByMetrics() config.ProtectionOptions {
	store, err := metrics.Load()
	if err != nil || store == nil || len(store.Records) < 2 {
		return protectionPresetBalanced()
	}
	tail := store.Records
	if len(tail) > 10 {
		tail = tail[len(tail)-10:]
	}
	bad := 0
	for _, r := range tail {
		switch strings.ToLower(strings.TrimSpace(r.ErrorType)) {
		case "timeout", "reset", "unknown":
			bad++
		}
	}
	if bad >= 3 {
		return protectionPresetStrict()
	}
	return protectionPresetBalanced()
}

func fillConnectionFromAnyField(inputs []textinput.Model) []textinput.Model {
	if len(inputs) < 2 {
		return inputs
	}
	connIdx := 1
	for i, in := range inputs {
		v := strings.TrimSpace(in.Value())
		if _, _, ok := config.ParseConnection(v); !ok || v == "" {
			continue
		}
		if i == connIdx {
			return inputs
		}
		inputs[connIdx].SetValue(v)
		inputs[i].SetValue("")
		break
	}
	return inputs
}

func boolStr(b bool) string {
	if b {
		return "true"
	}
	return "false"
}

func newInputsWithValues(name, connection, routes, exclude, tunCIDR6, transport, quicServer, quicServerName, quicSkipVerify, quicCertPin, quicCaCert string) []textinput.Model {
	ti := func(pl, val string) textinput.Model {
		t := textinput.New()
		t.Placeholder = pl
		t.SetValue(val)
		return t
	}
	tr := strings.TrimSpace(transport)
	if tr == "" {
		tr = "auto"
	}
	skip := strings.TrimSpace(quicSkipVerify)
	return []textinput.Model{
		ti("имя", name),
		ti("volter://... (или host:port:key)", connection),
		ti("routes (пусто=all)", routes),
		ti("exclude", exclude),
		ti("tun-cidr6 (fd00:.../64)", tunCIDR6),
		ti("transport tcp|quic|auto пусто=QUIC если quicServer", tr),
		ti("quic host:port (пусто = только TCP)", quicServer),
		ti("quic SNI (пусто = из адреса)", quicServerName),
		ti("skipVerify пусто|true=self-signed ok, false=CA only", skip),
		ti("quic pin sha256 hex (пусто = без pin)", quicCertPin),
		ti("quicCaCert PEM файл путь от ~/.config/volter (пусто)", quicCaCert),
	}
}

var cfgFormLabels = []string{
	"Имя:",
	"Connection (volter://...):",
	"Routes:",
	"Exclude:",
	"TUN IPv6 CIDR:",
	"Transport (tcp|quic|auto|пусто+quic):",
	"QUIC server (host:port):",
	"QUIC SNI:",
	"QUIC skipVerify:",
	"QUIC cert pin:",
	"QUIC CA PEM path:",
}

func configFromConnFormInputs(inputs []textinput.Model) (config.Config, string) {
	if len(inputs) < 11 {
		return config.Config{}, "форма конфига сломана"
	}
	server, token, ok := config.ParseConnection(strings.TrimSpace(inputs[1].Value()))
	if !ok {
		return config.Config{}, "connection: volter://... или host:port:key"
	}
	tr := strings.ToLower(strings.TrimSpace(inputs[5].Value()))
	if tr == "auto" {
		tr = ""
	} else if tr != "" && tr != "tcp" && tr != "quic" {
		return config.Config{}, "transport: tcp, quic, auto или пусто"
	}
	pin := strings.TrimSpace(strings.ReplaceAll(inputs[9].Value(), ":", ""))
	var skipPtr *bool
	switch strings.ToLower(strings.TrimSpace(inputs[8].Value())) {
	case "":
		skipPtr = nil
	case "true":
		v := true
		skipPtr = &v
	case "false":
		v := false
		skipPtr = &v
	default:
		return config.Config{}, "quic skipVerify: пусто, true или false"
	}
	out := config.Config{
		Server:            server,
		Token:             token,
		Routes:            strings.TrimSpace(inputs[2].Value()),
		Exclude:           strings.TrimSpace(inputs[3].Value()),
		TunCIDR6:          strings.TrimSpace(inputs[4].Value()),
		Transport:         tr,
		QuicServer:        strings.TrimSpace(inputs[6].Value()),
		QuicServerName:    strings.TrimSpace(inputs[7].Value()),
		QuicCertPinSHA256: pin,
		QuicCaCert:        strings.TrimSpace(inputs[10].Value()),
	}
	out.QuicSkipVerify = skipPtr
	return out, ""
}

type connectedMsg struct{ stop func() }
type disconnectedMsg struct{}
type errMsg string
type logMsg string

type WatchdogReconnectMsg struct{}

type pingResultMsg struct {
	name   string
	d      time.Duration
	failed bool
}
type pterovpnResultMsg struct {
	name string
	ok   bool
	err  bool
	ipv6 bool
	mode string
}

type cloudFetchedMsg struct {
	cfgs  []config.Config
	names []string
	err   string
}

type geoFetchedMsg struct {
	host string
	info geo.Info
}

type updateCheckMsg struct {
	latest string
}

type updateApplyMsg struct {
	err error
}

func LogMessage(s string) tea.Msg { return logMsg(s) }

func runApplyUpdate(tag string) tea.Cmd {
	return func() tea.Msg {
		url, err := update.AssetDownloadURLForTag(tag)
		if err != nil {
			return updateApplyMsg{err: err}
		}
		exe, err := os.Executable()
		if err != nil {
			return updateApplyMsg{err: err}
		}
		if err := update.Apply(exe, url); err != nil {
			return updateApplyMsg{err: err}
		}
		return updateApplyMsg{err: nil}
	}
}

func runCheckUpdate(currentVersion string) tea.Cmd {
	return func() tea.Msg {
		latest, err := update.CheckLatest(currentVersion)
		if err != nil || latest == "" {
			return nil
		}
		return updateCheckMsg{latest: latest}
	}
}

func runFetchCloud() tea.Cmd {
	return func() tea.Msg {
		cfgs, names, err := config.CloudList(true)
		if err != nil {
			return cloudFetchedMsg{err: err.Error()}
		}
		return cloudFetchedMsg{cfgs: cfgs, names: names}
	}
}

func runGeoFetch(host string) tea.Cmd {
	return func() tea.Msg {
		info, err := geo.Fetch(host)
		if err != nil {
			return nil
		}
		return geoFetchedMsg{host: host, info: info}
	}
}

func runGeoFetches(cfgs []config.Config) tea.Cmd {
	seen := make(map[string]bool)
	var cmds []tea.Cmd
	for _, c := range cfgs {
		host := cloudHost(c.Server)
		if host != "" && !seen[host] {
			seen[host] = true
			cmds = append(cmds, runGeoFetch(host))
		}
	}
	return tea.Batch(cmds...)
}

func runPing(addr, name string) tea.Cmd {
	return func() tea.Msg {
		d, err := probe.Ping(addr, probeTimeout)
		if err != nil {
			return pingResultMsg{name: name, failed: true}
		}
		return pingResultMsg{name: name, d: d}
	}
}

func runProbeVolter(addr, wireToken, name string) tea.Cmd {
	return func() tea.Msg {
		ok, ipv6, caps, err := probe.ProbeVolterWithCaps(addr, wireToken, probeTimeout)
		if err != nil {
			return pterovpnResultMsg{name: name, ok: false, err: true, ipv6: false, mode: ""}
		}
		return pterovpnResultMsg{name: name, ok: ok, err: false, ipv6: ipv6, mode: probe.ServerModeFromCaps(caps)}
	}
}

func runPingAll(cfgs []config.Config, names []string) tea.Cmd {
	if len(cfgs) == 0 {
		return nil
	}
	cmds := make([]tea.Cmd, len(cfgs))
	for i := range cfgs {
		cmds[i] = runPing(cfgs[i].Server, names[i])
	}
	return tea.Batch(cmds...)
}

func runProbeAll(cfgs []config.Config, names []string) tea.Cmd {
	if len(cfgs) == 0 {
		return nil
	}
	cmds := make([]tea.Cmd, len(cfgs))
	for i := range cfgs {
		cmds[i] = runProbeVolter(cfgs[i].Server, cfgs[i].Token, names[i])
	}
	return tea.Batch(cmds...)
}

func autoProbeCmds(cfgs []config.Config, names []string) tea.Cmd {
	return tea.Batch(runPingAll(cfgs, names), runProbeAll(cfgs, names))
}

func runMeshSelfTest(cfg config.Config, profileName string) tea.Cmd {
	return func() tea.Msg {
		var lines []string
		lines = append(lines, "profile="+profileName)
		ok, _, caps, err := probe.ProbeVolterWithCaps(cfg.Server, cfg.Token, 8*time.Second)
		lines = append(lines, fmt.Sprintf("serverReachable=%v", ok))
		if caps != nil {
			mode := probe.ServerModeFromCaps(caps)
			serverRelay := (caps.FeatureBits & protocol.FeatureRelayServer) != 0
			lines = append(lines, fmt.Sprintf("serverMode=%s", mode))
			lines = append(lines, fmt.Sprintf("serverRelay=%v", serverRelay))
		}
		if err != nil {
			lines = append(lines, "probeErr="+err.Error())
		}
		r := config.EffectiveRelayOptions(&cfg)
		if r == nil {
			lines = append(lines, "mesh=missing")
			return meshSelfTestMsg{report: strings.Join(lines, " | ")}
		}
		peerReady :=
			strings.TrimSpace(r.PeerID) != "" &&
				len(r.DhtRpcSeedPeers) > 0 &&
				r.PeerPathFromDiscovery &&
				r.PeerRelayUseUDP &&
				strings.TrimSpace(r.PeerRelayUDPListen) != ""
		lines = append(lines, fmt.Sprintf("peerRelayReady=%v", peerReady))
		if len(r.StunServers) > 0 {
			ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
			defer cancel()
			srflx, stunErr := ice.GatherSrflx(ctx, r.StunServers)
			if stunErr != nil {
				lines = append(lines, "stunErr="+stunErr.Error())
			} else {
				lines = append(lines, fmt.Sprintf("stunOk=true srflx=%s:%d", srflx.IP.String(), srflx.Port))
			}
		} else {
			lines = append(lines, "stun=empty")
		}
		return meshSelfTestMsg{report: strings.Join(lines, " | ")}
	}
}

func (m *Model) Init() tea.Cmd {
	cmds := []tea.Cmd{autoProbeCmds(m.cfgs, m.names), runCheckUpdate(m.opts.Version), runFetchCloud()}
	if len(m.cloudCfgs) > 0 {
		cmds = append(cmds, runGeoFetches(m.cloudCfgs))
	}
	if m.opts.IPCCommandCh != nil {
		cmds = append(cmds, m.listenIPCCommands())
	}
	if m.opts.AutoConnect != "" {
		cmds = append(cmds, func() tea.Msg {
			return autoConnectMsg{profile: m.opts.AutoConnect}
		})
	}
	return tea.Batch(cmds...)
}

func (m *Model) listenIPCCommands() tea.Cmd {
	return func() tea.Msg {
		if m.opts.IPCCommandCh == nil {
			return nil
		}
		for cmd := range m.opts.IPCCommandCh {
			return ipcCommandMsg{command: cmd}
		}
		return nil
	}
}

func (m *Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	var cmd tea.Cmd

	switch msg := msg.(type) {
	case tea.KeyMsg:
		switch msg.String() {
		case "q", "esc":
			if m.updateAwaitingConfirm {
				m.updateAwaitingConfirm = false
				return m, nil
			}
			if m.settingsEditing {
				m.settingsEditing = false
				m.settingsFormFocus = 0
				return m, nil
			}
			if m.deletingCfg != "" {
				m.deletingCfg = ""
				return m, nil
			}
			if m.meshEditing {
				m.meshEditing = false
				m.meshFormFocus = 0
				m.setMeshViewportContent()
				return m, nil
			}
			if m.protectionEditing {
				m.protectionEditing = false
				m.protectionFormFocus = 0
				return m, nil
			}
			if m.editing {
				m.editing = false
				m.editingName = ""
				m.err = ""
				return m, nil
			}
			if m.adding {
				m.adding = false
				m.err = ""
				return m, nil
			}
			if m.stop != nil {
				m.stop()
				m.stop = nil
			}
			return m, tea.Quit
		case "n", "N":
			if m.updateAwaitingConfirm {
				m.updateAwaitingConfirm = false
				return m, nil
			}
			if m.deletingCfg != "" {
				m.deletingCfg = ""
				return m, nil
			}
			if m.tab == tabConfig && !m.adding && !m.editing && m.deletingCfg == "" {
				m.adding = true
				m.addInputs = newAddInputs()
				m.addFocus = 0
				m.addInputs[0].Focus()
				return m, nil
			}
		case "p", "P":
			if m.tab == tabConfig && !m.adding && !m.editing && m.deletingCfg == "" && len(m.cfgs) > 0 {
				idx := m.cfgList.Index()
				if idx < 0 {
					idx = 0
				}
				if idx < len(m.cfgs) {
					return m, runPing(m.cfgs[idx].Server, m.names[idx])
				}
			}
			if m.tab == tabCloud && !m.cloudLoading && !m.editing && len(m.cloudCfgs) > 0 {
				idx := m.cloudList.Index()
				if idx >= 0 && idx < len(m.cloudCfgs) {
					return m, runPing(m.cloudCfgs[idx].Server, m.cloudNames[idx])
				}
			}
		case "t", "T":
			if m.tab == tabConfig && !m.adding && !m.editing && m.deletingCfg == "" && len(m.cfgs) > 0 {
				idx := m.cfgList.Index()
				if idx < 0 {
					idx = 0
				}
				if idx < len(m.cfgs) {
					return m, runProbeVolter(m.cfgs[idx].Server, m.cfgs[idx].Token, m.names[idx])
				}
			}
			if m.tab == tabCloud && !m.cloudLoading && !m.editing && len(m.cloudCfgs) > 0 {
				idx := m.cloudList.Index()
				if idx >= 0 && idx < len(m.cloudCfgs) {
					return m, runProbeVolter(m.cloudCfgs[idx].Server, m.cloudCfgs[idx].Token, m.cloudNames[idx])
				}
			}
		case "u", "U":
			if m.tab == tabHome && m.updateAvailable != "" && m.updateBusy == "" && m.status != statusConnecting {
				m.updateAwaitingConfirm = true
				m.updateErr = ""
				return m, nil
			}
		case "r", "R":
			if m.tab == tabCloud && !m.cloudLoading && !m.editing {
				return m, m.reloadCloud(true)
			}
		case "d", "D", "delete":
			if m.tab == tabConfig && !m.adding && !m.editing && m.deletingCfg == "" && len(m.cfgs) > 0 {
				idx := m.cfgList.Index()
				if idx >= 0 && idx < len(m.names) {
					m.deletingCfg = m.names[idx]
				}
				return m, nil
			}
		case "y", "Y":
			if m.updateAwaitingConfirm && m.updateAvailable != "" && m.updateBusy == "" {
				m.updateAwaitingConfirm = false
				m.updateBusy = "скачивание и установка…"
				m.updateErr = ""
				if m.stop != nil {
					m.stop()
					m.stop = nil
				}
				m.status = statusDisconnected
				m.activeCfg = ""
				return m, runApplyUpdate(m.updateAvailable)
			}
			if m.deletingCfg != "" {
				_ = config.Delete(m.deletingCfg)
				m.deletingCfg = ""
				m.reloadCfgs()
				return m, autoProbeCmds(m.cfgs, m.names)
			}
		case "b", "B":
			if m.tab == tabSettings && !m.settingsEditing && len(m.cfgs) > 0 {
				return m, runPingAll(m.cfgs, m.names)
			}
		case "1", "2", "3":
			if m.tab == tabCluster {
				mode := "auto"
				switch msg.String() {
				case "1":
					mode = "auto"
				case "2":
					mode = "direct"
				case "3":
					mode = "peer_relay"
				}
				m.setClusterRouteMode(mode)
				m.setClusterViewportContent()
				return m, nil
			}
			if m.tab == tabProtection && !m.protectionEditing {
				var preset config.ProtectionOptions
				switch msg.String() {
				case "1":
					preset = protectionPresetBalanced()
				case "2":
					preset = protectionPresetStrict()
				case "3":
					preset = protectionPresetAutoByMetrics()
				}
				if m.protectionEditing && len(m.protectionInputs) == protectionFormFieldCount {
					m.protectionInputs = applyProtectionPresetToInputs(m.protectionInputs, preset)
					return m, nil
				}
				if m.protectionTarget == "" {
					_ = config.SaveProtection(preset)
				} else {
					cfg, err := config.LoadByName(m.protectionTarget)
					if err == nil {
						cfg.Protection = &preset
						_ = config.Save(m.protectionTarget, cfg)
					}
				}
				return m, nil
			}
		case "4":
			if m.tab == tabCluster {
				m.setClusterRouteMode("server_relay")
				m.setClusterViewportContent()
				return m, nil
			}
		case "s", "S":
			if m.tab == tabCluster {
				m.cycleClusterPreferredServer()
				m.setClusterViewportContent()
				return m, nil
			}
		case "e", "E":
			if m.tab == tabSettings && !m.settingsEditing {
				m.settingsEditing = true
				m.settingsFormFocus = 0
				m.settingsInputs = newSettingsInputs(m.clientSettings)
				m.settingsInputs[0].Focus()
				return m, nil
			}
			if m.tab == tabCloud && !m.cloudLoading && !m.editing && len(m.cloudCfgs) > 0 {
				idx := m.cloudList.Index()
				if idx >= 0 && idx < len(m.cloudCfgs) {
					m.editing = true
					m.editingName = m.cloudNames[idx]
					cfg := m.cloudCfgs[idx]
					m.editInputs = newInputsWithValues(m.cloudNames[idx], config.BuildConnectionURI(cfg.Server, cfg.Token), cfg.Routes, cfg.Exclude, cfg.TunCIDR6,
						cfg.Transport, cfg.QuicServer, cfg.QuicServerName, cfg.QuicSkipVerifyFormField(), cfg.QuicCertPinSHA256, cfg.QuicCaCert)
					m.editFocus = 0
					m.editInputs[0].Focus()
					m.err = ""
				}
				return m, nil
			}
			if m.tab == tabMesh && !m.meshEditing && len(m.names) > 0 {
				name := m.meshRelayEffectiveTarget()
				var r *config.RelayOptions
				if name != "" {
					if cfg, err := config.LoadByName(name); err == nil {
						r = config.EffectiveRelayOptions(&cfg)
					}
				}
				m.meshInputs = newMeshRelayInputs(r)
				m.meshEditing = true
				m.meshFormFocus = 0
				m.meshInputs[0].Focus()
				m.err = ""
				return m, nil
			}
			if m.tab == tabProtection && !m.protectionEditing {
				var opts config.ProtectionOptions
				if m.protectionTarget == "" {
					opts, _ = config.LoadProtection()
				} else {
					cfg, _ := config.LoadByName(m.protectionTarget)
					if cfg.Protection != nil {
						opts = *cfg.Protection
					}
				}
				m.protectionInputs = newProtectionInputs(opts)
				m.protectionEditing = true
				m.protectionFormFocus = 0
				m.protectionInputs[0].Focus()
				return m, nil
			}
			if m.tab == tabConfig && !m.adding && !m.editing && m.deletingCfg == "" && len(m.cfgs) > 0 {
				idx := m.cfgList.Index()
				if idx >= 0 && idx < len(m.cfgs) {
					m.editing = true
					m.editingName = m.names[idx]
					cfg := m.cfgs[idx]
					m.editInputs = newInputsWithValues(m.names[idx], config.BuildConnectionURI(cfg.Server, cfg.Token), cfg.Routes, cfg.Exclude, cfg.TunCIDR6,
						cfg.Transport, cfg.QuicServer, cfg.QuicServerName, cfg.QuicSkipVerifyFormField(), cfg.QuicCertPinSHA256, cfg.QuicCaCert)
					m.editFocus = 0
					m.editInputs[0].Focus()
					m.err = ""
				}
				return m, nil
			}
		case "k", "K":
			if m.tab == tabMesh && !m.meshEditing {
				uri, filePath, err := m.exportPeerTicket()
				if err != nil {
					m.err = err.Error()
					return m, nil
				}
				m.err = ""
				m.logs = append(m.logs, "OK\tPeer ticket saved: "+filePath)
				m.logs = append(m.logs, "OK\tPeer ticket uri: "+uri)
				if len(m.logs) > 500 {
					m.logs = m.logs[len(m.logs)-500:]
				}
				return m, nil
			}
		case "i", "I":
			if m.tab == tabMesh && !m.meshEditing {
				p, err := m.importPeerTicket()
				if err != nil {
					m.err = err.Error()
					return m, nil
				}
				m.err = ""
				m.logs = append(m.logs, "OK\tPeer ticket imported from "+p)
				if len(m.logs) > 500 {
					m.logs = m.logs[len(m.logs)-500:]
				}
				return m, nil
			}
		case "x", "X":
			if m.tab == tabMesh && !m.meshEditing {
				name := m.meshRelayEffectiveTarget()
				if name == "" {
					m.err = "нет локального профиля"
					return m, nil
				}
				cfg, err := config.LoadByName(name)
				if err != nil {
					m.err = err.Error()
					return m, nil
				}
				return m, runMeshSelfTest(cfg, name)
			}
		case "ctrl+left", "ctrl+h":
			if (m.tab == tabProtection || m.tab == tabMesh) && !m.protectionEditing && !m.meshEditing && len(m.names) > 0 {
				m.protectionClientIdx--
				if m.protectionClientIdx < 0 {
					m.protectionClientIdx = len(m.names)
				}
				m.protectionTarget = ""
				if m.protectionClientIdx > 0 {
					m.protectionTarget = m.names[m.protectionClientIdx-1]
				}
				if m.tab == tabMesh {
					m.setMeshViewportContent()
				}
				return m, nil
			}
		case "ctrl+right", "ctrl+l":
			if (m.tab == tabProtection || m.tab == tabMesh) && !m.protectionEditing && !m.meshEditing && len(m.names) > 0 {
				m.protectionClientIdx = (m.protectionClientIdx + 1) % (len(m.names) + 1)
				m.protectionTarget = ""
				if m.protectionClientIdx > 0 {
					m.protectionTarget = m.names[m.protectionClientIdx-1]
				}
				if m.tab == tabMesh {
					m.setMeshViewportContent()
				}
				return m, nil
			}
		case "tab":
			if m.deletingCfg != "" {
				m.deletingCfg = ""
			}
			if m.meshEditing && len(m.meshInputs) == meshRelayInputCount {
				m.meshFormFocus = (m.meshFormFocus + 1) % len(m.meshInputs)
				for i := range m.meshInputs {
					if i == m.meshFormFocus {
						m.meshInputs[i].Focus()
					} else {
						m.meshInputs[i].Blur()
					}
				}
				return m, nil
			}
			if m.settingsEditing && len(m.settingsInputs) == 3 {
				m.settingsFormFocus = (m.settingsFormFocus + 1) % 3
				for i := range m.settingsInputs {
					if i == m.settingsFormFocus {
						m.settingsInputs[i].Focus()
					} else {
						m.settingsInputs[i].Blur()
					}
				}
				return m, nil
			}
			if m.protectionEditing && len(m.protectionInputs) == protectionFormFieldCount {
				m.protectionFormFocus = (m.protectionFormFocus + 1) % len(m.protectionInputs)
				for i := range m.protectionInputs {
					if i == m.protectionFormFocus {
						m.protectionInputs[i].Focus()
					} else {
						m.protectionInputs[i].Blur()
					}
				}
				return m, nil
			}
			if m.editing {
				m.editFocus = (m.editFocus + 1) % len(m.editInputs)
				for i := range m.editInputs {
					if i == m.editFocus {
						m.editInputs[i].Focus()
					} else {
						m.editInputs[i].Blur()
					}
				}
				return m, nil
			}
			if m.adding {
				m.addFocus = (m.addFocus + 1) % len(m.addInputs)
				for i := range m.addInputs {
					if i == m.addFocus {
						m.addInputs[i].Focus()
					} else {
						m.addInputs[i].Blur()
					}
				}
				return m, nil
			}
			m.tab = tab((int(m.tab) + 1) % tabCount)
			return m, m.batchTabSwitch()
		case "shift+tab":
			if m.deletingCfg != "" {
				m.deletingCfg = ""
			}
			if m.meshEditing && len(m.meshInputs) == meshRelayInputCount {
				m.meshFormFocus = (m.meshFormFocus + len(m.meshInputs) - 1) % len(m.meshInputs)
				for i := range m.meshInputs {
					if i == m.meshFormFocus {
						m.meshInputs[i].Focus()
					} else {
						m.meshInputs[i].Blur()
					}
				}
				return m, nil
			}
			if m.settingsEditing && len(m.settingsInputs) == 3 {
				m.settingsFormFocus = (m.settingsFormFocus + 2) % 3
				for i := range m.settingsInputs {
					if i == m.settingsFormFocus {
						m.settingsInputs[i].Focus()
					} else {
						m.settingsInputs[i].Blur()
					}
				}
				return m, nil
			}
			if m.protectionEditing && len(m.protectionInputs) == protectionFormFieldCount {
				m.protectionFormFocus = (m.protectionFormFocus + len(m.protectionInputs) - 1) % len(m.protectionInputs)
				for i := range m.protectionInputs {
					if i == m.protectionFormFocus {
						m.protectionInputs[i].Focus()
					} else {
						m.protectionInputs[i].Blur()
					}
				}
				return m, nil
			}
			if m.editing {
				m.editFocus = (m.editFocus + len(m.editInputs) - 1) % len(m.editInputs)
				for i := range m.editInputs {
					if i == m.editFocus {
						m.editInputs[i].Focus()
					} else {
						m.editInputs[i].Blur()
					}
				}
				return m, nil
			}
			if m.adding {
				m.addFocus = (m.addFocus + len(m.addInputs) - 1) % len(m.addInputs)
				for i := range m.addInputs {
					if i == m.addFocus {
						m.addInputs[i].Focus()
					} else {
						m.addInputs[i].Blur()
					}
				}
				return m, nil
			}
			m.tab = tab((int(m.tab) + 3) % tabCount)
			return m, m.batchTabSwitch()
		case "right":
			if m.adding || m.editing || m.protectionEditing || m.meshEditing || m.settingsEditing {
				break
			}
			if m.deletingCfg != "" {
				m.deletingCfg = ""
			}
			m.tab = tab((int(m.tab) + 1) % tabCount)
			return m, m.batchTabSwitch()
		case "left":
			if m.adding || m.editing || m.protectionEditing || m.meshEditing || m.settingsEditing {
				break
			}
			if m.deletingCfg != "" {
				m.deletingCfg = ""
			}
			m.tab = tab((int(m.tab) + 3) % tabCount)
			return m, m.batchTabSwitch()
		case "enter":
			if m.settingsEditing && len(m.settingsInputs) == 3 {
				m.clientSettings = settingsFromInputs(m.settingsInputs)
				_ = config.SaveClientSettings(m.clientSettings)
				m.settingsEditing = false
				m.settingsFormFocus = 0
				return m, nil
			}
			if m.meshEditing && len(m.meshInputs) == meshRelayInputCount {
				nu, formErr := meshRelayFromInputs(m.meshInputs)
				if formErr != "" {
					m.err = formErr
					return m, nil
				}
				name := m.meshRelayEffectiveTarget()
				if name == "" {
					m.err = "нет локального профиля"
					return m, nil
				}
				cfg, err := config.LoadByName(name)
				if err != nil {
					m.err = err.Error()
					return m, nil
				}
				old := config.EffectiveRelayOptions(&cfg)
				relayMergeKeepAdvanced(old, &nu)
				if cfg.Mesh != nil && cfg.Mesh.Enabled {
					cfg.Mesh = config.RelayOptionsToMesh(&nu, cfg.Mesh)
					cfg.Relay = nil
				} else {
					cfg.Relay = &nu
				}
				if err := config.Save(name, cfg); err != nil {
					m.err = err.Error()
					return m, nil
				}
				m.reloadCfgs()
				m.meshEditing = false
				m.meshFormFocus = 0
				m.err = ""
				m.setMeshViewportContent()
				return m, nil
			}
			if m.protectionEditing && len(m.protectionInputs) == protectionFormFieldCount {
				opts := protectionOptsFromInputs(m.protectionInputs)
				if m.protectionTarget == "" {
					_ = config.SaveProtection(opts)
				} else {
					cfg, err := config.LoadByName(m.protectionTarget)
					if err == nil {
						cfg.Protection = &opts
						_ = config.Save(m.protectionTarget, cfg)
					}
				}
				m.protectionEditing = false
				m.protectionFormFocus = 0
				return m, nil
			}
			if m.editing {
				if m.editFocus < len(m.editInputs)-1 {
					m.editFocus++
					for i := range m.editInputs {
						if i == m.editFocus {
							m.editInputs[i].Focus()
						} else {
							m.editInputs[i].Blur()
						}
					}
				} else {
					oldName := m.editingName
					newName := config.SanitizeName(strings.TrimSpace(m.editInputs[0].Value()))
					if newName == "" {
						newName = "default"
					}
					cfg, formErr := configFromConnFormInputs(m.editInputs)
					if formErr != "" {
						m.err = formErr
					} else {
						cfg = mergeCfgPreserveRelayProtection(oldName, cfg)
						if newName != oldName {
							_ = config.Delete(oldName)
						}
						if err := config.Save(newName, cfg); err != nil {
							m.err = err.Error()
						} else {
							m.reloadCfgs()
							m.editing = false
							m.editingName = ""
							m.err = ""
							return m, autoProbeCmds(m.cfgs, m.names)
						}
					}
				}
				return m, nil
			}
			if m.adding {
				if m.addFocus < len(m.addInputs)-1 {
					m.addFocus++
					for i := range m.addInputs {
						if i == m.addFocus {
							m.addInputs[i].Focus()
						} else {
							m.addInputs[i].Blur()
						}
					}
				} else {
					name := config.SanitizeName(strings.TrimSpace(m.addInputs[0].Value()))
					if name == "" {
						name = "default"
					}
					cfg, formErr := configFromConnFormInputs(m.addInputs)
					if formErr != "" {
						m.err = formErr
					} else if err := config.Save(name, cfg); err != nil {
						m.err = err.Error()
					} else {
						m.reloadCfgs()
						m.adding = false
						m.err = ""
						return m, autoProbeCmds(m.cfgs, m.names)
					}
				}
				return m, nil
			}
			if m.tab == tabConfig && m.deletingCfg == "" && m.opts.ConnectFn != nil && len(m.cfgs) > 0 {
				idx := m.cfgList.Index()
				if idx < 0 {
					idx = 0
				}
				if idx < len(m.cfgs) && m.status != statusConnecting {
					if m.status == statusConnected {
						profile := m.activeCfg
						if m.stop != nil {
							m.stop()
							m.stop = nil
						}
						m.status = statusDisconnected
						m.activeCfg = ""
						m.err = ""
						if m.opts.IPCStatusCh != nil {
							go func() {
								m.opts.IPCStatusCh <- "status:disconnected:" + profile
							}()
						}
					} else {
						m.status = statusConnecting
						m.err = ""
						m.activeCfg = m.names[idx]
						m.connectCount++
						cfg := m.cfgs[idx]
						return m, waitConnect(cfg, m.activeCfg, m.connectCount-1, m.clientSettings, m.opts.ConnectFn)
					}
				}
			}
			if m.tab == tabCloud && !m.cloudLoading && m.opts.ConnectFn != nil && len(m.cloudCfgs) > 0 {
				idx := m.cloudList.Index()
				if idx < 0 {
					idx = 0
				}
				if idx < len(m.cloudCfgs) && m.status != statusConnecting {
					if m.status == statusConnected {
						profile := m.activeCfg
						if m.stop != nil {
							m.stop()
							m.stop = nil
						}
						m.status = statusDisconnected
						m.activeCfg = ""
						m.err = ""
						if m.opts.IPCStatusCh != nil {
							go func() {
								m.opts.IPCStatusCh <- "status:disconnected:" + profile
							}()
						}
					} else {
						m.status = statusConnecting
						m.err = ""
						m.activeCfg = m.cloudNames[idx]
						m.connectCount++
						cfg := m.cloudCfgs[idx]
						if saved, err := config.LoadByName(m.cloudNames[idx]); err == nil &&
							saved.Server == cfg.Server && saved.Token == cfg.Token {
							cfg = saved
						}
						mode := ""
						if m.cloudMode != nil {
							mode = m.cloudMode[m.cloudNames[idx]]
						}
						probeV6 := m.cloudIPv6 != nil && m.cloudIPv6[m.cloudNames[idx]]
						config.ApplyCloudConnectDefaults(&cfg, mode, probeV6)
						return m, waitConnect(cfg, m.activeCfg, m.connectCount-1, m.clientSettings, m.opts.ConnectFn)
					}
				}
			}
		}
	case WatchdogReconnectMsg:
		if m.opts.ConnectFn == nil || m.status != statusConnected || m.activeCfg == "" {
			return m, nil
		}
		cfg, ok := m.configSnapshotForActive()
		if !ok {
			return m, nil
		}
		if m.stop != nil {
			m.stop()
			m.stop = nil
		}
		m.status = statusConnecting
		m.err = ""
		m.connectCount++
		return m, waitConnect(cfg, m.activeCfg, m.connectCount-1, m.clientSettings, m.opts.ConnectFn)
	case connectedMsg:
		m.status = statusConnected
		m.stop = msg.stop
		if m.tab == tabMesh {
			m.setMeshViewportContent()
			return m, meshTickCmd()
		}
		if m.tab == tabCluster {
			m.setClusterViewportContent()
			return m, meshTickCmd()
		}
		return m, nil
	case disconnectedMsg:
		m.status = statusDisconnected
		m.stop = nil
		m.activeCfg = ""
		return m, nil
	case errMsg:
		m.status = statusDisconnected
		m.stop = nil
		m.err = string(msg)
		m.activeCfg = ""
		return m, nil
	case logMsg:
		m.logsMu.Lock()
		if m.tab == tabLogs {
			m.logAutoScroll = m.logViewport.AtBottom()
		} else {
			m.logAutoScroll = true
		}
		m.logs = append(m.logs, string(msg))
		if len(m.logs) > 500 {
			m.logs = m.logs[len(m.logs)-500:]
		}
		m.logsMu.Unlock()
		return m, nil
	case pingResultMsg:
		if msg.failed {
			m.pingFailed[msg.name] = true
			delete(m.pingResults, msg.name)
		} else {
			m.pingResults[msg.name] = msg.d
			delete(m.pingFailed, msg.name)
		}
		m.refreshCfgItems()
		m.refreshCloudItems()
		return m, nil
	case meshRefreshMsg:
		m.setMeshViewportContent()
		m.setClusterViewportContent()
		if m.tab == tabMesh || m.tab == tabCluster {
			return m, meshTickCmd()
		}
		return m, nil
	case meshSelfTestMsg:
		m.meshSelfTest = msg.report
		m.setMeshViewportContent()
		return m, nil
	case ipcCommandMsg:
		listenNext := m.listenIPCCommands()
		if msg.command == "disconnect" {
			if m.stop != nil {
				m.stop()
				m.stop = nil
			}
			m.status = statusDisconnected
			m.activeCfg = ""
			m.err = ""
			return m, listenNext
		}
		if strings.HasPrefix(msg.command, "connect:") {
			profile := strings.TrimPrefix(msg.command, "connect:")
			return m, tea.Batch(listenNext, m.tryConnect(profile))
		}
		if msg.command == "quit" {
			if m.stop != nil {
				m.stop()
				m.stop = nil
			}
			return m, tea.Quit
		}
		return m, listenNext
	case autoConnectMsg:
		return m, m.tryConnect(msg.profile)
	case tea.WindowSizeMsg:
		m.logViewport.Width = msg.Width - 4
		m.logViewport.Height = msg.Height - 10
		if m.logViewport.Height < 5 {
			m.logViewport.Height = 5
		}
		if m.logViewport.Width < 20 {
			m.logViewport.Width = 20
		}
		m.meshViewport.Width = msg.Width - 4
		m.meshViewport.Height = msg.Height - 14
		if m.meshViewport.Height < 5 {
			m.meshViewport.Height = 5
		}
		if m.meshViewport.Width < 20 {
			m.meshViewport.Width = 20
		}
		m.clusterViewport.Width = msg.Width - 4
		m.clusterViewport.Height = msg.Height - 14
		if m.clusterViewport.Height < 5 {
			m.clusterViewport.Height = 5
		}
		if m.clusterViewport.Width < 20 {
			m.clusterViewport.Width = 20
		}
		m.protectionViewport.Width = msg.Width - 4
		m.protectionViewport.Height = msg.Height - 10
		if m.protectionViewport.Height < 5 {
			m.protectionViewport.Height = 5
		}
		if m.protectionViewport.Width < 20 {
			m.protectionViewport.Width = 20
		}
		return m, nil
	case pterovpnResultMsg:
		if msg.err {
			m.pterovpnRes[msg.name] = 2
		} else if msg.ok {
			m.pterovpnRes[msg.name] = 1
		} else {
			m.pterovpnRes[msg.name] = 0
		}
		if m.cfgIPv6 == nil {
			m.cfgIPv6 = make(map[string]bool)
		}
		prevIPv6 := m.cfgIPv6[msg.name]
		nextIPv6 := msg.ipv6
		modeForIPv6 := strings.TrimSpace(msg.mode)
		if modeForIPv6 == "unknown" && prevIPv6 && !nextIPv6 {
			nextIPv6 = true
		}
		m.cfgIPv6[msg.name] = nextIPv6
		if m.cloudIPv6 == nil {
			m.cloudIPv6 = make(map[string]bool)
		}
		prevCloudIPv6 := m.cloudIPv6[msg.name]
		nextCloudIPv6 := msg.ipv6
		if modeForIPv6 == "unknown" && prevCloudIPv6 && !nextCloudIPv6 {
			nextCloudIPv6 = true
		}
		m.cloudIPv6[msg.name] = nextCloudIPv6
		if m.cfgMode == nil {
			m.cfgMode = make(map[string]string)
		}
		prevMode := strings.TrimSpace(m.cfgMode[msg.name])
		nextMode := strings.TrimSpace(msg.mode)
		if nextMode == "unknown" && prevMode != "" && prevMode != "unknown" {
			nextMode = prevMode
		}
		m.cfgMode[msg.name] = nextMode
		if m.cloudMode == nil {
			m.cloudMode = make(map[string]string)
		}
		prevCloudMode := strings.TrimSpace(m.cloudMode[msg.name])
		nextCloudMode := strings.TrimSpace(msg.mode)
		if nextCloudMode == "unknown" && prevCloudMode != "" && prevCloudMode != "unknown" {
			nextCloudMode = prevCloudMode
		}
		m.cloudMode[msg.name] = nextCloudMode
		m.refreshCfgItems()
		m.refreshCloudItems()
		return m, nil
	case updateCheckMsg:
		m.updateAvailable = msg.latest
		return m, nil
	case updateApplyMsg:
		m.updateBusy = ""
		if msg.err != nil {
			m.updateErr = msg.err.Error()
			return m, nil
		}
		return m, tea.Quit
	case cloudFetchedMsg:
		m.cloudLoading = false
		if msg.err != "" {
			m.cloudFetchErr = msg.err
			return m, nil
		}
		m.cloudFetchErr = ""
		m.cloudCfgs = msg.cfgs
		m.cloudNames = msg.names
		if m.cloudGeo == nil {
			m.cloudGeo = make(map[string]geo.Info)
		}
		m.cloudIPv6 = make(map[string]bool)
		items := m.buildCloudItems()
		l := list.New(items, list.NewDefaultDelegate(), 40, 14)
		l.Title = "Cloud конфиги"
		l.SetShowStatusBar(false)
		m.cloudList = l
		return m, tea.Batch(autoProbeCmds(m.cloudCfgs, m.cloudNames), runGeoFetches(m.cloudCfgs))
	case geoFetchedMsg:
		if m.cloudGeo == nil {
			m.cloudGeo = make(map[string]geo.Info)
		}
		m.cloudGeo[msg.host] = msg.info
		if m.tab == tabCloud {
			m.refreshCloudItems()
		}
		return m, nil
	}

	if m.settingsEditing && m.settingsFormFocus < len(m.settingsInputs) {
		m.settingsInputs[m.settingsFormFocus], cmd = m.settingsInputs[m.settingsFormFocus].Update(msg)
		return m, cmd
	}
	if m.meshEditing && m.meshFormFocus < len(m.meshInputs) {
		m.meshInputs[m.meshFormFocus], cmd = m.meshInputs[m.meshFormFocus].Update(msg)
		return m, cmd
	}
	if m.protectionEditing && m.protectionFormFocus < len(m.protectionInputs) {
		m.protectionInputs[m.protectionFormFocus], cmd = m.protectionInputs[m.protectionFormFocus].Update(msg)
		return m, cmd
	}
	if m.editing && m.editFocus < len(m.editInputs) {
		m.editInputs[m.editFocus], cmd = m.editInputs[m.editFocus].Update(msg)
		m.editInputs = fillConnectionFromAnyField(m.editInputs)
		return m, cmd
	}
	if m.adding && m.addFocus < len(m.addInputs) {
		m.addInputs[m.addFocus], cmd = m.addInputs[m.addFocus].Update(msg)
		m.addInputs = fillConnectionFromAnyField(m.addInputs)
		return m, cmd
	}
	if m.tab == tabMesh && !m.meshEditing {
		m.meshViewport, cmd = m.meshViewport.Update(msg)
		return m, cmd
	}
	if m.tab == tabCluster {
		m.clusterViewport, cmd = m.clusterViewport.Update(msg)
		return m, cmd
	}
	if m.tab == tabProtection {
		m.protectionViewport, cmd = m.protectionViewport.Update(msg)
		return m, cmd
	}
	if m.tab == tabConfig {
		m.cfgList, cmd = m.cfgList.Update(msg)
	}
	if m.tab == tabCloud {
		m.cloudList, cmd = m.cloudList.Update(msg)
	}
	return m, cmd
}

func waitConnect(cfg config.Config, configName string, reconnectCount int, settings config.ClientSettings, fn ConnectFn) tea.Cmd {
	return func() tea.Msg {
		stop, err := fn(cfg, configName, reconnectCount, settings)
		if err != nil {
			return errMsg(err.Error())
		}
		return connectedMsg{stop: stop}
	}
}

func (m *Model) tryConnect(profileName string) tea.Cmd {
	for i, name := range m.names {
		if name == profileName {
			if m.status == statusConnected {
				if m.stop != nil {
					m.stop()
					m.stop = nil
				}
				m.status = statusDisconnected
				m.activeCfg = ""
			}
			m.status = statusConnecting
			m.err = ""
			m.activeCfg = profileName
			m.connectCount++
			cfg := m.cfgs[i]
			return waitConnect(cfg, profileName, m.connectCount-1, m.clientSettings, m.opts.ConnectFn)
		}
	}
	for i, name := range m.cloudNames {
		if name == profileName {
			if m.status == statusConnected {
				if m.stop != nil {
					m.stop()
					m.stop = nil
				}
				m.status = statusDisconnected
				m.activeCfg = ""
			}
			m.status = statusConnecting
			m.err = ""
			m.activeCfg = profileName
			m.connectCount++
			cfg := m.cloudCfgs[i]
			if saved, err := config.LoadByName(profileName); err == nil &&
				saved.Server == cfg.Server && saved.Token == cfg.Token {
				cfg = saved
			}
			mode := ""
			if m.cloudMode != nil {
				mode = m.cloudMode[profileName]
			}
			probeV6 := m.cloudIPv6 != nil && m.cloudIPv6[profileName]
			config.ApplyCloudConnectDefaults(&cfg, mode, probeV6)
			return waitConnect(cfg, profileName, m.connectCount-1, m.clientSettings, m.opts.ConnectFn)
		}
	}
	return func() tea.Msg {
		return errMsg("Profile not found: " + profileName)
	}
}

const protectionAnalyticsLimit = 20

func (m *Model) protectionView() string {
	var b strings.Builder
	if !m.protectionEditing {
		b.WriteString(sectionTitle.Render("Аналитика") + "\n")
		store, err := metrics.Load()
		if err == nil && len(store.Records) > 0 {
			start := len(store.Records) - protectionAnalyticsLimit
			if start < 0 {
				start = 0
			}
			for i := len(store.Records) - 1; i >= start; i-- {
				r := store.Records[i]
				hs := "-"
				if r.HandshakeOK {
					hs = lipgloss.NewStyle().Foreground(lipgloss.Color(success)).Render("ok")
				} else {
					hs = lipgloss.NewStyle().Foreground(lipgloss.Color(errCol)).Render("fail")
				}
				errType := r.ErrorType
				if errType == "" {
					errType = "-"
				}
				dur := r.Duration.Round(time.Second).String()
				if r.Duration == 0 && !r.End.IsZero() {
					dur = "-"
				}
				b.WriteString("  ")
				b.WriteString(kvLabel.Render(r.Start.Format("02.01 15:04")) + " ")
				b.WriteString(kvValue.Render(fmt.Sprintf("%s  %s  HS:%s  R:%d  RTT:%s/%s  DNS:%v/%v",
					dur, errType, hs, r.ReconnectCount,
					formatRTT(r.RTTBefore), formatRTT(r.RTTDuring), r.DNSOKBefore, r.DNSOKAfter)))
				b.WriteString("\n")
			}
		} else {
			b.WriteString("  ")
			b.WriteString(emptyState.Render("нет данных"))
			b.WriteString("\n")
		}
		b.WriteString("\n")
	}
	b.WriteString(sectionTitle.Render("Настройки защиты") + "\n")
	if m.protectionEditing && len(m.protectionInputs) == protectionFormFieldCount {
		labels := []string{
			"obfuscation", "junkCount", "junkMin", "junkMax", "padS1", "padS2", "padS3", "padS4", "preCheck", "magicSplit", "junkStyle", "flushPolicy", "preambleProfile", "preambleRotate",
			"standaloneDpiOnly", "dpiLocalEngine", "splitAfter", "ttlMillis", "disorder", "splitAfter2", "ttl2Millis", "jitterMaxMs", "leadInMs", "dpiLocalPreset",
		}
		for i := range m.protectionInputs {
			b.WriteString("  ")
			b.WriteString(kvLabel.Render(labels[i]+":") + " ")
			b.WriteString(m.protectionInputs[i].View())
			b.WriteString("\n")
		}
		b.WriteString("\n  ")
		b.WriteString(hintKey.Render("Tab") + " ")
		b.WriteString(hintText.Render("след.  ") + hintKey.Render("Enter") + " ")
		b.WriteString(hintText.Render("сохранить  ") + hintKey.Render("Esc") + " ")
		b.WriteString(hintText.Render("отмена") + "\n")
	} else {
		var opts config.ProtectionOptions
		if m.protectionTarget == "" {
			opts, _ = config.LoadProtection()
		} else {
			cfg, _ := config.LoadByName(m.protectionTarget)
			if cfg.Protection != nil {
				opts = *cfg.Protection
			}
		}
		obf := opts.Obfuscation
		if obf == "" {
			obf = "default"
		}
		b.WriteString("  ")
		b.WriteString(kvLabel.Render("obfuscation:") + " ")
		b.WriteString(kvValue.Render(obf) + "   ")
		b.WriteString(kvLabel.Render("junkCount:") + " ")
		b.WriteString(kvValue.Render(strconv.Itoa(opts.JunkCount)) + "   ")
		b.WriteString(kvLabel.Render("junkMin:") + " ")
		b.WriteString(kvValue.Render(strconv.Itoa(opts.JunkMin)) + "   ")
		b.WriteString(kvLabel.Render("junkMax:") + " ")
		b.WriteString(kvValue.Render(strconv.Itoa(opts.JunkMax)) + "\n")
		b.WriteString("  ")
		b.WriteString(kvLabel.Render("padS1-4:") + " ")
		b.WriteString(kvValue.Render(fmt.Sprintf("%d/%d/%d/%d", opts.PadS1, opts.PadS2, opts.PadS3, opts.PadS4)) + "   ")
		b.WriteString(kvLabel.Render("preCheck:") + " ")
		b.WriteString(kvValue.Render(fmt.Sprintf("%v", opts.PreCheck)) + "\n")
		b.WriteString("  ")
		b.WriteString(kvLabel.Render("magicSplit:") + " ")
		b.WriteString(kvValue.Render(orEmpty(opts.MagicSplit, "0")) + "   ")
		b.WriteString(kvLabel.Render("junkStyle:") + " ")
		b.WriteString(kvValue.Render(orEmpty(opts.JunkStyle, "random")) + "   ")
		b.WriteString(kvLabel.Render("flushPolicy:") + " ")
		b.WriteString(kvValue.Render(orEmpty(opts.FlushPolicy, "once")) + "\n")
		b.WriteString("  ")
		b.WriteString(kvLabel.Render("preambleProfile:") + " ")
		b.WriteString(kvValue.Render(orEmpty(opts.PreambleProfile, "-")) + "   ")
		b.WriteString(kvLabel.Render("preambleRotate:") + " ")
		b.WriteString(kvValue.Render(fmt.Sprintf("%v", opts.PreambleRotate)) + "\n")
		de := config.MergeDpiLocalEmbeddedDefaults(opts.DpiLocalEmbedded)
		eng := strings.TrimSpace(opts.DpiLocalEngine)
		if eng == "" {
			eng = "embedded"
		}
		b.WriteString("  ")
		b.WriteString(kvLabel.Render("standaloneDpiOnly:") + " ")
		b.WriteString(kvValue.Render(fmt.Sprintf("%v", opts.StandaloneDpiOnly)) + "   ")
		b.WriteString(kvLabel.Render("dpiLocalEngine:") + " ")
		b.WriteString(kvValue.Render(eng) + "\n")
		b.WriteString("  ")
		b.WriteString(kvLabel.Render("dpi embedded:") + " ")
		b.WriteString(kvValue.Render(fmt.Sprintf("s %d|%d ttl %d|%d dis %v jit %d lead %d", de.SplitAfter, de.SplitAfter2, de.TTLMillis, de.TTL2Millis, de.Disorder, de.JitterMaxMs, de.LeadInMs)) + "   ")
		b.WriteString(kvLabel.Render("dpiLocalPreset:") + " ")
		b.WriteString(kvValue.Render(orEmpty(opts.DpiLocalPreset, "-")) + "\n")
		b.WriteString("  ")
		b.WriteString(hintKey.Render("E") + " ")
		b.WriteString(hintText.Render("редактировать  ") + hintKey.Render("1/2/3") + " ")
		b.WriteString(hintText.Render("баланс/усил/авто") + "\n")
	}

	b.WriteString("\n")
	b.WriteString(sectionTitle.Render("Клиентские опции") + "\n")
	target := "Глобально"
	if m.protectionTarget != "" {
		target = m.protectionTarget
	}
	b.WriteString("  ")
	b.WriteString(kvLabel.Render("Цель:") + " ")
	b.WriteString(kvValue.Render(target))
	if len(m.names) > 0 {
		b.WriteString("   ")
		b.WriteString(hintKey.Render("Ctrl+←/→") + hintText.Render(" переключить"))
	}
	b.WriteString("\n")

	full := b.String()
	m.protectionViewport.SetContent(full)
	if m.protectionEditing && len(m.protectionInputs) == protectionFormFieldCount && m.protectionViewport.Height > 0 {
		focusLine := 1 + m.protectionFormFocus
		if focusLine < m.protectionViewport.YOffset {
			m.protectionViewport.YOffset = focusLine
		}
		if focusLine >= m.protectionViewport.YOffset+m.protectionViewport.Height {
			m.protectionViewport.YOffset = focusLine - m.protectionViewport.Height + 1
		}
		if m.protectionViewport.YOffset < 0 {
			m.protectionViewport.YOffset = 0
		}
	}
	return m.protectionViewport.View()
}

func orEmpty(s, def string) string {
	if s == "" {
		return def
	}
	return s
}

func formatRTT(d time.Duration) string {
	if d == 0 {
		return "-"
	}
	return d.Round(time.Millisecond).String()
}

func (m *Model) View() string {
	var b strings.Builder
	b.WriteString(titleStyle.Render("volter  dev - c0redev(unitdevgcc)"))
	if m.updateAvailable != "" {
		b.WriteString("  ")
		b.WriteString(lipgloss.NewStyle().Foreground(lipgloss.Color("11")).Render("↑ " + m.updateAvailable))
	}
	b.WriteString("  ")
	switch m.status {
	case statusConnected:
		b.WriteString(statusStyle.Render("Ядро: Подключено"))
	case statusConnecting:
		b.WriteString("Ядро: Подключение...")
	default:
		b.WriteString("Ядро: Отключено")
	}
	mode := m.clientSettings.Mode
	if mode == "" || mode == "tun" {
		b.WriteString("  TUN  ")
	} else {
		b.WriteString("  Proxy  ")
	}
	if m.activeCfg != "" {
		b.WriteString("Конфигурация: " + m.activeCfg)
	}
	b.WriteString("\n\n")

	for i, name := range tabNames {
		if i == int(m.tab) {
			b.WriteString(activeTabStyle.Render("[ " + name + " ]"))
		} else {
			b.WriteString(tabStyle.Render(name))
		}
	}
	b.WriteString("\n\n")

	var content strings.Builder
	switch m.tab {
	case tabHome:
		if m.updateAvailable != "" {
			content.WriteString(lipgloss.NewStyle().Foreground(lipgloss.Color("11")).Render("Доступно обновление: " + m.updateAvailable))
			if m.updateBusy != "" {
				content.WriteString(lipgloss.NewStyle().Foreground(lipgloss.Color("14")).Render(" — " + m.updateBusy))
			}
			content.WriteString("\n")
			if m.updateErr != "" {
				content.WriteString(errStyle.Render(m.updateErr) + "\n")
			}
			if m.updateAwaitingConfirm {
				content.WriteString(hintKey.Render("y") + hintText.Render(" скачать и перезапустить  ") + hintKey.Render("n") + hintText.Render(" / Esc отмена\n"))
			} else if m.updateBusy == "" {
				content.WriteString(hintKey.Render("u") + hintText.Render(" обновить   ") + lipgloss.NewStyle().Foreground(lipgloss.Color(dim)).Render("https://dev.c0redev.volter/releases\n"))
			}
			content.WriteString("\n")
		}
		content.WriteString(sectionTitle.Render("Статус") + "\n")
		switch m.status {
		case statusConnected:
			content.WriteString(statusStyle.Render("Ядро: Подключено"))
		case statusConnecting:
			content.WriteString(lipgloss.NewStyle().Foreground(lipgloss.Color("11")).Bold(true).Render("Ядро: Подключение..."))
		default:
			content.WriteString(lipgloss.NewStyle().Foreground(lipgloss.Color(dim)).Render("Ядро: Отключено"))
		}
		content.WriteString("\n")
		if m.activeCfg != "" {
			idx := -1
			for i, n := range m.names {
				if n == m.activeCfg {
					idx = i
					break
				}
			}
			if idx >= 0 && idx < len(m.cfgs) {
				content.WriteString("Конфигурация: " + m.cfgs[idx].Server + "\n")
			} else {
				for i, n := range m.cloudNames {
					if n == m.activeCfg && i < len(m.cloudCfgs) {
						content.WriteString("Конфигурация: " + m.cloudCfgs[i].Server + "\n")
						break
					}
				}
			}
		}
		if m.clientSettings.Mode == "proxy" {
			content.WriteString("Режим: Proxy (" + m.clientSettings.ProxyListen + ")\n")
		} else {
			content.WriteString("Режим: TUN\n")
		}
		if m.err != "" {
			content.WriteString(errStyle.Render("Ошибка: " + m.err))
		}
	case tabConfig:
		if m.deletingCfg != "" {
			content.WriteString("Удалить конфигурацию \"" + m.deletingCfg + "\"? y/n")
		} else if m.editing {
			content.WriteString(sectionTitle.Render("Редактирование: "+m.editingName) + "\n\n")
			for i := range m.editInputs {
				lbl := ""
				if i < len(cfgFormLabels) {
					lbl = cfgFormLabels[i]
				}
				content.WriteString(lbl + " ")
				content.WriteString(m.editInputs[i].View())
				content.WriteString("\n")
			}
			content.WriteString("\n" + hintKey.Render("Tab/Enter") + hintText.Render(" следующее  ") + hintKey.Render("Esc") + hintText.Render(" отмена"))
			if m.err != "" {
				content.WriteString("\n")
				content.WriteString(errStyle.Render(m.err))
			}
		} else if m.adding {
			content.WriteString(sectionTitle.Render("Новая конфигурация") + "\n\n")
			for i := range m.addInputs {
				lbl := ""
				if i < len(cfgFormLabels) {
					lbl = cfgFormLabels[i]
				}
				content.WriteString(lbl + " ")
				content.WriteString(m.addInputs[i].View())
				content.WriteString("\n")
			}
			content.WriteString("\n" + hintKey.Render("Tab/Enter") + hintText.Render(" следующее  ") + hintKey.Render("Esc") + hintText.Render(" отмена"))
			if m.err != "" {
				content.WriteString("\n")
				content.WriteString(errStyle.Render(m.err))
			}
		} else {
			content.WriteString(m.cfgList.View())
			idx := m.cfgList.Index()
			if idx >= 0 && idx < len(m.cfgs) {
				name := m.names[idx]
				ipv6Ok := m.cfgIPv6 != nil && m.cfgIPv6[name]
				_, probed := m.pterovpnRes[name]
				var ipv6Str string
				if probed {
					if ipv6Ok {
						ipv6Str = "IPv6: ✓"
					} else {
						ipv6Str = "IPv6: ✗"
					}
				} else {
					ipv6Str = "IPv6: —"
				}
				content.WriteString(cloudDetailStyle.Render(ipv6Str))
			}
		}
	case tabCloud:
		if m.editing {
			content.WriteString(sectionTitle.Render("Редактирование cloud: "+m.editingName) + "\n\n")
			for i := range m.editInputs {
				lbl := ""
				if i < len(cfgFormLabels) {
					lbl = cfgFormLabels[i]
				}
				content.WriteString(lbl + " ")
				content.WriteString(m.editInputs[i].View())
				content.WriteString("\n")
			}
			content.WriteString("\n" + hintKey.Render("Tab/Enter") + hintText.Render(" следующее  ") + hintKey.Render("Esc") + hintText.Render(" отмена"))
			if m.err != "" {
				content.WriteString("\n")
				content.WriteString(errStyle.Render(m.err))
			}
		} else if m.cloudLoading {
			content.WriteString("Загрузка cloud конфигов...")
		} else if m.cloudFetchErr != "" {
			content.WriteString(errStyle.Render("Ошибка: " + m.cloudFetchErr))
			content.WriteString("\n\nR - обновить")
		} else if len(m.cloudCfgs) == 0 {
			content.WriteString(emptyState.Render("Нет конфигов. R - загрузить с реп"))
		} else {
			content.WriteString(m.cloudList.View())
			idx := m.cloudList.Index()
			if idx >= 0 && idx < len(m.cloudCfgs) {
				parts := []string{}
				if m.cloudGeo != nil {
					if g, ok := m.cloudGeo[cloudHost(m.cloudCfgs[idx].Server)]; ok {
						if g.CountryCode != "" {
							parts = append(parts, g.CountryCode)
						}
						if g.ASN != "" {
							parts = append(parts, g.ASN)
						}
						if g.Org != "" {
							parts = append(parts, g.Org)
						}
					}
				}
				ipv6Ok, hasProbe := false, false
				if m.cloudIPv6 != nil {
					ipv6Ok = m.cloudIPv6[m.cloudNames[idx]]
					hasProbe = true
				}
				if _, probed := m.pterovpnRes[m.cloudNames[idx]]; probed {
					hasProbe = true
				}
				if hasProbe {
					if ipv6Ok {
						parts = append(parts, "IPv6: ✓")
					} else {
						parts = append(parts, "IPv6: ✗")
					}
				} else {
					parts = append(parts, "IPv6: —")
				}
				if len(parts) > 0 {
					content.WriteString(cloudDetailStyle.Render(strings.Join(parts, " · ")))
				}
			}
		}
	case tabLogs:
		m.logsMu.Lock()
		var logLines strings.Builder
		for _, line := range m.logs {
			line = strings.TrimRight(line, "\r\n")
			payload := clientlog.LinePayload(line)
			tag := clientlog.InferTag(line)
			var styled string
			switch tag {
			case "OK":
				styled = logOKStyle.Render(payload)
			case "TRAFFIC":
				styled = logTrafficStyle.Render(payload)
			case "DROP":
				styled = logDropStyle.Render("▼ " + payload)
			case "DPI":
				styled = logDPIStyle.Render("◆ " + payload)
			case "ERR":
				styled = logErrStyle.Render("✗ " + payload)
			case "WARN":
				styled = logWarnStyle.Render("⚠ " + payload)
			default:
				styled = logLineStyle.Render(payload)
			}
			logLines.WriteString(styled)
			logLines.WriteString("\n")
		}
		logStr := logLines.String()
		m.logsMu.Unlock()
		m.logViewport.SetContent(logStr)
		if m.logAutoScroll {
			m.logViewport.GotoBottom()
		}
		content.WriteString(m.logViewport.View())
	case tabMesh:
		content.WriteString(m.meshViewport.View())
	case tabCluster:
		content.WriteString(m.clusterViewport.View())
	case tabProtection:
		content.WriteString(m.protectionView())
	case tabSettings:
		if m.settingsEditing && len(m.settingsInputs) == 3 {
			content.WriteString(sectionTitle.Render("Режим подключения") + "\n\n")
			labels := []string{"Режим (tun|proxy):", "Прокси (addr:port):", "System proxy (Windows):"}
			for i := range m.settingsInputs {
				content.WriteString(labels[i] + " ")
				content.WriteString(m.settingsInputs[i].View())
				content.WriteString("\n")
			}
			content.WriteString("\n" + hintKey.Render("Tab/Enter") + hintText.Render(" сохранить  ") + hintKey.Render("Esc") + hintText.Render(" отмена"))
		} else {
			content.WriteString(sectionTitle.Render("Утилиты") + "\n\n")
			if m.activeCfg != "" {
				idx := -1
				for i, n := range m.names {
					if n == m.activeCfg {
						idx = i
						break
					}
				}
				if idx >= 0 && idx < len(m.cfgs) {
					content.WriteString("Активный профиль: " + m.activeCfg + "  →  " + m.cfgs[idx].Server + "\n\n")
				} else {
					for i, n := range m.cloudNames {
						if n == m.activeCfg && i < len(m.cloudCfgs) {
							content.WriteString("Активный профиль: " + m.activeCfg + "  →  " + m.cloudCfgs[i].Server + "\n\n")
							break
						}
					}
				}
				content.WriteString("Полный JSON профиля лежит в ~/.config/volter/<имя>.json — правь relay/mesh во вкладке «Mesh».\n\n")
			}
			content.WriteString("Режим: ")
			if m.clientSettings.Mode == "proxy" {
				content.WriteString(statusStyle.Render("Proxy") + " (" + m.clientSettings.ProxyListen + ")")
				if m.clientSettings.SystemProxy {
					content.WriteString("  System proxy: вкл")
				}
			} else {
				content.WriteString(statusStyle.Render("TUN"))
			}
			content.WriteString("\n")
			content.WriteString("E - редактировать режим\n\n")
			content.WriteString("B - тест всех конфигов (ping)\n")
			content.WriteString("q/Esc - выход\n")
		}
	}

	b.WriteString(contentBox.Render(content.String()))
	b.WriteString("\n\n")
	footer := hintKey.Render("Tab/Shift+Tab") + hintText.Render(" вкладки  ") + hintKey.Render("q/Esc") + hintText.Render(" выход  ") + hintKey.Render("Enter") + hintText.Render(" подключиться/отключиться")
	if m.tab == tabConfig && !m.adding && !m.editing && m.deletingCfg == "" {
		footer += hintText.Render("  ") + hintKey.Render("↑/↓") + hintText.Render(" выбор  ") + hintKey.Render("N") + hintText.Render(" добавить  ") + hintKey.Render("P") + hintText.Render(" ping  ") + hintKey.Render("T") + hintText.Render(" volter  ") + hintKey.Render("E") + hintText.Render(" ред.  ") + hintKey.Render("D") + hintText.Render(" удалить")
	}
	if m.tab == tabCloud && !m.cloudLoading {
		footer += hintText.Render("  ") + hintKey.Render("↑/↓") + hintText.Render(" выбор  ") + hintKey.Render("P") + hintText.Render(" ping  ") + hintKey.Render("T") + hintText.Render(" volter  ") + hintKey.Render("E") + hintText.Render(" ред.  ") + hintKey.Render("R") + hintText.Render(" обновить")
	}
	if m.tab == tabMesh {
		footer += hintText.Render("  ") + hintKey.Render("E") + hintText.Render(" relay/mesh  ") + hintKey.Render("K") + hintText.Render(" export ticket  ") + hintKey.Render("I") + hintText.Render(" import ticket(file)  ") + hintKey.Render("X") + hintText.Render(" self-test stun/relay  ") + hintKey.Render("Ctrl+←/→") + hintText.Render(" цель  ") + hintKey.Render("↑/↓ PgUp/PgDn") + hintText.Render(" прокрутка  ") + hintText.Render("(~2s)")
	}
	if m.tab == tabCluster {
		footer += hintText.Render("  ") + hintKey.Render("↑/↓ PgUp/PgDn") + hintText.Render(" прокрутка  ") + hintText.Render("(~2s)")
	}
	if m.tab == tabProtection {
		footer += hintText.Render("  ") + hintKey.Render("E") + hintText.Render(" редактировать  ") + hintKey.Render("1/2/3") + hintText.Render(" баланс/усил/авто  ") + hintKey.Render("Ctrl+←/→") + hintText.Render(" цель  ") + hintKey.Render("↑/↓ PgUp/PgDn") + hintText.Render(" прокрутка")
	}
	if m.tab == tabSettings {
		footer += hintText.Render("  ") + hintKey.Render("E") + hintText.Render(" режим TUN/Proxy  ") + hintKey.Render("B") + hintText.Render(" тест всех конфигов")
	}
	b.WriteString(footerStyle.Render(footer))
	return b.String()
}

func (m *Model) exportPeerTicket() (string, string, error) {
	name := m.meshRelayEffectiveTarget()
	if name == "" {
		return "", "", fmt.Errorf("нет локального профиля для ticket")
	}
	cfg, err := config.LoadByName(name)
	if err != nil {
		return "", "", err
	}
	t, err := peerTicketFromConfig(cfg, 24*time.Hour)
	if err != nil {
		return "", "", err
	}
	if err := config.UpsertPeerTicket(t); err != nil {
		return "", "", err
	}
	uri := config.BuildPeerTicketURI(t)
	p := peerTicketExchangePath("peer-ticket-export.txt")
	if err := os.WriteFile(p, []byte(uri+"\n"), 0o600); err != nil {
		return "", "", err
	}
	return uri, p, nil
}

func peerTicketFromConfig(cfg config.Config, ttl time.Duration) (config.PeerTicket, error) {
	relay := config.EffectiveRelayOptions(&cfg)
	if relay == nil {
		return config.PeerTicket{}, fmt.Errorf("peer ticket requires mesh relay config")
	}
	peerID := strings.TrimSpace(relay.PeerID)
	pubKey := strings.TrimSpace(relay.BootstrapPubKey)
	advertise := strings.TrimSpace(relay.PeerRelayUDPAdvertise)
	listen := strings.TrimSpace(relay.PeerRelayUDPListen)
	switch {
	case peerID == "":
		return config.PeerTicket{}, fmt.Errorf("peer ticket requires peerId")
	case pubKey == "":
		return config.PeerTicket{}, fmt.Errorf("peer ticket requires bootstrapPubKey")
	case advertise == "" && listen == "":
		return config.PeerTicket{}, fmt.Errorf("peer ticket requires peer UDP endpoint")
	}
	var addrs []string
	if advertise != "" {
		addrs = append(addrs, advertise)
	}
	if listen != "" {
		addrs = append(addrs, listen)
	}
	return config.CreatePeerTicket(peerID, pubKey, addrs, ttl), nil
}

func (m *Model) importPeerTicket() (string, error) {
	p := peerTicketExchangePath("peer-ticket-import.txt")
	data, err := os.ReadFile(p)
	if err != nil {
		return p, err
	}
	raw := strings.TrimSpace(string(data))
	if raw == "" {
		return p, fmt.Errorf("файл ticket пуст: %s", p)
	}
	t, ok := config.ParsePeerTicketURI(raw)
	if !ok {
		return p, fmt.Errorf("некорректный peer ticket")
	}
	if err := config.UpsertPeerTicket(t); err != nil {
		return p, err
	}
	return p, nil
}

func peerTicketExchangePath(name string) string {
	base, err := os.UserConfigDir()
	if err != nil {
		return name
	}
	dir := filepath.Join(base, "volter")
	_ = os.MkdirAll(dir, 0o700)
	return filepath.Join(dir, name)
}
