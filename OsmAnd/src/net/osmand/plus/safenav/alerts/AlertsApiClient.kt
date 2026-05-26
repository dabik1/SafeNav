package net.osmand.plus.safenav.alerts

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * SafeNav — Клієнт API тривог України
 * Джерело: alerts.in.ua
 */
class AlertsApiClient(private val apiKey: String) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val API_URL = "https://api.alerts.in.ua/v1/alerts/active.json"
        const val POLL_INTERVAL_MS = 30_000L // 30 секунд
    }

    interface AlertsListener {
        fun onAlertsUpdated(alerts: List<AlertRegion>)
        fun onError(message: String)
    }

    data class AlertRegion(
        val id: Int,
        val locationTitle: String,
        val locationType: String,  // oblast, raion, hromada
        val alertType: String,     // air_raid, artillery, urban_fights
        val startedAt: String,
        val isActive: Boolean
    )

    fun fetchAlerts(listener: AlertsListener) {
        executor.execute {
            try {
                val url = URL(API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "GET"
                    setRequestProperty("X-API-Key", apiKey)
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()

                    val alerts = parseAlerts(response)
                    mainHandler.post { listener.onAlertsUpdated(alerts) }
                } else {
                    mainHandler.post { listener.onError("HTTP $responseCode") }
                }
                conn.disconnect()
            } catch (e: Exception) {
                mainHandler.post { listener.onError(e.message ?: "Помилка мережі") }
            }
        }
    }

    private fun parseAlerts(json: String): List<AlertRegion> {
        val result = mutableListOf<AlertRegion>()
        try {
            val root = JSONObject(json)
            val alertsArray = root.getJSONArray("alerts")
            for (i in 0 until alertsArray.length()) {
                val obj = alertsArray.getJSONObject(i)
                result.add(
                    AlertRegion(
                        id = obj.optInt("id"),
                        locationTitle = obj.optString("location_title"),
                        locationType = obj.optString("location_type"),
                        alertType = obj.optString("alert_type"),
                        startedAt = obj.optString("started_at"),
                        isActive = true
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }
}
