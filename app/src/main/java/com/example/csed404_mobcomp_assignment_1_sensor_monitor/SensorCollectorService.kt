package com.example.csed404_mobcomp_assignment_1_sensor_monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SensorCollectorService : Service() {

    private val binder = LocalBinder()
    lateinit var sensorManager: SensorCollectorManager
        private set

    inner class LocalBinder : Binder() {
        fun getService(): SensorCollectorService = this@SensorCollectorService
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = SensorCollectorManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_FOREGROUND) {
            startForegroundServiceNotification()
        } else if (intent?.action == ACTION_STOP_FOREGROUND) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    private fun startForegroundServiceNotification() {
        val channelId = "SensorCollectorChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Sensor Data Collection",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording Sensor Data")
            .setContentText("Recording CSED404 Sensor Data at >=100Hz...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    companion object {
        const val ACTION_START_FOREGROUND = "START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "STOP_FOREGROUND"
    }
}
