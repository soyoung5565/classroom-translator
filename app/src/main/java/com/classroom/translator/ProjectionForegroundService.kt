package com.classroom.translator

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
import android.util.Log

/**
 * MediaProjection 前台服务（Android 14+ 硬性要求）。
 * 拿到投影授权后必须先把本服务拉起（type=mediaProjection），
 * 否则 getMediaProjection() 抛 SecurityException 闪退。
 */
class ProjectionForegroundService : Service() {

    companion object {
        private const val TAG = "ProjectionSvc"
        private const val CHANNEL_ID = "projection_channel"
        private const val NOTIFICATION_ID = 1001

        // 服务 startForeground 完成后回调（Android 14+ 要求：getMediaProjection 前必须已在前台）
        @Volatile var onReady: (() -> Unit)? = null

        fun start(ctx: Context, ready: () -> Unit) {
            onReady = ready
            val intent = Intent(ctx, ProjectionForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
                ready()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 14 (API 34)+ 必须指定 FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 34)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                else 0)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "Projection foreground service started")
        // 前台就绪，回调 MainActivity 继续 getMediaProjection
        try { onReady?.invoke() } catch (e: Exception) { Log.e(TAG, "onReady failed", e) }
        onReady = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "屏幕录制/内录", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("课堂翻译")
            .setContentText("正在内录系统声音（测试模式）")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Projection foreground service stopped")
    }
}
