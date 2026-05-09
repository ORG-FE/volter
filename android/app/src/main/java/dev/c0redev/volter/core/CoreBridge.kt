package dev.c0redev.volter.core

import core.Core
import dev.c0redev.volter.VolterLog
import org.json.JSONArray
import org.json.JSONObject

object CoreBridge {
    data class StartResult(
        val handle: Long,
        val error: String?,
        val socksListen: String? = null,
    )

    data class State(
        val ready: Boolean,
        val running: Boolean,
        val error: String?,
        val watchdog: Boolean = false,
        val socksListen: String? = null,
    )

    data class ProbeResult(
        val ok: Boolean,
        val ipv6: Boolean,
        val mode: String,
        val leafPin: String,
        val error: String?,
        val capsNoQuic: Boolean? = null,
    )

    data class PingResult(
        val rttMs: Long,
        val error: String?,
    )

    data class QuicIPsResult(
        val ips: List<String>,
        val error: String?,
    )

    data class MeshSelfTestResult(
        val ok: Boolean,
        val serverReachable: Boolean,
        val serverMode: String,
        val serverRelay: Boolean,
        val peerRelayReady: Boolean,
        val stunOk: Boolean,
        val stunSrflx: String,
        val warnings: List<String>,
        val error: String?,
    )

    fun startTun(tunFd: Int, mtu: Int, cfgJson: String, configDir: String): StartResult {
        VolterLog.i("Core.startTun fd=$tunFd mtu=$mtu cfgBytes=${cfgJson.length} configDir=$configDir")
        val raw = Core.startTun(tunFd.toLong(), mtu.toLong(), cfgJson, configDir)
        val j = JSONObject(raw)
        val handle = j.optLong("handle", 0L)
        val err = nullableErr(j, "error")
        val socks = nullableOpt(j, "socksListen")
        VolterLog.i("Core.startTun -> handle=$handle err=${err ?: "null"} socks=${socks ?: "null"}")
        return StartResult(handle = handle, error = err, socksListen = socks)
    }

    fun stop(handle: Long): Boolean {
        VolterLog.i("Core.stop handle=$handle")
        val ok = Core.stop(handle)
        VolterLog.i("Core.stop -> $ok")
        return ok
    }

    fun pollState(handle: Long): State {
        val raw = Core.pollState(handle)
        val j = JSONObject(raw)
        val err = nullableErr(j, "error")
        val running = j.optBoolean("running", false)
        val ready = j.optBoolean("ready", false)
        val watchdog = j.optBoolean("watchdog", false)
        val socksListen = nullableOpt(j, "socksListen")
        VolterLog.v("pollState h=$handle ready=$ready running=$running watchdog=$watchdog socks=${socksListen ?: "null"} err=${err ?: "null"}")
        if (!err.isNullOrBlank() && (err.equals("no session", ignoreCase = true) || err.equals("bad handle", ignoreCase = true))) {
            VolterLog.w("pollState missing session h=$handle (stale handle or core already stopped)")
        }
        return State(ready = ready, running = running, error = err, watchdog = watchdog, socksListen = socksListen)
    }

    fun pollLogs(handle: Long, max: Int = 200): List<String> {
        val raw = Core.pollLogs(handle, max.toLong())
        val arr = JSONArray(raw)
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        if (out.isNotEmpty()) {
            VolterLog.i("pollLogs h=$handle n=${out.size}")
            for (line in out.take(60)) {
                VolterLog.i("core| $line")
            }
        }
        return out
    }

    fun startProxy(listenAddr: String, cfgJson: String, configDir: String): StartResult {
        VolterLog.i("Core.startProxy listen=$listenAddr cfgBytes=${cfgJson.length}")
        val raw = Core.startProxy(listenAddr, cfgJson, configDir)
        val j = JSONObject(raw)
        val handle = j.optLong("handle", 0L)
        val err = nullableErr(j, "error")
        val socks = nullableOpt(j, "socksListen")
        VolterLog.i("Core.startProxy -> handle=$handle err=${err ?: "null"} socks=${socks ?: "null"}")
        return StartResult(handle = handle, error = err, socksListen = socks)
    }

    fun startStandaloneDpi(cfgJson: String, configDir: String): StartResult {
        VolterLog.i("Core.startStandaloneDpi cfgBytes=${cfgJson.length} configDir=$configDir")
        val raw = Core.startStandaloneDpi(cfgJson, configDir)
        val j = JSONObject(raw)
        val handle = j.optLong("handle", 0L)
        val err = nullableErr(j, "error")
        val socks = nullableOpt(j, "socksListen")
        VolterLog.i("Core.startStandaloneDpi -> handle=$handle err=${err ?: "null"} socks=${socks ?: "null"}")
        return StartResult(handle = handle, error = err, socksListen = socks)
    }

    fun probeVolter(server: String, token: String, timeoutMs: Long): ProbeResult {
        VolterLog.i("probeVolter server=$server tokenLen=${token.length} timeout=$timeoutMs")
        val raw = Core.probeVolter(server, token, timeoutMs)
        val j = JSONObject(raw)
        val capsNoQuic = if (j.has("capsNoQuic")) j.optBoolean("capsNoQuic") else null
        val res = ProbeResult(
            ok = j.optBoolean("ok", false),
            ipv6 = j.optBoolean("ipv6", false),
            mode = j.optString("mode", ""),
            leafPin = j.optString("leafPin", ""),
            error = nullableErr(j, "error"),
            capsNoQuic = capsNoQuic,
        )
        VolterLog.i("probeVolter -> ok=${res.ok} mode=${res.mode} capsNoQuic=$capsNoQuic err=${res.error ?: "null"}")
        return res
    }

    fun ping(server: String, timeoutMs: Long): PingResult {
        VolterLog.v("ping server=$server timeout=$timeoutMs")
        val raw = Core.ping(server, timeoutMs)
        val j = JSONObject(raw)
        val res = PingResult(
            rttMs = j.optLong("rttMs", 0L),
            error = nullableErr(j, "error"),
        )
        VolterLog.v("ping -> rttMs=${res.rttMs} err=${res.error ?: "null"}")
        return res
    }

    fun quicDialTargetIPs(server: String, quicServer: String): QuicIPsResult {
        VolterLog.i("quicDialTargetIPs server=$server quicServer=$quicServer")
        val raw = Core.quicDialTargetIPs(server, quicServer)
        val j = JSONObject(raw)
        val err = nullableErr(j, "error")
        val arr = j.optJSONArray("ips") ?: JSONArray()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        VolterLog.i("quicDialTargetIPs -> n=${out.size} err=${err ?: "null"}")
        return QuicIPsResult(ips = out, error = err)
    }

    fun meshSelfTest(cfgJson: String, timeoutMs: Long): MeshSelfTestResult {
        val raw = try {
            val c = Class.forName("core.Core")
            val m = runCatching {
                c.getMethod("meshSelfTest", String::class.java, Long::class.javaPrimitiveType)
            }.getOrElse {
                runCatching {
                    c.getMethod("meshSelfTest", String::class.java, Int::class.javaPrimitiveType)
                }.getOrElse {
                    c.getMethod("relaySelfTest", String::class.java, Int::class.javaPrimitiveType)
                }
            }
            val arg = if (m.parameterTypes.lastOrNull() == Int::class.javaPrimitiveType) timeoutMs.toInt() else timeoutMs
            m.invoke(null, cfgJson, arg) as String
        } catch (e: Exception) {
            """{"ok":false,"error":"meshSelfTest unavailable: rebuild volter-core.aar","warnings":["${e.message}"]}"""
        }
        val j = JSONObject(raw)
        val warnings = ArrayList<String>()
        val arr = j.optJSONArray("warnings") ?: JSONArray()
        for (i in 0 until arr.length()) warnings.add(arr.optString(i, ""))
        return MeshSelfTestResult(
            ok = j.optBoolean("ok", false),
            serverReachable = j.optBoolean("serverReachable", false),
            serverMode = j.optString("serverMode", ""),
            serverRelay = j.optBoolean("serverRelay", false),
            peerRelayReady = j.optBoolean("peerRelayReady", false),
            stunOk = j.optBoolean("stunOk", false),
            stunSrflx = j.optString("stunSrflx", ""),
            warnings = warnings.filter { it.isNotBlank() },
            error = nullableErr(j, "error"),
        )
    }

    fun meshStatus(): String {
        return try {
            val c = Class.forName("core.Core")
            val m = c.getMethod("meshStatus")
            m.invoke(null) as String
        } catch (e: Exception) {
            VolterLog.w("meshStatus: ${e.message}")
            """{"error":"meshStatus unavailable: rebuild volter-core.aar (gomobile bind) or update native layer","detail":"${e.message}"}"""
        }
    }

    data class ClusterRefreshResult(
        val ok: Boolean,
        val mapOk: Boolean,
        val sessionsOk: Boolean,
        val clientsOk: Boolean,
        val error: String?,
        val serverUsed: String?,
    )

    fun refreshClusterServers(cfgJson: String, configDir: String): ClusterRefreshResult {
        val raw = try {
            val c = Class.forName("core.Core")
            val m = c.getMethod("refreshClusterServers", String::class.java, String::class.java)
            m.invoke(null, cfgJson, configDir) as String
        } catch (e: Exception) {
            VolterLog.w("refreshClusterServers: ${e.message}")
            return ClusterRefreshResult(
                ok = false,
                mapOk = false,
                sessionsOk = false,
                clientsOk = false,
                error = e.message,
                serverUsed = null,
            )
        }
        val j = JSONObject(raw)
        return ClusterRefreshResult(
            ok = j.optBoolean("ok", false),
            mapOk = j.optBoolean("mapOk", false),
            sessionsOk = j.optBoolean("sessionsOk", false),
            clientsOk = j.optBoolean("clientsOk", false),
            error = nullableErr(j, "error"),
            serverUsed = nullableOpt(j, "serverUsed"),
        )
    }

    private fun nullableErr(j: JSONObject, key: String): String? {
        if (!j.has(key) || j.isNull(key)) return null
        val s = j.optString(key, "")
        return s.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun nullableOpt(j: JSONObject, key: String): String? {
        if (!j.has(key) || j.isNull(key)) return null
        val s = j.optString(key, "")
        return s.takeIf { it.isNotEmpty() && it != "null" }
    }
}

