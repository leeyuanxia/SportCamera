package cn.leeyuanxia.sportcamera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 摄像头前台服务 — 保证待机时不被系统杀死
 *
 * foregroundServiceType = camera | microphone
 * 在 Standby 模式下启动此 Service，保持 CPU 运行
 * （配合 PARTIAL_WAKE_LOCK）
 */
class CameraForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "sport_camera_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_START = "cn.leeyuanxia.sportcamera.ACTION_START"
        const val ACTION_STOP = "cn.leeyuanxia.sportcamera.ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForeground()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY // 服务被杀后自动重启
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForeground() {
        val notification = createNotification("运动相机正在监听语音指令...")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("运动相机")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(Notification.PRIORITY_LOW) // 低优先级通知，不打扰用户
            .setOngoing(true) // 不可清除
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "运动相机服务",
            NotificationManager.IMPORTANCE_LOW // 低优先级 — 不发出声音
        ).apply {
            description = "保持摄像头和麦克风监听状态"
            setShowBadge(false)
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}