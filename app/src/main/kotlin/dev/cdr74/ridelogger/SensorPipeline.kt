package dev.cdr74.ridelogger

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Registers all sensor listeners on one dedicated HandlerThread and copies every
 * delivered event into the ring buffer (design.md §3). Prefers *_UNCALIBRATED
 * variants; falls back to calibrated ones and records which was used.
 */
class SensorPipeline(
    private val sensorManager: SensorManager,
    private val ring: RingBuffer,
) : SensorEventListener {

    data class StreamInfo(
        val stream: Int,
        val sensorName: String,
        val uncalibrated: Boolean,
        val requested: String,
    )

    /** Total events delivered per stream; sampled by the service to compute rates. */
    val counts = AtomicLongArray(Config.STREAM_COUNT)

    private var thread: HandlerThread? = null
    var streams: List<StreamInfo> = emptyList()
        private set

    fun start(): List<StreamInfo> {
        val t = HandlerThread("sensor-pipeline", Process.THREAD_PRIORITY_URGENT_AUDIO)
        t.start()
        thread = t
        val handler = Handler(t.looper)

        val info = mutableListOf<StreamInfo>()

        fun register(stream: Int, uncalType: Int?, calType: Int?, delay: Int, requested: String) {
            val uncal = uncalType?.let { sensorManager.getDefaultSensor(it) }
            val sensor = uncal ?: calType?.let { sensorManager.getDefaultSensor(it) } ?: return
            sensorManager.registerListener(this, sensor, delay, handler)
            info.add(StreamInfo(stream, sensor.name, uncal != null, requested))
        }

        register(
            Config.STREAM_ACCEL, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
            Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_FASTEST, "FASTEST",
        )
        register(
            Config.STREAM_GYRO, Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
            Sensor.TYPE_GYROSCOPE, SensorManager.SENSOR_DELAY_FASTEST, "FASTEST",
        )
        register(
            Config.STREAM_MAG, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
            Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_FASTEST, "FASTEST",
        )
        register(
            Config.STREAM_ROTVEC, null,
            Sensor.TYPE_ROTATION_VECTOR, SensorManager.SENSOR_DELAY_GAME, "GAME",
        )
        register(
            Config.STREAM_BARO, null,
            Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_NORMAL, "NORMAL",
        )

        streams = info
        return info
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Hot path: stream lookup + copy into pre-allocated slot. Nothing else.
        val stream = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.TYPE_ACCELEROMETER -> Config.STREAM_ACCEL
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_GYROSCOPE -> Config.STREAM_GYRO
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, Sensor.TYPE_MAGNETIC_FIELD -> Config.STREAM_MAG
            Sensor.TYPE_ROTATION_VECTOR -> Config.STREAM_ROTVEC
            Sensor.TYPE_PRESSURE -> Config.STREAM_BARO
            else -> return
        }
        ring.offer(stream, event.timestamp, event.values, event.accuracy)
        counts.incrementAndGet(stream)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    fun countsSnapshot(): LongArray = LongArray(Config.STREAM_COUNT) { counts.get(it) }
}
