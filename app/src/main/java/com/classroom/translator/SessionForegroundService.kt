package com.classroom.translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 会话前台服务（2026-09-24 新增）
 *
 * 为什么需要它：Android 的 App Freezer 会在 App 退到后台时冻结整个进程组，
 * 连带把 App 用 ProcessBuilder 拉起的本地推理服务一起冻结 ——
 * 实测现象：App 在后台时服务 TCP 端口能连，但 /health 与推理请求全部无响应（HTTP 000），
 * 切回前台立刻恢复。上课时切微信/锁屏就会导致翻译卡死。
 *
 * 会话进行中把本服务跑起来，App 即处于前台服务状态，不再被冻结。
 * 会话结束即停止（不常驻，省电降温）。
 */
class SessionForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "session"
        private const val NOTIF_ID = 1001

        @Volatile private var running = false
        fun isRunning(): Boolean = running

        fun start(ctx: Context) {
            if (running) return
            val i = Intent(ctx, SessionForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SessionForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "翻译进行中", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "课堂翻译运行时保持麦克风与本地推理服务活跃"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("课堂翻译进行中")
            .setContentText("正在聆听并翻译，切到其他应用不受影响")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
