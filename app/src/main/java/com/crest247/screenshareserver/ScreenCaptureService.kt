package com.crest247.screenshareserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    private lateinit var screenCaptureManager: ScreenCaptureManager

    override fun onCreate() {
        super.onCreate()
        screenCaptureManager = ScreenCaptureManager(
            context = this,
            onLog = { msg ->
                val intent = Intent("com.crest247.screenshareserver.LOG")
                intent.putExtra("msg", msg)
                sendBroadcast(intent)
            },
            onShutdown = {
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = "STOP"
                }
                startService(intent)
            }
        )
        createNotificationChannel()
    }

    private fun logToUI(msg: String) {
        val intent = Intent("com.crest247.screenshareserver.LOG")
        intent.putExtra("msg", msg)
        sendBroadcast(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("ScreenCaptureService", "onStartCommand action: ${intent?.action}")
        logToUI("Service: onStartCommand ${intent?.action}")

        if (intent?.action == "START") {
            // Immediately start foreground to satisfy the system promise
            startForegroundService()

            val resultCode = intent.getIntExtra("RESULT_CODE", 0) // Default to 0 (Canceled)
            
            // Robust Parcelable extraction
            val data: Intent? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("DATA")
            }

            logToUI("Service Code: $resultCode, Data: ${if (data!=null) "OK" else "NULL"}")

            // Corrected check: RESULT_OK is -1
            if (resultCode == -1 && data != null) {
                logToUI("Service: Starting Manager...")
                screenCaptureManager.start(resultCode, data)
            } else {
                logToUI("Service Error: Missing Data. Code=$resultCode (Expected -1)")
            }
        } else if (intent?.action == "STOP") {
            screenCaptureManager.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Share Server")
            .setContentText("Sharing screen and audio...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .build()

        startForeground(1, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    override fun onDestroy() {
        super.onDestroy()
        screenCaptureManager.stop()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    companion object {
        const val CHANNEL_ID = "ScreenCaptureChannel"
    }
}
