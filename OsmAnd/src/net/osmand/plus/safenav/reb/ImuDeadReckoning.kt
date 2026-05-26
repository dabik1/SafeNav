package net.osmand.plus.safenav.reb

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.*

/**
 * SafeNav — Dead Reckoning по IMU датчиках
 *
 * Коли GPS недоступний або заглушений РЕБ,
 * відслідковуємо позицію по гіроскопу + акселерометру + компасу.
 *
 * Використовує фільтр Мадгвіка для злиття датчиків.
 */
class ImuDeadReckoning(private val sensorManager: SensorManager) : SensorEventListener {

    data class Position(
        val latitude: Double,
        val longitude: Double,
        val heading: Float,       // градуси 0-360
        val speedMs: Float,
        val accuracyM: Float,     // оціночна похибка в метрах
        val timestamp: Long
    )

    // Стан фільтра Мадгвіка (quaternion)
    private var q0 = 1f; private var q1 = 0f
    private var q2 = 0f; private var q3 = 0f
    private val beta = 0.1f  // коефіцієнт фільтра

    // Поточна позиція (dead reckoning)
    private var currentLat = 0.0
    private var currentLon = 0.0
    private var currentHeading = 0f
    private var currentSpeedMs = 0f
    private var estimatedErrorM = 0f

    // Час
    private var lastTimestamp = 0L
    private var isActive = false
    private var startTime = 0L

    // Датчики
    private var accel = floatArrayOf(0f, 0f, 9.8f)
    private var gyro  = floatArrayOf(0f, 0f, 0f)
    private var mag   = floatArrayOf(25f, 0f, -40f)

    private var listener: ((Position) -> Unit)? = null

    /**
     * Запускаємо з останньої відомої GPS позиції
     */
    fun start(lastKnownLat: Double, lastKnownLon: Double, callback: (Position) -> Unit) {
        currentLat = lastKnownLat
        currentLon = lastKnownLon
        estimatedErrorM = 0f
        isActive = true
        startTime = System.currentTimeMillis()
        listener = callback

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        isActive = false
        sensorManager.unregisterListener(this)
        listener = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isActive) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accel = event.values.clone()
            Sensor.TYPE_GYROSCOPE    -> gyro  = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> {
                mag = event.values.clone()
                // Оновлюємо позицію на кожному магнітному вимірі (~50Гц)
                updatePosition(event.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun updatePosition(timestamp: Long) {
        if (lastTimestamp == 0L) {
            lastTimestamp = timestamp
            return
        }

        val dt = (timestamp - lastTimestamp) / 1_000_000_000f
        if (dt <= 0f || dt > 0.5f) {
            lastTimestamp = timestamp
            return
        }

        // Фільтр Мадгвіка — оновлення quaternion орієнтації
        madgwickUpdate(
            gyro[0], gyro[1], gyro[2],
            accel[0], accel[1], accel[2],
            mag[0], mag[1], mag[2],
            dt
        )

        // Отримуємо heading (азимут) з quaternion
        currentHeading = getYaw()

        // Горизонтальне прискорення (відняли гравітацію)
        val horizAccel = sqrt(accel[0].pow(2) + accel[1].pow(2))
        val gravityComponent = 9.8f * sin(Math.toRadians(getPitch().toDouble())).toFloat()
        val netAccel = horizAccel - abs(gravityComponent)

        // Оновлюємо швидкість
        currentSpeedMs = (currentSpeedMs + netAccel * dt).coerceIn(0f, 55.5f) // max 200км/г

        // Зупинка — скидаємо дрейф швидкості
        if (horizAccel < 0.3f) currentSpeedMs *= 0.85f

        // Dead reckoning — оновлюємо координати
        val distM = currentSpeedMs * dt
        val headingRad = Math.toRadians(currentHeading.toDouble())

        val dLat = (distM * cos(headingRad)) / 111_320.0
        val dLon = (distM * sin(headingRad)) / (111_320.0 * cos(Math.toRadians(currentLat)))

        currentLat += dLat
        currentLon += dLon

        // Наростання похибки (приблизно 1.5% відстані)
        estimatedErrorM += distM * 0.015f

        lastTimestamp = timestamp

        val minutesActive = (System.currentTimeMillis() - startTime) / 60_000f

        listener?.invoke(
            Position(
                latitude = currentLat,
                longitude = currentLon,
                heading = currentHeading,
                speedMs = currentSpeedMs,
                accuracyM = estimatedErrorM,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    // ---- Фільтр Мадгвіка ----

    private fun madgwickUpdate(
        gx: Float, gy: Float, gz: Float,
        ax: Float, ay: Float, az: Float,
        mx: Float, my: Float, mz: Float,
        dt: Float
    ) {
        var normA = sqrt(ax*ax + ay*ay + az*az)
        if (normA == 0f) return
        val rNormA = 1f / normA
        val axN = ax * rNormA; val ayN = ay * rNormA; val azN = az * rNormA

        var normM = sqrt(mx*mx + my*my + mz*mz)
        if (normM == 0f) return
        val rNormM = 1f / normM
        val mxN = mx * rNormM; val myN = my * rNormM; val mzN = mz * rNormM

        // Спрощений розрахунок gradient descent
        val s0 = (-2f*q2) * (2f*(q1*q3 - q0*q2) - axN) +
                 (2f*q1)  * (2f*(q0*q1 + q2*q3) - ayN)
        val s1 = (2f*q3)  * (2f*(q1*q3 - q0*q2) - axN) +
                 (2f*q0)  * (2f*(q0*q1 + q2*q3) - ayN) -
                 (4f*q1)  * (1f - 2f*q1*q1 - 2f*q2*q2 - azN)
        val s2 = (-4f*q2) * (2f*(q1*q3 - q0*q2) - axN) +
                 (2f*q3)  * (2f*(q0*q1 + q2*q3) - ayN) +
                 (-4f*q2) * (1f - 2f*q1*q1 - 2f*q2*q2 - azN)
        val s3 = (2f*q1)  * (2f*(q1*q3 - q0*q2) - axN)

        val normS = sqrt(s0*s0 + s1*s1 + s2*s2 + s3*s3).let { if (it == 0f) 1f else it }

        q0 += (-0.5f*(q1*gx + q2*gy + q3*gz) - beta*(s0/normS)) * dt
        q1 += ( 0.5f*(q0*gx + q2*gz - q3*gy) - beta*(s1/normS)) * dt
        q2 += ( 0.5f*(q0*gy - q1*gz + q3*gx) - beta*(s2/normS)) * dt
        q3 += ( 0.5f*(q0*gz + q1*gy - q2*gx) - beta*(s3/normS)) * dt

        val normQ = sqrt(q0*q0 + q1*q1 + q2*q2 + q3*q3).let { if (it == 0f) 1f else it }
        q0 /= normQ; q1 /= normQ; q2 /= normQ; q3 /= normQ
    }

    private fun getYaw(): Float {
        val yaw = atan2(2f*(q0*q3 + q1*q2), 1f - 2f*(q2*q2 + q3*q3))
        return ((Math.toDegrees(yaw.toDouble()).toFloat() + 360f) % 360f)
    }

    private fun getPitch(): Float {
        return asin(2f*(q0*q2 - q3*q1))
    }

    fun getCurrentPosition() = Position(
        currentLat, currentLon, currentHeading,
        currentSpeedMs, estimatedErrorM, System.currentTimeMillis()
    )

    /**
     * Корекція — коли GPS знову з'явився або знайшли орієнтир
     */
    fun correctPosition(lat: Double, lon: Double) {
        currentLat = lat
        currentLon = lon
        estimatedErrorM = 5f // скидаємо похибку до 5м
        android.util.Log.i("SafeNav", "IMU позиція скоригована: $lat, $lon")
    }
}
