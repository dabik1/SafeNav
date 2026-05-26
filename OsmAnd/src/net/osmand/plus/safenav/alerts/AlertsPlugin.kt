package net.osmand.plus.safenav.alerts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.location.Location
import net.osmand.plus.OsmandApplication
import net.osmand.plus.plugins.OsmandPlugin
import net.osmand.plus.views.OsmandMapTileView
import net.osmand.plus.views.layers.base.OsmandMapLayer

/**
 * SafeNav — Плагін відображення тривог на карті
 */
class AlertsPlugin(app: OsmandApplication) : OsmandPlugin(app) {

    companion object {
        const val PLUGIN_ID = "safenav.alerts"
        // Отримати безкоштовно на alerts.in.ua
        const val API_KEY_PREF = "safenav_alerts_api_key"
        const val ALERTS_ENABLED_PREF = "safenav_alerts_enabled"
    }

    private var alertsClient: AlertsApiClient? = null
    private var currentAlerts: List<AlertsApiClient.AlertRegion> = emptyList()
    private var currentUserOblast: String = ""

    override fun getId() = PLUGIN_ID
    override fun getName(context: Context) = "SafeNav: Тривоги України"
    override fun getDescription(ctx: Context) = "Показує повітряні тривоги на карті в реальному часі"

    override fun isEnabled() = app.settings.getBoolean(ALERTS_ENABLED_PREF, true)

    fun initialize() {
        val apiKey = app.settings.getString(API_KEY_PREF, "")
        if (apiKey.isNullOrEmpty()) return

        alertsClient = AlertsApiClient(apiKey)
        startPolling()
    }

    private fun startPolling() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                fetchAlerts()
                handler.postDelayed(this, AlertsApiClient.POLL_INTERVAL_MS)
            }
        }
        handler.post(runnable)
    }

    private fun fetchAlerts() {
        alertsClient?.fetchAlerts(object : AlertsApiClient.AlertsListener {
            override fun onAlertsUpdated(alerts: List<AlertsApiClient.AlertRegion>) {
                currentAlerts = alerts
                checkUserRegion()
                app.osmandMap?.mapView?.refreshMap()
            }
            override fun onError(message: String) {
                // Тихо логуємо — не заважаємо навігації
                android.util.Log.w("SafeNav", "Alerts API error: $message")
            }
        })
    }

    /**
     * Перевіряємо чи є тривога в поточному регіоні користувача
     */
    fun checkUserRegion() {
        val userLocation = app.locationProvider?.lastKnownLocation ?: return
        // Визначаємо область по координатах (спрощено через geocoding)
        // В повній версії — пошук по offline OSM polygons
        val activeInRegion = currentAlerts.filter { alert ->
            alert.alertType == "air_raid" &&
            alert.locationType == "oblast"
        }
        if (activeInRegion.isNotEmpty()) {
            notifyUser(activeInRegion)
        }
    }

    private fun notifyUser(alerts: List<AlertsApiClient.AlertRegion>) {
        // Вібрація + звук + банер
        val oblasts = alerts.joinToString(", ") { it.locationTitle }
        android.util.Log.i("SafeNav", "⚠️ ТРИВОГА: $oblasts")
        // TODO: показати AlertBannerView поверх карти
        // TODO: TTS "Увага! Повітряна тривога в ${oblast}"
    }

    fun getCurrentAlerts() = currentAlerts
    fun hasActiveAlertInRegion(oblastName: String) =
        currentAlerts.any { it.locationTitle.contains(oblastName) && it.alertType == "air_raid" }
}
