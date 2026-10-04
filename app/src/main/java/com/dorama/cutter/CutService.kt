package com.dorama.cutter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.SystemClock

/** Держит приложение живым, пока идёт задача [Engine], и показывает прогресс в шторке. */
class CutService : Service() {
    private lateinit var nm: NotificationManager
    private var shownAt = 0L
    private val listener: () -> Unit = { refresh() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_WORK, "Нарезка", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Готово", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID_WORK, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (intent?.action == ACTION_STOP) {
            Engine.stop()
            if (Engine.kind == Engine.Kind.IDLE) finished(null, null)
            return START_NOT_STICKY
        }
        Engine.listen(listener)
        Engine.attach(this)
        return START_NOT_STICKY
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun build(): Notification {
        val p = Engine.progress
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CutService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH_WORK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (Engine.kind == Engine.Kind.AI) "ИИ-анализ серии" else "Нарезка видео")
            .setContentText(Engine.status)
            .setProgress(100, p.coerceAtLeast(0), p < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat), "Остановить", stop).build())
            .build()
    }

    private fun refresh() {
        if (Engine.kind == Engine.Kind.IDLE) return
        val now = SystemClock.uptimeMillis()
        if (now - shownAt < 1000) return
        shownAt = now
        nm.notify(ID_WORK, build())
    }

    /** Задача закончилась: убрать уведомление о работе, показать итог и остановиться. */
    fun finished(title: String?, text: String?) {
        Engine.unlisten(listener)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (title != null) {
            nm.notify(ID_DONE, Notification.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build())
        }
        stopSelf()
    }

    override fun onDestroy() {
        Engine.unlisten(listener)
        Engine.detach(this)
        super.onDestroy()
    }

    companion object {
        private const val CH_WORK = "work"
        private const val CH_DONE = "done"
        private const val ID_WORK = 1
        private const val ID_DONE = 2
        private const val ACTION_STOP = "stop"
    }
}
