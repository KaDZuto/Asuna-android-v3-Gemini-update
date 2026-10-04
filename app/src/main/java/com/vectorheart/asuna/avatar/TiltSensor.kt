package com.vectorheart.asuna.avatar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Датчик движения телефона -> параметры тела Live2D аватара.
 *
 * Использует акселерометр (или LINEAR_ACCELERATION, если доступен) + spring-damper
 * для имитации инерции/покачивания. Когда телефон трясут вверх-вниз или из
 * стороны в сторону, грудь/тело аватара качается с задержкой и плавным
 * затуханием — как физически подвешенный объект.
 *
 * Маппинг осей:
 *  - phone.X (влево/вправо)  -> PARAM_BODY_ANGLE_Z (наклон корпуса влево/вправо)
 *  - phone.Y (вперёд/назад) -> PARAM_BODY_ANGLE_X (наклон вперёд/назад)
 *  - phone.Z (вверх/вниз)   -> PARAM_ANGLE_X (подъём/опускание головы при тряске)
 *  - |acceleration|         -> PARAM_BREATH (глубже дыхание при быстром движении)
 *
 * API:
 *  - [start] / [stop] — подписка/отписка от датчика
 *  - [tilt] — Flow<TiltData> с уже сглаженными значениями (60Hz)
 */
@Singleton
class TiltSensor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class TiltData(
        /** X-axis tilt, -1..1 (negative = left, positive = right) */
        val x: Float,
        /** Y-axis tilt, -1..1 (negative = forward, positive = back) */
        val y: Float,
        /** Z-axis (up/down) — 0..1, magnitude of vertical motion (для bounce/breath) */
        val z: Float,
        /** True если в данный момент есть сильное движение (bounce активен) */
        val isBouncing: Boolean
    )

    private val _tilt = MutableStateFlow(TiltData(0f, 0f, 0f, false))
    val tilt: StateFlow<TiltData> = _tilt.asStateFlow()

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // LINEAR_ACCELERATION — это accelerometer минус гравитация, идеально для "тряски".
    // Если нет — fallback на обычный accelerometer.
    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Spring-damper: имитация физического маятника с критическим затуханием
    // Текущая позиция (применяется к аватару)
    private var posX = 0f
    private var posY = 0f
    private var posZ = 0f
    // Скорость
    private var velX = 0f
    private var velY = 0f
    private var velZ = 0f
    // Параметры spring-damper
    // m*g = -k*x  =>  omega = sqrt(k/m)
    // c — демпфирование (c=2*sqrt(k*m) — критическое)
    // 0..1 -> max amplitude [-10..10] для PARAM_BODY_ANGLE
    private val k = 28f       // жёсткость пружины
    private val damping = 0.92f  // затухание за кадр (60Hz), почти критическое

    // Маппинг: 1g = 9.8 m/s^2 -> максимальный угол
    private val accelToTiltScale = 0.6f

    private var listening = false

    fun start() {
        if (listening || sensor == null) return
        listening = true
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        Log.d(TAG, "TiltSensor started: " + sensor?.name)
    }

    fun stop() {
        if (!listening) return
        sensorManager.unregisterListener(listener)
        listening = false
        // Плавно возвращаемся в нейтраль
        posX = 0f; posY = 0f; posZ = 0f
        velX = 0f; velY = 0f; velZ = 0f
        _tilt.value = TiltData(0f, 0f, 0f, false)
        Log.d(TAG, "TiltSensor stopped")
    }

    fun isAvailable(): Boolean = sensor != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION &&
                event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

            // event.values: [x, y, z] в m/s^2
            // На Android x — горизонтально, y — вертикально (вверх), z — из экрана
            // (в портретной ориентации)
            val rawX = event.values[0]
            val rawY = event.values[1]
            val rawZ = event.values[2]

            // Если это обычный акселерометр, нужно убрать гравитацию (~9.8 по Y).
            // Простое приближение: вычтем 9.8 из Y (если телефон в покое лежит плашмя).
            val ay = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) rawY - 9.8f else rawY

            // Целевые значения (force -> target position через spring)
            // Множитель подбирается эмпирически
            val targetX = -rawX * accelToTiltScale   // телефон наклонён вправо -> отрицательный угол
            val targetY = ay * accelToTiltScale
            val targetZ = abs(rawZ) * accelToTiltScale

            // Spring-damper: F = -k*(x - target) - c*v
            // dt = 1/60
            val dt = 1f / 60f
            val ax = -k * (posX - targetX) - 8f * velX
            val ay2 = -k * (posY - targetY) - 8f * velY
            val az = -k * (posZ - targetZ) - 8f * velZ
            velX += ax * dt
            velY += ay2 * dt
            velZ += az * dt
            posX += velX * dt
            posY += velY * dt
            posZ += velZ * dt
            // Ограничиваем амплитуду (все параметры Cubism 2 обычно -10..10)
            posX = posX.coerceIn(-10f, 10f)
            posY = posY.coerceIn(-10f, 10f)
            posZ = posZ.coerceIn(0f, 10f)

            // Демпфируем скорость (трение воздуха)
            velX *= damping
            velY *= damping
            velZ *= damping

            val normX = posX / 10f
            val normY = posY / 10f
            val normZ = posZ / 10f
            val isBouncing = abs(velZ) > 1.5f || abs(velY) > 1.5f || abs(velX) > 1.5f

            _tilt.value = TiltData(
                x = normX.coerceIn(-1f, 1f),
                y = normY.coerceIn(-1f, 1f),
                z = normZ.coerceIn(0f, 1f),
                isBouncing = isBouncing
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    companion object {
        private const val TAG = "TiltSensor"
    }
}
