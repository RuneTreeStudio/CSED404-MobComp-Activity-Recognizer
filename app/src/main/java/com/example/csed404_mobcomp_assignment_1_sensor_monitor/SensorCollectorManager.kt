package com.example.csed404_mobcomp_assignment_1_sensor_monitor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale

data class SensorSample(
    val timestampUs: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

class SensorCollectorManager(
    private val context: Context
) : SensorEventListener {

    var onStateUpdated: ((State) -> Unit)? = null
    var onTimeUpdated: ((Long, Long) -> Unit)? = null // real time ms, total valid ms
    var onRateUpdated: ((Float, Float, Float) -> Unit)? = null // linear, gravity, gyro rate

    enum class State {
        IDLE, DELAYED_START_COUNTDOWN, RECORDING, PAUSED
    }

    private val DELAYED_START_US = 10_000_000L
    private val EARLY_STOP_US = 10_000_000L
    private val SENSOR_DELAY_US = SensorManager.SENSOR_DELAY_FASTEST

    var currentState = State.IDLE
        private set
    private var handlerThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val linearSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Sub-segment buffers (temporary)
    private val linearSubBuffer = ArrayList<SensorSample>()
    private val gravitySubBuffer = ArrayList<SensorSample>()
    private val gyroSubBuffer = ArrayList<SensorSample>()

    // Session staging list (committed sub-segments, wait to be flushed)
    private val linearSessionList = ArrayList<SensorSample>()
    private val gravitySessionList = ArrayList<SensorSample>()
    private val gyroSessionList = ArrayList<SensorSample>()

    private var currentStudentId = "20201234"
    private var currentActivityClass = 0
    private var totalAccumulatedDurationMs = 0L // Keep track of the total previous duration for the class

    private var subSegmentAnchorUs = 0L
    private var validStartUs = 0L
    private var lastSeenUs = 0L

    private var hasVibrated = false
    private var isFirstEvent = false
    private var hasPlayed10MinSound = false

    private var linearEventCount = 0
    private var gravityEventCount = 0
    private var gyroEventCount = 0
    private var rateStartTimeMs = 0L
    private var subSegmentStartRealtimeMs = 0L
    private var currentSessionRealTimeMs = 0L
    private var currentSessionValidDurationMs = 0L
    private val mainHandler = Handler(context.mainLooper)

    private val rateUpdateRunnable = object : Runnable {
        override fun run() {
            if (currentState == State.RECORDING || currentState == State.DELAYED_START_COUNTDOWN) {
                val now = SystemClock.elapsedRealtime()
                val elapsedSec = (now - rateStartTimeMs) / 1000f
                if (elapsedSec > 0) {
                    onRateUpdated?.invoke(
                        linearEventCount / elapsedSec,
                        gravityEventCount / elapsedSec,
                        gyroEventCount / elapsedSec
                    )
                }
                linearEventCount = 0
                gravityEventCount = 0
                gyroEventCount = 0
                rateStartTimeMs = now
                mainHandler.postDelayed(this, 1000)
                
                // Update UI duration
                val currentRawElapsedMs = now - subSegmentStartRealtimeMs
                val realTimeMs = currentSessionRealTimeMs + currentRawElapsedMs
                
                var validElapsedMs = 0L
                if (currentState == State.RECORDING && validStartUs > 0) {
                    val validElapsedUs = (lastSeenUs - validStartUs - EARLY_STOP_US).coerceAtLeast(0)
                    validElapsedMs = validElapsedUs / 1000
                }
                
                val totalValidMs = totalAccumulatedDurationMs + currentSessionValidDurationMs + validElapsedMs
                
                onTimeUpdated?.invoke(realTimeMs, totalValidMs)

                if (!hasPlayed10MinSound && totalValidMs >= 600_000L) {
                    hasPlayed10MinSound = true
                    play10MinSound()
                }
            } else if (currentState == State.PAUSED) {
                // Keep the UI accurate during pauses
                val totalValidMs = totalAccumulatedDurationMs + currentSessionValidDurationMs
                onTimeUpdated?.invoke(currentSessionRealTimeMs, totalValidMs)
            }
        }
    }

    fun startSession(studentId: String, activityClass: Int) {
        if (currentState != State.IDLE) return
        currentStudentId = studentId
        currentActivityClass = activityClass
        
        val sharedPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        totalAccumulatedDurationMs = sharedPrefs.getLong("total_duration_class_$activityClass", 0L)
        
        currentSessionRealTimeMs = 0L
        currentSessionValidDurationMs = 0L
        hasPlayed10MinSound = (totalAccumulatedDurationMs >= 600_000L)

        linearSessionList.clear()
        gravitySessionList.clear()
        gyroSessionList.clear()

        startSubSegment()
    }

    fun resumeSession() {
        if (currentState != State.PAUSED) return
        startSubSegment()
    }

    private fun startSubSegment() {
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SensorMonitor::RecordingLock")
        wakeLock?.acquire(20 * 60 * 1000L /*10 minutes max per sub segment*/)

        handlerThread = HandlerThread("SensorCollectorThread").apply { start() }
        sensorHandler = Handler(handlerThread!!.looper)

        linearSubBuffer.clear()
        gravitySubBuffer.clear()
        gyroSubBuffer.clear()

        isFirstEvent = true
        hasVibrated = false
        validStartUs = 0
        lastSeenUs = 0
        
        linearEventCount = 0
        gravityEventCount = 0
        gyroEventCount = 0
        rateStartTimeMs = SystemClock.elapsedRealtime()
        subSegmentStartRealtimeMs = rateStartTimeMs
        
        updateState(State.DELAYED_START_COUNTDOWN)

        sensorHandler?.post {
            linearSensor?.let { sensorManager.registerListener(this, it, SENSOR_DELAY_US, sensorHandler) }
            gravitySensor?.let { sensorManager.registerListener(this, it, SENSOR_DELAY_US, sensorHandler) }
            gyroSensor?.let { sensorManager.registerListener(this, it, SENSOR_DELAY_US, sensorHandler) }
        }
        mainHandler.post(rateUpdateRunnable)
    }

    fun pauseSession() {
        if (currentState != State.RECORDING && currentState != State.DELAYED_START_COUNTDOWN) return
        stopSubSegment(commit = true, toState = State.PAUSED)
    }

    fun stopSession() {
        if (currentState == State.IDLE) return
        if (currentState == State.PAUSED) {
            flushSessionFiles()
            updateState(State.IDLE)
        } else {
            stopSubSegment(commit = true, toState = State.IDLE, flush = true)
        }
    }

    private fun stopSubSegment(commit: Boolean, toState: State, flush: Boolean = false) {
        val capturedLastSeenUs = lastSeenUs
        val capturedValidStartUs = validStartUs
        
        sensorHandler?.post {
            sensorManager.unregisterListener(this)
            
            var subSegmentValidMs = 0L
            val subSegmentRawMs = SystemClock.elapsedRealtime() - subSegmentStartRealtimeMs
            
            if (commit && capturedValidStartUs > 0) {
                val validEndUs = capturedLastSeenUs - EARLY_STOP_US
                if (validEndUs > capturedValidStartUs) {
                    subSegmentValidMs = (validEndUs - capturedValidStartUs) / 1000
                    linearSessionList.addAll(linearSubBuffer.filter { it.timestampUs in capturedValidStartUs..validEndUs })
                    gravitySessionList.addAll(gravitySubBuffer.filter { it.timestampUs in capturedValidStartUs..validEndUs })
                    gyroSessionList.addAll(gyroSubBuffer.filter { it.timestampUs in capturedValidStartUs..validEndUs })
                }
            }
            
            currentSessionRealTimeMs += subSegmentRawMs
            currentSessionValidDurationMs += subSegmentValidMs
            
            linearSubBuffer.clear()
            gravitySubBuffer.clear()
            gyroSubBuffer.clear()
            
            if (flush) {
                flushSessionFiles()
            }
            
            mainHandler.post {
                wakeLock?.let {
                    if (it.isHeld) it.release()
                }
                updateState(toState)
                handlerThread?.quitSafely()
            }
        }
        mainHandler.removeCallbacks(rateUpdateRunnable)
    }

    private fun flushSessionFiles() {
        if (linearSessionList.isEmpty() && gravitySessionList.isEmpty() && gyroSessionList.isEmpty()) {
            return
        }
        
        val baseDir = File(context.getExternalFilesDir(null), "${currentStudentId}/${currentActivityClass}")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        fun writeBuffer(buffer: List<SensorSample>, fileName: String) {
            if (buffer.isEmpty()) return
            val file = File(baseDir, fileName)
            BufferedWriter(FileWriter(file, true)).use { writer ->
                buffer.forEach { sample ->
                    val line = String.format(Locale.US, "%d,%d,%.9e,%.9e,%.9e\n", 
                        currentActivityClass, sample.timestampUs, sample.x, sample.y, sample.z)
                    writer.write(line)
                }
            }
        }

        writeBuffer(linearSessionList, "linear.csv")
        writeBuffer(gravitySessionList, "gravity.csv")
        writeBuffer(gyroSessionList, "gyro.csv")
        
        val sharedPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val currentTotal = sharedPrefs.getLong("total_duration_class_$currentActivityClass", 0L)
        val updatedTotal = currentTotal + currentSessionValidDurationMs
        sharedPrefs.edit().putLong("total_duration_class_$currentActivityClass", updatedTotal).apply()
        
        linearSessionList.clear()
        gravitySessionList.clear()
        gyroSessionList.clear()
    }

    private fun updateState(newState: State) {
        currentState = newState
        mainHandler.post {
            onStateUpdated?.invoke(newState)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val timestampUs = event.timestamp / 1000L
        lastSeenUs = timestampUs

        if (isFirstEvent) {
            isFirstEvent = false
            subSegmentAnchorUs = timestampUs
            validStartUs = subSegmentAnchorUs + DELAYED_START_US
        }

        val isValid = timestampUs >= validStartUs

        if (isValid && !hasVibrated) {
            hasVibrated = true
            updateState(State.RECORDING)
            triggerVibration()
            playStartSound()
        }

        val sample = SensorSample(timestampUs, event.values[0], event.values[1], event.values[2])

        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                linearEventCount++
                if (isValid) linearSubBuffer.add(sample)
            }
            Sensor.TYPE_GRAVITY -> {
                gravityEventCount++
                if (isValid) gravitySubBuffer.add(sample)
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroEventCount++
                if (isValid) gyroSubBuffer.add(sample)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerVibration() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        val audioAttributes = android.media.AudioAttributes.Builder()
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
            .build()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE), audioAttributes)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(300, audioAttributes)
        }
    }

    private fun playStartSound() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            mainHandler.postDelayed({ tg.release() }, 500)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun play10MinSound() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1000)
            mainHandler.postDelayed({ tg.release() }, 1500)
        } catch (e: Exception) { e.printStackTrace() }
    }
}
