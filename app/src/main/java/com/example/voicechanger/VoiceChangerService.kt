package com.example.voicechanger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

//this is a foreground service to keep the pipeline active when the app is closed, to stop android from killing the background process a notification is attached
class VoiceChangerService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, "VOICE_CHANNEL")//notification must be active for android
            .setContentTitle("Voice Changer Active")
            .setContentText("Microphone processing in background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {//android needs a declaration of the microphone service to access the hardware with the ui gone
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }

        //starts the global engine
        AudioGlobals.engine.startAudio(this)
        return START_STICKY//auto restarts the service if its killed off for memory
    }

    override fun onDestroy() {
        super.onDestroy()
        //stop the engine when the service is destroyed
        AudioGlobals.engine.stopAudio()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("VOICE_CHANNEL", "Voice Processing", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}