package com.oncet.smsforward

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** 常驻通知，让小米/华为等系统不轻易杀掉进程，短信广播才能及时收到。 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "后台运行", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持短信转发在后台运行"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("短信转发运行中")
            .setContentText("收到短信会自动推送到 iPhone")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "keepalive"
        private const val NOTIFY_ID = 1

        /** 按设置启动或停止。 */
        fun sync(ctx: Context) {
            val cfg = Config(ctx)
            val intent = Intent(ctx, KeepAliveService::class.java)
            try {
                if (cfg.enabled && cfg.keepAlive) ctx.startForegroundService(intent) else ctx.stopService(intent)
            } catch (e: Exception) {
                // 某些系统在后台禁止启动前台服务；下次打开 App 时会再启动
            }
        }
    }
}
