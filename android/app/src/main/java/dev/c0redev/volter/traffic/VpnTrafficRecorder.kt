package dev.c0redev.volter.traffic

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
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

            var rxTotal = 0L
            var txTotal = 0L
            val byUid = mutableMapOf<Int, LongArray>()

            @Suppress("DEPRECATION")
            val stats = nsm.queryDetails(ConnectivityManager.TYPE_VPN, null, startMs, endMs)
            if (stats == null) {
                val msg = "NetworkStats queryDetails returned null"
                return@runCatching TrafficPending(
                    rxBytes = null,
                    txBytes = null,
                    byApp = emptyList(),
                    collectError = msg,
                ) to msg
            }

            stats.use { s ->
                val b = NetworkStats.Bucket()
                while (s.hasNextBucket()) {
                    if (!s.getNextBucket(b)) break
                    val uid = b.uid
                    if (uid == NetworkStats.Bucket.UID_REMOVED || uid == NetworkStats.Bucket.UID_TETHERING) continue
                    val arr = byUid.getOrPut(uid) { longArrayOf(0L, 0L) }
                    arr[0] += b.rxBytes
                    arr[1] += b.txBytes
                    rxTotal += b.rxBytes
                    txTotal += b.txBytes
                }
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
                collectError = null,
            ) to null
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
