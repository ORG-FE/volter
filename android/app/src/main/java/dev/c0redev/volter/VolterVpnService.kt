package dev.c0redev.volter

import android.net.VpnService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.IBinder
import android.system.Os
import androidx.core.app.ServiceCompat
import dev.c0redev.volter.core.CoreBridge
import dev.c0redev.volter.domain.model.ClientSettings
import dev.c0redev.volter.domain.model.Config
import dev.c0redev.volter.traffic.VpnTrafficRecorder
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val NOTIF_CHANNEL_ID = "volter_vpn"
const val ACTION_CORE_SESSION = "dev.c0redev.volter.ACTION_CORE_SESSION"
const val EXTRA_CORE_HANDLE = "core_handle"
const val EXTRA_CORE_MODE = "core_mode"
const val EXTRA_CORE_ERROR = "core_error"
const val EXTRA_CORE_SOCKS_LISTEN = "core_socks_listen"

class VolterVpnService : VpnService() {
    private var coreHandle: Long = -1
    private var tunFd: Int = -1
    private var tunPfd: ParcelFileDescriptor? = null
    private val sessionAbort = AtomicBoolean(false)
    private val sessionGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private var trafficWallStartMs: Long = 0L
    private var activeVpnMode: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            VolterLog.i("onStartCommand ACTION_STOP stopSelf")
            sessionAbort.set(true)
            sessionGeneration.incrementAndGet()
            stopActive()
            stopSelf()
            return START_NOT_STICKY
        }

        val modeRaw = intent?.getStringExtra(EXTRA_SETTINGS_JSON)
        val cfgJson = intent?.getStringExtra(EXTRA_CFG_JSON)
        val settingsJson = modeRaw ?: "{}"
        val configDir = intent?.getStringExtra(EXTRA_CONFIG_DIR) ?: File(filesDir, "volter").absolutePath

        VolterLog.i("onStartCommand flags=$flags startId=$startId cfgLen=${cfgJson?.length ?: 0} settingsLen=${settingsJson.length} configDir=$configDir")

        if (cfgJson.isNullOrBlank()) {
            VolterLog.w("onStartCommand empty cfg, stopSelf")
            stopSelf()
            return START_NOT_STICKY
        }

        val settings = runCatching {
            ClientSettings.fromJson(JSONObject(settingsJson))
        }.getOrElse { ClientSettings() }

        val cfg = runCatching {
            Config.fromJson(JSONObject(cfgJson))
        }.getOrElse {
            VolterLog.e("onStartCommand bad cfg json", it)
            stopSelf()
            return START_NOT_STICKY
        }

        VolterLog.i("stopActive before new session mode=${settings.mode}")
        stopActive()
        sessionAbort.set(false)
        val generation = sessionGeneration.incrementAndGet()

        ensureForeground("starting")

        thread(name = "volter-thread") {
            runCatching {
                VolterLog.i("worker start mode=${settings.mode}")
                val standaloneDpi = cfg.protection?.standaloneDpiOnly == true
                val effective = if (standaloneDpi || cfg.protection?.clusterPreferredServer?.isNotBlank() == true) cfg else configAfterTcpOnlyProbe(cfg)
                if (!isActiveGeneration(generation)) {
                    VolterLog.i("worker stale before start generation=$generation")
                    return@runCatching
                }
                if (settings.mode == "proxy" && effective.mesh.enabled) {
                    throw IllegalStateException("Mesh requires TUN mode, disable mesh or switch from proxy mode")
                }
                when {
                    standaloneDpi -> startStandaloneDpiInternal(effective, configDir, generation)
                    settings.mode == "proxy" -> startProxyInternal(effective, settings, configDir, generation)
                    else -> startTunInternal(effective, settings, configDir, generation)
                }
            }.onFailure { e ->
                if (!isActiveGeneration(generation)) return@onFailure
                VolterLog.e("worker failed", e)
                ensureForeground("error")
                broadcastSessionError(e.message ?: e.toString())
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        sessionGeneration.incrementAndGet()
        stopActive()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isActiveGeneration(generation: Long): Boolean {
        return generation == sessionGeneration.get() && !sessionAbort.get()
    }

    private fun closeRawFd(fd: Int) {
        if (fd < 0) return
        runCatching {
            val desc = FileDescriptor()
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.setInt(desc, fd)
            Os.close(desc)
        }.onFailure { VolterLog.w("closeRawFd failed fd=$fd: ${it.message}") }
    }

    private fun ensureForeground(contentText: CharSequence) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            mgr.createNotificationChannel(ch)
        }

        val notif = Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                1,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(1, notif)
        }
    }

    private fun configAfterTcpOnlyProbe(cfg: Config): Config {
        val probe = CoreBridge.probeVolter(cfg.server, cfg.token, 12_000)
        if (probe.error != null) {
            VolterLog.w("configAfterTcpOnlyProbe probe err=${probe.error}, cfg unchanged")
            return cfg
        }
        if (!probe.ok) {
            VolterLog.w("configAfterTcpOnlyProbe probe not ok, cfg unchanged")
            return cfg
        }
        val noQuic = probe.capsNoQuic ?: return cfg
        return if (noQuic) {
            VolterLog.i("configAfterTcpOnlyProbe caps without QUIC -> tcp, quicServer cleared")
            cfg.copy(transport = "tcp", quicServer = null)
        } else {
            cfg
        }
    }

    private fun stopActive() {
        flushTrafficSnapshot()
        CoreSocketProtect.clear()
        val handle = coreHandle
        VolterLog.i("stopActive prevCoreHandle=$handle tunFd=$tunFd")
        if (handle > 0) {
            runCatching { CoreBridge.stop(handle) }
                .onFailure { VolterLog.e("Core.stop failed", it) }
            waitCoreStopped(handle)
        } else if (tunFd >= 0) {
            closeRawFd(tunFd)
        }
        coreHandle = -1

        tunFd = -1

        tunPfd?.let {
            runCatching { it.close() }
        }
        tunPfd = null

        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun waitCoreStopped(handle: Long) {
        if (handle <= 0) return
        repeat(20) {
            val state = runCatching { CoreBridge.pollState(handle) }.getOrNull()
            if (state == null || !state.running) return
            Thread.sleep(50)
        }
        VolterLog.w("Core.stop wait timeout handle=$handle")
    }

    private fun flushTrafficSnapshot() {
        val start = trafficWallStartMs
        if (start <= 0L) return
        val end = System.currentTimeMillis()
        val mode = activeVpnMode ?: "tun"
        trafficWallStartMs = 0L
        activeVpnMode = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { VpnTrafficRecorder.writePending(this, start, end, mode) }
                .onFailure { VolterLog.w("flushTrafficSnapshot failed: ${it.message}") }
        }
    }

    private fun startStandaloneDpiInternal(cfg: Config, configDir: String, generation: Long) {
        CoreSocketProtect.install(this)
        val cfgJson = cfg.toJson().toString()
        VolterLog.i("startStandaloneDpi")
        val res = CoreBridge.startStandaloneDpi(cfgJson, configDir)
        if (res.error != null) throw IllegalStateException(res.error)
        if (!isActiveGeneration(generation)) {
            runCatching { CoreBridge.stop(res.handle) }
            return
        }
        coreHandle = res.handle
        if (sessionAbort.get()) {
            VolterLog.i("startStandaloneDpi aborted after handle=$coreHandle")
            runCatching { CoreBridge.stop(coreHandle) }
            coreHandle = -1
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }
        VolterLog.i("startStandaloneDpi ok handle=$coreHandle socks=${res.socksListen}")
        trafficWallStartMs = System.currentTimeMillis()
        activeVpnMode = "dpi_standalone"
        broadcastSession(coreHandle, "dpi_standalone", res.socksListen)
        val fgText = res.socksListen?.takeIf { it.isNotBlank() }?.let { socks ->
            getString(R.string.notif_fg_standalone_socks, socks)
        } ?: getString(R.string.notif_fg_standalone)
        ensureForeground(fgText)
    }

    private fun startProxyInternal(cfg: Config, settings: ClientSettings, configDir: String, generation: Long) {
        CoreSocketProtect.install(this)
        val cfgJson = cfg.toJson().toString()
        val listenAddr = settings.proxyListen
        VolterLog.i("startProxy listen=$listenAddr")
        val res = CoreBridge.startProxy(listenAddr, cfgJson, configDir)
        if (res.error != null) throw IllegalStateException(res.error)
        if (!isActiveGeneration(generation)) {
            runCatching { CoreBridge.stop(res.handle) }
            return
        }
        coreHandle = res.handle
        if (sessionAbort.get()) {
            VolterLog.i("startProxy aborted after handle=$coreHandle")
            runCatching { CoreBridge.stop(coreHandle) }
            coreHandle = -1
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }
        VolterLog.i("startProxy ok handle=$coreHandle")
        trafficWallStartMs = System.currentTimeMillis()
        activeVpnMode = "proxy"
        broadcastSession(coreHandle, "proxy")
        ensureForeground("proxy")
    }

    private fun startTunInternal(cfg: Config, settings: ClientSettings, configDir: String, generation: Long) {
        CoreSocketProtect.install(this)
        val mtu = 1380
        VolterLog.i("startTunInternal mtu=$mtu ipv6Tunnel=${settings.ipv6Tunnel} server=${cfg.server}")

        val builder = Builder()
        builder.setSession("volter")
        builder.setMtu(mtu)
        val splitApps = settings.splitTunnelApps.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val splitMode = ClientSettings.normalizedSplitTunnelMode(settings.splitTunnelMode)
        if (splitMode == ClientSettings.SPLIT_ONLY && splitApps.isNotEmpty()) {
            splitApps.forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }
                    .onFailure { e -> if (e !is PackageManager.NameNotFoundException) VolterLog.w("VpnBuilder addAllowedApplication failed pkg=$pkg: ${e.message}") }
            }
            VolterLog.i("VpnBuilder split only apps=${splitApps.size}")
        } else if (splitMode == ClientSettings.SPLIT_BYPASS) {
            splitApps.forEach { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onFailure { e -> if (e !is PackageManager.NameNotFoundException) VolterLog.w("VpnBuilder addDisallowedApplication failed pkg=$pkg: ${e.message}") }
            }
            VolterLog.i("VpnBuilder split bypass apps=${splitApps.size}")
        }

        if (splitMode != ClientSettings.SPLIT_ONLY) {
            runCatching { builder.addDisallowedApplication(packageName) }
                .onSuccess { VolterLog.i("VpnBuilder addDisallowedApplication ok package=$packageName") }
                .onFailure { e ->
                    if (e !is PackageManager.NameNotFoundException) {
                        VolterLog.w("VpnBuilder addDisallowedApplication failed: ${e.message}")
                    }
                }
        }

        builder.addAddress("10.13.37.2", 24)

        val tun6 = cfg.tunCIDR6?.takeIf { it.isNotBlank() && settings.ipv6Tunnel }
        if (tun6 != null) {
            val (ip, pfx) = parseCIDR(tun6) ?: error("bad tunCIDR6")
            builder.addAddress(ip, pfx)
        }

        builder.addDnsServer("1.1.1.1")
        builder.addDnsServer("8.8.8.8")
        if (tun6 != null) {
            builder.addDnsServer("2606:4700:4700::1111")
        }

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
        builder.setMetered(false)

        val routeCidrs = parseCsvCidrs(cfg.routes)
        if (routeCidrs.isEmpty()) {
            builder.addRoute("0.0.0.0", 0)
            if (tun6 != null) builder.addRoute("::", 0)
        } else {
            for (c in routeCidrs) {
                val (ip, pfx) = parseCIDR(c) ?: continue
                builder.addRoute(ip, pfx)
            }
        }

        val excludeCidrs = parseCsvCidrs(cfg.exclude)
        for (c in excludeCidrs) {
            val (ip, pfx) = parseCIDR(c) ?: continue
            val inet = InetAddress.getByName(ip)
            builder.excludeRoute(IpPrefix(inet, pfx))
        }

        val (serverHost, _) = parseHostPort(cfg.server) ?: Pair(null, null)
        val serverHostStr = serverHost ?: throw IllegalStateException("bad server:port")

        val serverIPs = resolveHostIPsPreferV4(serverHostStr)
        for (ip in serverIPs) {
            val pfx = if (ip is Inet4Address) 32 else 128
            builder.excludeRoute(IpPrefix(ip, pfx))
        }

        val relayHosts = collectRelayBypassHosts(cfg)
        for (h in relayHosts) {
            runCatching { resolveHostIPsPreferV4(h) }
                .getOrDefault(emptyList())
                .forEach { ip ->
                    val pfx = if (ip is Inet4Address) 32 else 128
                    builder.excludeRoute(IpPrefix(ip, pfx))
                }
        }

        val quicUsed = isQuicUsed(cfg.transport, cfg.quicServer)
        if (quicUsed) {
            val quicServer = cfg.quicServer ?: ""
            val res = CoreBridge.quicDialTargetIPs(cfg.server, quicServer)
            if (res.error != null) throw IllegalStateException(res.error)
            for (ipStr in res.ips) {
                val inet = InetAddress.getByName(ipStr)
                val pfx = if (inet is Inet4Address) 32 else 128
                builder.excludeRoute(IpPrefix(inet, pfx))
            }
        }

        if (!isActiveGeneration(generation)) {
            VolterLog.i("startTunInternal aborted before establish")
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }

        val pfd = builder.establish() ?: error("VpnService.establish returned null")
        tunPfd = pfd
        val fd = pfd.detachFd()
        pfd.close()
        tunFd = fd
        VolterLog.i("TUN established fd=$fd mtu=$mtu")
        if (!isActiveGeneration(generation)) {
            VolterLog.i("startTun aborted after establish fd=$fd")
            closeRawFd(fd)
            tunFd = -1
            tunPfd = null
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }

        val cfgJson = cfg.toJson().toString()
        val res = CoreBridge.startTun(fd, mtu, cfgJson, configDir)
        if (res.error != null) {
            closeRawFd(fd)
            tunFd = -1
            tunPfd = null
            throw IllegalStateException(res.error)
        }
        if (!isActiveGeneration(generation)) {
            runCatching { CoreBridge.stop(res.handle) }
            coreHandle = -1
            tunFd = -1
            tunPfd = null
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }
        coreHandle = res.handle
        if (!isActiveGeneration(generation)) {
            VolterLog.i("startTun aborted after core start handle=$coreHandle")
            runCatching { CoreBridge.stop(coreHandle) }
            coreHandle = -1
            tunFd = -1
            tunPfd = null
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }
        VolterLog.i("startTun core ok handle=$coreHandle")
        trafficWallStartMs = System.currentTimeMillis()
        activeVpnMode = "tun"
        broadcastSession(coreHandle, "tun")
        ensureForeground("connected")
    }

    private fun isQuicUsed(transport: String?, quicServer: String?): Boolean {
        val tr = transport?.trim()?.lowercase().orEmpty()
        return when (tr) {
            "tcp" -> false
            "quic" -> true
            else -> !quicServer.isNullOrBlank()
        }
    }

    private fun parseCsvCidrs(raw: String?): List<String> {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return emptyList()
        return s.split(',').map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun parseCIDR(cidr: String): Pair<String, Int>? {
        val idx = cidr.indexOf('/')
        if (idx <= 0 || idx == cidr.lastIndex) return null
        val ip = cidr.substring(0, idx).trim()
        val pfx = cidr.substring(idx + 1).trim().toIntOrNull() ?: return null
        return ip to pfx
    }

    private fun parseHostPort(server: String): Pair<String?, Int?>? {
        val s = server.trim()
        if (s.isEmpty()) return null

        return try {
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 0) return null
                val host = s.substring(1, end)
                val rest = s.substring(end + 1)
                if (!rest.startsWith(":")) return null
                val port = rest.substring(1).toIntOrNull() ?: return null
                Pair(host, port)
            } else {
                val idx = s.lastIndexOf(':')
                if (idx <= 0 || idx == s.lastIndex) return null
                val host = s.substring(0, idx)
                val port = s.substring(idx + 1).toIntOrNull() ?: return null
                Pair(host, port)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveHostIPsPreferV4(host: String): List<InetAddress> {
        val all = InetAddress.getAllByName(host).toList()
        val v4 = all.filterIsInstance<Inet4Address>()
        return if (v4.isNotEmpty()) v4 else all
    }

    private fun collectRelayBypassHosts(cfg: Config): Set<String> {
        val out = linkedSetOf<String>()

        cfg.protection?.clusterPreferredServer?.let { hp ->
            parseHostPort(hp)?.first?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        val mesh = cfg.mesh
        if (mesh.enabled) {
            mesh.discovery.dhtRpcSeedPeers.orEmpty().forEach { hp ->
                parseHostPort(hp)?.first?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            }
            if (mesh.stun.enabled) {
                mesh.stun.servers.orEmpty().forEach { spec ->
                    parseStunHost(spec)?.let(out::add)
                }
            }
            mesh.serverRelay.discoveryUrl?.let { parseUrlHost(it)?.let(out::add) }
            mesh.discovery.dhtFindUrls.orEmpty().forEach { u -> parseUrlHost(u)?.let(out::add) }
            mesh.discovery.gossipPeers.orEmpty().forEach { u -> parseUrlHost(u)?.let(out::add) }
            return out
        }
        val relay = cfg.relay ?: return out
        relay.dhtRpcSeedPeers.orEmpty().forEach { hp ->
            parseHostPort(hp)?.first?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        relay.stunServers.orEmpty().forEach { spec ->
            parseStunHost(spec)?.let(out::add)
        }
        relay.turnUrls.orEmpty().forEach { spec ->
            parseUrlHost(spec)?.let(out::add)
        }
        relay.discoveryURL?.let { parseUrlHost(it)?.let(out::add) }
        relay.dhtFindUrls.orEmpty().forEach { u -> parseUrlHost(u)?.let(out::add) }
        relay.gossipPeers.orEmpty().forEach { u -> parseUrlHost(u)?.let(out::add) }
        return out
    }

    private fun parseStunHost(spec: String): String? {
        val s = spec.trim()
        if (s.isEmpty()) return null
        val clean = s.removePrefix("udp://").removePrefix("tcp://")
        return parseHostPort(clean)?.first?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseUrlHost(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return runCatching {
            val normalized = when {
                s.contains("://") -> s
                else -> "http://$s"
            }
            URI(normalized).host?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun broadcastSession(handle: Long, mode: String, socksListen: String? = null) {
        VolterLog.i("broadcastSession handle=$handle mode=$mode socks=${socksListen ?: "null"}")
        val i = Intent(ACTION_CORE_SESSION).apply {
            setPackage(packageName)
            putExtra(EXTRA_CORE_HANDLE, handle)
            putExtra(EXTRA_CORE_MODE, mode)
            if (!socksListen.isNullOrBlank()) putExtra(EXTRA_CORE_SOCKS_LISTEN, socksListen)
        }
        sendBroadcast(i)
    }

    private fun broadcastSessionError(message: String) {
        VolterLog.e("broadcastSessionError $message")
        val i = Intent(ACTION_CORE_SESSION).apply {
            setPackage(packageName)
            putExtra(EXTRA_CORE_ERROR, message)
        }
        sendBroadcast(i)
    }

    companion object {
        const val ACTION_STOP = "dev.c0redev.volter.ACTION_STOP_VPN"
        const val EXTRA_CFG_JSON = "cfg_json"
        const val EXTRA_SETTINGS_JSON = "settings_json"
        const val EXTRA_CONFIG_DIR = "config_dir"

        fun stopIntent(context: Context): Intent =
            Intent(context, VolterVpnService::class.java).apply { action = ACTION_STOP }

        fun newIntent(
            context: Context,
            cfgJson: String,
            settingsJson: String,
            configDir: String,
        ): Intent {
            return Intent(context, VolterVpnService::class.java).apply {
                putExtra(EXTRA_CFG_JSON, cfgJson)
                putExtra(EXTRA_SETTINGS_JSON, settingsJson)
                putExtra(EXTRA_CONFIG_DIR, configDir)
            }
        }
    }
}
