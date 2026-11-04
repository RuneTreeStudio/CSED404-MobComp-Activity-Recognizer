package com.example.csed404_mobcomp_assignment_1_sensor_monitor

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.csed404_mobcomp_assignment_1_sensor_monitor.databinding.ActivityCollectorBinding
import java.util.Locale
import android.content.Context

class CollectorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCollectorBinding
    private lateinit var zipExporter: DatasetZipExporter
    private var currentState = SensorCollectorManager.State.IDLE

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            val studentId = binding.etStudentId.text.toString()
            binding.tvState.text = "Current State: EXPORTING_ZIP"
            binding.btnExportZip.isEnabled = false
            zipExporter.exportToZip(studentId, uri) {
                binding.tvState.text = "Current State: IDLE"
                binding.btnExportZip.isEnabled = true
            }
        }
    }
    
    private lateinit var sensorService: SensorCollectorService
    private var isBound = false
    
    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as SensorCollectorService.LocalBinder
            sensorService = binder.getService()
            isBound = true
            
            val manager = sensorService.sensorManager
            manager.onStateUpdated = { state ->
                currentState = state
                binding.tvState.text = "Current State: ${state.name}"
                updateUIForState(state)
            }
            manager.onTimeUpdated = { validMs, totalMs ->
                updateDurationsDisplay(validMs, totalMs)
            }
            manager.onRateUpdated = { linearRate, gravityRate, gyroRate ->
                binding.tvSamplingRate.text = String.format(Locale.US, "Sampling Rate: Lin: %.1f Hz, Grav: %.1f Hz, Gyro: %.1f Hz", linearRate, gravityRate, gyroRate)
            }
            
            // Sync current state
            currentState = manager.currentState
            binding.tvState.text = "Current State: ${currentState.name}"
            updateUIForState(currentState)
            
            // Sync default durations on bind
            val classId = getSelectedActivityClass()
            val sharedPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val totalMs = sharedPrefs.getLong("total_duration_class_$classId", 0L)
            if (currentState == SensorCollectorManager.State.IDLE) {
                updateDurationsDisplay(0L, totalMs)
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        zipExporter = DatasetZipExporter(this)

        val sharedPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        binding.etStudentId.setText(sharedPrefs.getString("student_id", "20201234"))
        
        binding.rgActivityClass.setOnCheckedChangeListener { _, _ ->
            if (currentState == SensorCollectorManager.State.IDLE) {
                val classId = getSelectedActivityClass()
                val totalMs = sharedPrefs.getLong("total_duration_class_$classId", 0L)
                updateDurationsDisplay(0L, totalMs)
                updateUIForState(currentState)
            }
        }
        
        // Initial setup for default checked radio button
        val defaultClassId = getSelectedActivityClass()
        val defaultTotalMs = sharedPrefs.getLong("total_duration_class_$defaultClassId", 0L)
        updateDurationsDisplay(0L, defaultTotalMs)
        
        // Ensure buttons are in the correct enabled/disabled state at launch
        updateUIForState(currentState)

        binding.btnStartStop.setOnClickListener {
            if (!isBound) return@setOnClickListener
            when (currentState) {
                SensorCollectorManager.State.IDLE -> {
                    val studentId = binding.etStudentId.text.toString()
                    if (studentId.isBlank()) {
                        Toast.makeText(this, "Enter Student ID", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    sharedPrefs.edit().putString("student_id", studentId).apply()
                    val classId = getSelectedActivityClass()
                    
                    // Discard old data because starting again on an activity that was stopped should clear the data.
                    discardSavedData(studentId, classId)
                    
                    val intent = android.content.Intent(this, SensorCollectorService::class.java).apply {
                        action = SensorCollectorService.ACTION_START_FOREGROUND
                    }
                    androidx.core.content.ContextCompat.startForegroundService(this, intent)
                    
                    sensorService.sensorManager.startSession(studentId, classId)
                }
                SensorCollectorManager.State.DELAYED_START_COUNTDOWN,
                SensorCollectorManager.State.RECORDING,
                SensorCollectorManager.State.PAUSED -> {
                    sensorService.sensorManager.stopSession()
                    val intent = android.content.Intent(this, SensorCollectorService::class.java).apply {
                        action = SensorCollectorService.ACTION_STOP_FOREGROUND
                    }
                    startService(intent)
                }
            }
        }

        binding.btnPauseResume.setOnClickListener {
            if (!isBound) return@setOnClickListener
            when (currentState) {
                SensorCollectorManager.State.RECORDING,
                SensorCollectorManager.State.DELAYED_START_COUNTDOWN -> {
                    sensorService.sensorManager.pauseSession()
                }
                SensorCollectorManager.State.PAUSED -> {
                    sensorService.sensorManager.resumeSession()
                }
                else -> {}
            }
        }

        binding.btnDiscard.setOnClickListener {
            if (!isBound) return@setOnClickListener
            if (currentState == SensorCollectorManager.State.IDLE) {
                val studentId = binding.etStudentId.text.toString()
                val classId = getSelectedActivityClass()
                discardSavedData(studentId, classId)
                // Stop service if we discard, just in case
                val intent = android.content.Intent(this, SensorCollectorService::class.java).apply {
                    action = SensorCollectorService.ACTION_STOP_FOREGROUND
                }
                startService(intent)
            }
        }

        binding.btnExportZip.setOnClickListener {
            val studentId = binding.etStudentId.text.toString()
            if (studentId.isBlank()) {
                Toast.makeText(this, "Enter Student ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            zipExporter.validateAndExport(studentId) {
                createDocumentLauncher.launch("${studentId}_dataset.zip")
            }
        }
    }

    private fun getSelectedActivityClass(): Int {
        return when (binding.rgActivityClass.checkedRadioButtonId) {
            R.id.rb_other -> 0
            R.id.rb_walking -> 1
            R.id.rb_running -> 2
            R.id.rb_standing -> 3
            R.id.rb_sitting -> 4
            R.id.rb_upstairs -> 5
            R.id.rb_downstairs -> 6
            else -> 0
        }
    }
    
    private fun discardSavedData(studentId: String, classId: Int) {
        val baseDir = java.io.File(getExternalFilesDir(null), "$studentId/$classId")
        if (baseDir.exists()) {
            baseDir.listFiles()?.forEach { it.delete() }
            // Delete the directory itself so it shows up as "Missing" cleanly rather than an empty dir
            baseDir.delete()
        }
        val sharedPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        sharedPrefs.edit().putLong("total_duration_class_$classId", 0L).apply()
        updateDurationsDisplay(0L, 0L)
        
        // Re-evaluate button states now that totalMs is 0 for this class
        updateUIForState(currentState)
    }

    private fun updateDurationsDisplay(validMs: Long, totalMs: Long) {
        val validMin = validMs / 60000
        val validSec = (validMs % 60000) / 1000
        val totalMin = totalMs / 60000
        val totalSec = (totalMs % 60000) / 1000
        binding.tvValidDuration.text = String.format(Locale.US, "Real-time Duration: %02d:%02d", validMin, validSec)
        binding.tvTotalDuration.text = String.format(Locale.US, "Total Valid: %02d:%02d", totalMin, totalSec)
    }

    private fun updateUIForState(state: SensorCollectorManager.State) {
        val isIdle = state == SensorCollectorManager.State.IDLE
        binding.etStudentId.isEnabled = isIdle
        for (i in 0 until binding.rgActivityClass.childCount) {
            binding.rgActivityClass.getChildAt(i).isEnabled = isIdle
        }
        binding.btnExportZip.isEnabled = isIdle

        val classId = getSelectedActivityClass()
        val sharedPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val totalMs = sharedPrefs.getLong("total_duration_class_$classId", 0L)

        when (state) {
            SensorCollectorManager.State.IDLE -> {
                binding.btnStartStop.text = "Start"
                binding.btnPauseResume.isEnabled = false
                binding.btnPauseResume.text = "Pause"
                binding.btnDiscard.isEnabled = (totalMs > 0)
            }
            SensorCollectorManager.State.DELAYED_START_COUNTDOWN,
            SensorCollectorManager.State.RECORDING -> {
                binding.btnStartStop.text = "Stop"
                binding.btnPauseResume.isEnabled = true
                binding.btnPauseResume.text = "Pause"
                binding.btnDiscard.isEnabled = false
            }
            SensorCollectorManager.State.PAUSED -> {
                binding.btnStartStop.text = "Stop"
                binding.btnPauseResume.isEnabled = true
                binding.btnPauseResume.text = "Resume"
                binding.btnDiscard.isEnabled = false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        android.content.Intent(this, SensorCollectorService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            sensorService.sensorManager.apply {
                onStateUpdated = null
                onTimeUpdated = null
                onRateUpdated = null
            }
            unbindService(connection)
            isBound = false
        }
    }
}
