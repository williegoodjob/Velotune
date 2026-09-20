package com.example.velotune.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.velotune.MainActivity
import com.example.velotune.core.ServiceState

class NotificationHelper(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Velotune 常駐服務",
                NotificationManager.IMPORTANCE_LOW // 設為 LOW 確保音量數值更新時不會一直發出提示音
            ).apply {
                description = "顯示車速與音量自動控制狀態"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(
        speedKmh: Float,
        volumeRatio: Float,
        state: ServiceState
    ): Notification {
        // 點擊通知主體開啟主畫面
        val openAppIntent = Intent(context, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 動作按鈕 1：暫停 / 繼續
        val togglePauseIntent = Intent(context, AutoVolumeService::class.java).apply {
            action = AutoVolumeService.ACTION_TOGGLE_PAUSE
        }
        val togglePausePendingIntent = PendingIntent.getService(
            context, 1, togglePauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseActionTitle = if (state == ServiceState.PAUSED) "繼續" else "暫停"

        // 動作按鈕 2：靜音 / 恢復
        val toggleMuteIntent = Intent(context, AutoVolumeService::class.java).apply {
            action = AutoVolumeService.ACTION_TOGGLE_MUTE
        }
        val toggleMutePendingIntent = PendingIntent.getService(
            context, 2, toggleMuteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val muteActionTitle = if (state == ServiceState.MUTED) "取消靜音" else "靜音"

        // 動作按鈕 3：停止服務
        val stopIntent = Intent(context, AutoVolumeService::class.java).apply {
            action = AutoVolumeService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context, 3, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stateText = when (state) {
            ServiceState.STOPPED -> "未啟動"
            ServiceState.RUNNING -> "運行中"
            ServiceState.PAUSED -> "已暫停"
            ServiceState.MUTED -> "靜音中"
        }

        val volumePercent = (volumeRatio * 100).toInt()
        val contentText = "車速: %.1f km/h | 目標音量: %d%% [%s]".format(speedKmh, volumePercent, stateText)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Velotune 速度調音")
            .setContentText(contentText)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, pauseActionTitle, togglePausePendingIntent)
            .addAction(android.R.drawable.stat_notify_chat, muteActionTitle, toggleMutePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "關閉", stopPendingIntent)
            .build()
    }

    fun updateNotification(speedKmh: Float, volumeRatio: Float, state: ServiceState) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(speedKmh, volumeRatio, state)
        )
    }

    companion object {
        const val CHANNEL_ID = "velotune_foreground_channel"
        const val NOTIFICATION_ID = 1001
    }
}