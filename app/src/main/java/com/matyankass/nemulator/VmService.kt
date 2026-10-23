package com.matyankass.nemulator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

/**
 * Держит процесс QEMU живым, пока приложение свёрнуто: без службы на
 * переднем плане Android убьёт Windows через пару минут в фоне.
 */
class VmService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Работающий ПК", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Nemulator: ПК включён")
            .setContentText("Нажми, чтобы вернуться к Windows")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n)
        // Процессор не должен засыпать посреди работы Windows.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nemulator:vm")
            .apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "vm"
    }
}
