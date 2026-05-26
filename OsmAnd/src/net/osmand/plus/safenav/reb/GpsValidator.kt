package net.osmand.plus.safenav.reb

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import kotlin.math.*

/**
 * SafeNav — Детектор GPS спуфінгу / РЕБ глушіння
 *
 * Алгоритм: порівнює GPS дані з показниками IMU датчиків.
 * Якщо є суттєва розбіжність — GPS вважається ненадійним.
 */
class GpsValidator(private val sensorManager: SensorManager) : SensorEventListener {

    enum class GpsStatus {
        VALID,        // GPS надійний
        SUSPICIOUS,   // Можливе глушіння
        JAMMED,       // GPS заглушений — перейти на IMU
        NO_SIGNAL     // Немає сигналу взагалі
    }

    data class ValidationResult(
        val status: GpsStatus,
        val confidence: Float,      // 0.0 = повністю ненадійний, 1.0 = надійний
        val reason: String
    )

    // Буфер IMU даних
    private var lastAccel = floatArrayOf(0f, 0f, 9.8f)
    private var lastGyro  = floatArrayOf(0f, 0f, 0f)
    private var imuSpeed  = 0f           // швидкість по IMU (м/с)
    private var lastImuTime = 0L

    // Буфер GPS
    private var lastValidLocation: Location? = null
    private var lastGpsTime = 0L

    companion object {
        const val MAX_SPEED_JUMP_KMH = 50f    // Макс стрибок швидкості за 1с
        const val MAX_POSITION_JUMP_KM = 5f   // Макс стрибок позиції за 1с
        const val MIN_GPS_ACCURACY_M = 100f   // Якщо точність гірша — підозра
        const val IMU_GPS_SPEED_DIFF = 0.35f  // 35% різниця швидкостей = підозра
    }

    init {
        // Реєструємо датчики
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel = event.values.clone()
                updateImuSpeed(event.timestamp)
            }
            Sensor.TYPE_GYROSCOPE -> {
                lastGyro = event.values.clone()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun updateImuSpeed(timestamp: Long) {
        if (lastImuTime == 0L) {
            lastImuTime = timestamp
            return
        }
        val dt = (timestamp - lastImuTime) / 1_000_000_000f // в секундах
        // Горизонтальне прискорення (без гравітації Z)
        val horizAccel = sqrt(lastAccel[0].pow(2) + lastAccel[1].pow(2))
        imuSpeed += horizAccel * dt
        imuSpeed = imuSpeed.coerceIn(0f, 60f) // обмеження 0-60 м/с
        lastImuTime = timestamp
    }

    /**
     * Головна функція — валідація нової GPS точки
     */
    fun validate(newLocation: Location): ValidationResult {
        val now = System.currentTimeMillis()
        val prev = lastValidLocation

        // 1. Перевірка точності GPS
        if (newLocation.accuracy > MIN_GPS_ACCURACY_M) {
            return ValidationResult(
                GpsStatus.SUSPICIOUS, 0.4f,
                "Низька точність GPS: ${newLocation.accuracy.toInt()}м"
            )
        }

        if (prev != null) {
            val timeDeltaSec = (now - lastGpsTime) / 1000f
            if (timeDeltaSec <= 0) return ValidationResult(GpsStatus.VALID, 1f, "OK")

            // 2. Перевірка стрибка позиції
            val distKm = distanceKm(
                prev.latitude, prev.longitude,
                newLocation.latitude, newLocation.longitude
            )
            val impliedSpeedKmh = (distKm / timeDeltaSec) * 3600f

            if (distKm > MAX_POSITION_JUMP_KM) {
                return ValidationResult(
                    GpsStatus.JAMMED, 0.05f,
                    "Стрибок позиції ${distKm.toInt()}км за ${timeDeltaSec.toInt()}с — РЕБ!"
                )
            }

            // 3. Порівняння швидкості GPS vs IMU
            val gpsSpeedMs = newLocation.speed
            val imuSpeedMs = imuSpeed
            if (gpsSpeedMs > 1f && imuSpeedMs > 1f) {
                val diff = abs(gpsSpeedMs - imuSpeedMs) / maxOf(gpsSpeedMs, imuSpeedMs)
                if (diff > IMU_GPS_SPEED_DIFF) {
                    return ValidationResult(
                        GpsStatus.SUSPICIOUS, 0.5f,
                        "Швидкість GPS ${gpsSpeedMs.toInt()}м/с vs IMU ${imuSpeedMs.toInt()}м/с"
                    )
                }
            }
        }

        // GPS валідний — оновлюємо еталон
        lastValidLocation = newLocation
        lastGpsTime = now
        return ValidationResult(GpsStatus.VALID, 0.95f, "GPS надійний")
    }

    /**
     * Відстань між двома точками в км (формула гаверсинуса)
     */
    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat/2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon/2).pow(2)
        return (R * 2 * atan2(sqrt(a), sqrt(1-a))).toFloat()
    }

    fun getLastValidLocation() = lastValidLocation
    fun getCurrentImuSpeed() = imuSpeed

    fun release() {
        sensorManager.unregisterListener(this)
    }
}
