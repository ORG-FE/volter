package dev.c0redev.volter.traffic

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import dev.c0redev.volter.VolterLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TrafficAppRow(
    val uid: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val label: String,
)

data class TrafficPending(
    val rxBytes: Long?,
    val txBytes: Long?,
    val byApp: List<TrafficAppRow>,
    val collectError: String?,
)

object VpnTrafficRecorder {
    private const val FILE_NAME = "volter_traffic_pending.json"

    private fun pendingFile(ctx: Context): File = File(ctx.cacheDir, FILE_NAME)

    @JvmStatic
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @JvmStatic
    fun writePending(ctx: Context, startMs: Long, endMs: Long, mode: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val (pending, err) = collect(ctx, startMs, endMs)
        val j = JSONObject().apply {
            put("v", 1)
            put("mode", mode)
            pending.rxBytes?.let { put("rx", it) }
            pending.txBytes?.let { put("tx", it) }
            err?.let { put("err", it) }
            put(
                "apps",
                JSONArray().apply {
                    pending.byApp.forEach { r ->
                        put(
                            JSONObject().apply {
                                put("uid", r.uid)
                                put("rx", r.rxBytes)
                                put("tx", r.txBytes)
                                put("label", r.label)
                            },
                        )
                    }
                },
            )
        }
        pendingFile(ctx).writeText(j.toString())
    }

    @JvmStatic
    fun consumePending(ctx: Context): TrafficPending? {
        val f = pendingFile(ctx)
        if (!f.exists()) return null
        val raw = runCatching { f.readText() }.getOrNull() ?: return null
        runCatching { f.delete() }.onFailure { VolterLog.w("consumePending delete: ${it.message}") }
        return runCatching { parsePending(ctx, raw) }.getOrElse {
            VolterLog.w("consumePending parse: ${it.message}")
            TrafficPending(null, null, emptyList(), it.message)
        }
    }

    private fun parsePending(ctx: Context, raw: String): TrafficPending {
        val j = JSONObject(raw)
        val rx = if (j.has("rx") && !j.isNull("rx")) j.getLong("rx") else null
        val tx = if (j.has("tx") && !j.isNull("tx")) j.getLong("tx") else null
        val err = j.optString("err", "").takeIf { it.isNotBlank() }
        val arr = j.optJSONArray("apps") ?: JSONArray()
        val pm = ctx.packageManager
        val apps = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uid = o.optInt("uid")
                val label = runCatching {
                    pm.getNameForUid(uid)?.let { n ->
                        pm.getApplicationLabel(pm.getApplicationInfo(n, 0)).toString()
                    } ?: "uid $uid"
                }.getOrDefault("uid $uid")
                add(
                    TrafficAppRow(
                        uid = uid,
                        rxBytes = o.optLong("rx", 0L),
                        txBytes = o.optLong("tx", 0L),
                        label = label,
                    ),
                )
            }
        }
        return TrafficPending(rx, tx, apps, err)
    }


    @RequiresApi(Build.VERSION_CODES.M)
    @Suppress("DEPRECATION")
    private fun accumulateDetails(
        nsm: NetworkStatsManager,
        networkType: Int,
        startMs: Long,
        endMs: Long,
        byUid: MutableMap<Int, LongArray>,
    ): String? {
        val stats = runCatching { nsm.queryDetails(networkType, null, startMs, endMs) }
            .getOrElse { return it.message ?: "queryDetails threw" }
            ?: return "null"
        return runCatching {
            stats.use { s ->
                val b = NetworkStats.Bucket()
                while (s.hasNextBucket()) {
                    if (!s.getNextBucket(b)) break
                    val uid = b.uid
                    if (uid == NetworkStats.Bucket.UID_REMOVED || uid == NetworkStats.Bucket.UID_TETHERING) continue
                    val arr = byUid.getOrPut(uid) { longArrayOf(0L, 0L) }
                    arr[0] += b.rxBytes
                    arr[1] += b.txBytes
                }
            }
            null
        }.getOrElse { it.message ?: "bucket read failed" }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @Suppress("DEPRECATION")
    private fun querySummaryTotals(
        nsm: NetworkStatsManager,
        startMs: Long,
        endMs: Long,
    ): Pair<Long, Long> {
        var rx = 0L
        var tx = 0L
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            runCatching {
                val b = nsm.querySummaryForDevice(type, null, startMs, endMs) ?: return@runCatching
                rx += b.rxBytes
                tx += b.txBytes
            }
        }
        return rx to tx
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun collect(ctx: Context, startMs: Long, endMs: Long): Pair<TrafficPending, String?> {
        return runCatching {
            val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            if (nsm == null) {
                val msg = "NetworkStatsManager unavailable"
                return@runCatching TrafficPending(
                    rxBytes = null,
                    txBytes = null,
                    byApp = emptyList(),
                    collectError = msg,
                ) to msg
            }

            if (!hasUsageAccess(ctx)) {
                val msg = "Usage Access not granted"
                return@runCatching TrafficPending(
                    rxBytes = null,
                    txBytes = null,
                    byApp = emptyList(),
                    collectError = msg,
                ) to msg

            }

            val byUid = mutableMapOf<Int, LongArray>()
            @Suppress("DEPRECATION")
            val types = intArrayOf(
                ConnectivityManager.TYPE_VPN,
                ConnectivityManager.TYPE_WIFI,
                ConnectivityManager.TYPE_MOBILE,
            )
            var queriedOk = false
            val errs = mutableListOf<String>()
            for (type in types) {
                val r = accumulateDetails(nsm, type, startMs, endMs, byUid)
                if (r == null) {
                    queriedOk = true
                } else {
                    errs += "type$type:$r"
                }
            }

            var rxTotal = byUid.values.sumOf { it[0] }
            var txTotal = byUid.values.sumOf { it[1] }

            // если детализация недоступна, хотя бы суммарные по устройству за окно
            if (rxTotal <= 0L && txTotal <= 0L) {
                val (sumRx, sumTx) = querySummaryTotals(nsm, startMs, endMs)
                rxTotal = sumRx
                txTotal = sumTx
            }

            val collectErr = when {
                byUid.isNotEmpty() -> null
                rxTotal > 0L || txTotal > 0L -> null
                !queriedOk && errs.isNotEmpty() -> "NetworkStats unavailable (${errs.joinToString(",")})"
                else -> null
            }

            val pm = ctx.packageManager
            val rows = byUid.entries
                .asSequence()
                .filter { it.value[0] > 0L || it.value[1] > 0L }
                .sortedByDescending { it.value[0] + it.value[1] }
                .take(32)
                .map { (uid, v) ->
                    val label = runCatching {
                        pm.getNameForUid(uid)?.let { n ->
                            pm.getApplicationLabel(pm.getApplicationInfo(n, 0)).toString()
                        } ?: "uid $uid"
                    }.getOrDefault("uid $uid")
                    TrafficAppRow(uid, v[0], v[1], label)
                }
                .toList()

            TrafficPending(
                rxBytes = rxTotal.takeIf { it > 0L },
                txBytes = txTotal.takeIf { it > 0L },
                byApp = rows,
                collectError = collectErr,
            ) to collectErr
        }.getOrElse { e ->
            TrafficPending(
                rxBytes = null,
                txBytes = null,
                byApp = emptyList(),
                collectError = e.message,
            ) to e.message
        }
    }
}
