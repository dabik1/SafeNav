package net.osmand.plus.safenav.alerts

import net.osmand.plus.OsmandApplication
import net.osmand.plus.plugins.OsmandPlugin

/**
 * SafeNav — Плагін відображення тривог на карті
 * Цей файл залишено для сумісності.
 * Основна логіка перенесена в SafeNavPlugin.java
 */
class AlertsPlugin(app: OsmandApplication) : OsmandPlugin(app) {

    companion object {
        const val PLUGIN_ID        = "safenav.alerts"
        const val API_KEY_PREF     = "safenav_alerts_api_key"
        const val ALERTS_ENABLED_PREF = "safenav_alerts_enabled"
    }

    // Правильні сигнатури OsmandPlugin
    override fun getId(): String = PLUGIN_ID

    override fun getName(): String = "SafeNav: Тривоги України"

    override fun getDescription(linksEnabled: Boolean): CharSequence =
        "Показує повітряні тривоги на карті в реальному часі"
}
