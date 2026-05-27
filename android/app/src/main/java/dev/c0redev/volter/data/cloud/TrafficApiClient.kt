package dev.c0redev.volter.data.cloud

import dev.c0redev.volter.VolterLog
import dev.c0redev.volter.domain.model.ServerTraffic
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object TrafficApiClient {

    private const val TAG = "TrafficApiClient"
    private const val REQUEST_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /**
     * Fetch traffic data from control server.
     * Uses GET {controlUrl}/api/v1/clients/{clientId}/traffic
     * with Authorization: Bearer {secret}.
     */
    fun fetch(clientId: String, secret: String, controlUrl: String): ServerTraffic? {
        val base = controlUrl.trimEnd('/')
        val url = "$base/api/v1/clients/$clientId/traffic"
        VolterLog.i("$TAG GET $url")

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = REQUEST_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $secret")
        conn.setRequestProperty("Accept", "application/json")

        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                ?: throw RuntimeException("traffic api: empty response")

            if (code != HttpURLConnection.HTTP_OK) {
                VolterLog.w("$TAG HTTP $code: $body")
                return null
            }

            val json = JSONObject(body)
            return ServerTraffic.fromJson(json)
        } catch (e: Exception) {
            VolterLog.w("$TAG fetch error: ${e.message}")
            return null
        } finally {
            conn.disconnect()
        }
    }
}
