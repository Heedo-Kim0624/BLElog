package com.example.bletest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class BleForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private var notificationManager: NotificationManager? = null
    private var isForegroundStarted = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
        startObservingController()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isForegroundStarted) {
            val notification = buildNotification(null)
            startForeground(NOTIFICATION_ID, notification)
            isForegroundStarted = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        isForegroundStarted = false
        notificationManager?.cancel(NOTIFICATION_ID)
    }

    override fun onBind(intent: Intent?) = null

    private fun startObservingController() {
        val controller = BleEngine.controller(applicationContext)
        serviceScope.launch {
            combine(
                controller.connectedAddress,
                controller.isAdvertising,
                controller.isScanning,
                controller.reconnecting,
                controller.automationState
            ) { address, advertising, scanning, reconnecting, automation ->
                NotificationState(
                    connectedAddress = address,
                    advertising = advertising,
                    scanning = scanning,
                    reconnecting = reconnecting,
                    automation = automation
                )
            }.collect { state ->
                val notification = buildNotification(state)
                if (!isForegroundStarted) {
                    startForeground(NOTIFICATION_ID, notification)
                    isForegroundStarted = true
                } else {
                    notificationManager?.notify(NOTIFICATION_ID, notification)
                }
                if (!state.isActive && isForegroundStarted) {
                    stopForeground(true)
                    isForegroundStarted = false
                    stopSelf()
                }
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ble_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: NotificationState?): Notification {
        val title = getString(R.string.ble_notification_title)
        val text = when {
            state == null -> getString(R.string.ble_notification_initial)
            state.reconnecting -> getString(R.string.ble_notification_reconnecting)
            state.connectedAddress != null -> getString(R.string.ble_notification_connected, state.connectedAddress)
            state.scanning && state.advertising -> getString(R.string.ble_notification_scanning_advertising)
            state.scanning -> getString(R.string.ble_notification_scanning)
            state.advertising -> getString(R.string.ble_notification_advertising)
            state.automation -> getString(R.string.ble_notification_automation)
            else -> getString(R.string.ble_notification_idle)
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private data class NotificationState(
        val connectedAddress: String?,
        val advertising: Boolean,
        val scanning: Boolean,
        val reconnecting: Boolean,
        val automation: Boolean
    ) {
        val isActive: Boolean
            get() = automation || connectedAddress != null || advertising || scanning || reconnecting
    }

    companion object {
        private const val CHANNEL_ID = "ble_background_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.example.bletest.ACTION_STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, BleForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleForegroundService::class.java))
        }
    }
}
