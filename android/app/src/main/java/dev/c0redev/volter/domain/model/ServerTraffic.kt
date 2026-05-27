package dev.c0redev.volter.domain.model

import org.json.JSONObject

data class ServerTraffic(
    val rxBytes: Long,
    val txBytes: Long,
) {
    companion object {
        fun fromJson(j: JSONObject): ServerTraffic = ServerTraffic(
            rxBytes = j.optLong("rxBytes", 0L),
            txBytes = j.optLong("txBytes", 0L),
        )

        fun fromClientJson(j: JSONObject): ServerTraffic = ServerTraffic(
            rxBytes = j.optLong("rxBytes", 0L),
            txBytes = j.optLong("txBytes", 0L),
        )
    }
}
