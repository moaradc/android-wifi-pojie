package com.wifi.toolbox.services.pojie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.wifi.toolbox.R
import com.wifi.toolbox.ui.MainActivity

object PojieNotification {

    private const val NOTIFICATION_CHANNEL_ID = "PojieServiceChannel"

    /**
     * 创建通知渠道
     * @param context 上下文
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.wifi_pojie_name),
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    /**
     * 构建前台服务通知
     * @param context 上下文
     * @return Notification对象
     */
    fun buildForeground(context: Context): Notification {
        val notificationIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("target", "Pojie")
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.wifi_pojie_name))
            .setContentText(context.getString(R.string.running))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /**
     * 更新前台服务通知
     * @param context 上下文
     * @param contentText 要更新的内容
     */
    fun update(context: Context, contentText: String, subText: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildForeground(context).let {
            NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.wifi_pojie_name))
                .setContentText(contentText)
                .setSubText(subText)
                .setContentIntent(it.contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        }
        notificationManager.notify(1, notification)
    }
}