package com.example.velotune.system

import android.content.Context
import android.media.AudioManager
import android.os.Build
import kotlin.math.roundToInt

class AudioController(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val maxVolume: Int
        get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    val minVolume: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }

    /**
     * 設定音樂音量比例 (0.0f ~ 1.0f)
     * @param ratio 音量比例
     * @param showUi 是否在畫面上彈出系統音量調整 HUD (預設關閉避免打擾)
     */
    fun setVolumeRatio(ratio: Float, showUi: Boolean = false) {
        val clampedRatio = ratio.coerceIn(0f, 1f)
        val range = maxVolume - minVolume
        val targetStep = (minVolume + range * clampedRatio).roundToInt()

        val flags = if (showUi) AudioManager.FLAG_SHOW_UI else 0
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetStep, flags)
    }

    /**
     * 取得目前系統音樂音量比例 (0.0f ~ 1.0f)
     */
    fun getCurrentVolumeRatio(): Float {
        val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val range = maxVolume - minVolume
        if (range <= 0) return 0f
        return ((currentStep - minVolume).toFloat() / range).coerceIn(0f, 1f)
    }
}