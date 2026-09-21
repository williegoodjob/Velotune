package com.example.velotune.system

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*

/**
 * 導航/通話語音避讓監聽器 (Audio Ducking Detector)
 * 監控系統中是否有導航語音、語音助理或通話正在發聲
 */
class AudioGuidanceDetector(
    context: Context,
    private val scope: CoroutineScope,
    private val cooldownMs: Long = 1200L, // 語句停頓防抖緩衝時間
    private val onGuidanceStateChanged: (isGuidanceActive: Boolean) -> Unit
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var cooldownJob: Job? = null
    private var isGuidanceActive = false

    // 需要讓路（避讓）的系統音訊類型
    private val monitoredUsages = setOf(
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, // Google Maps / 導航王語音
        AudioAttributes.USAGE_ASSISTANT,                      // Google 語音助理
        AudioAttributes.USAGE_VOICE_COMMUNICATION,            // 電話 / LINE 通話中
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING, // 來電鈴響
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE           // 鈴聲
    )

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>?) {
            super.onPlaybackConfigChanged(configs)
            checkActivePlayback(configs ?: emptyList())
        }
    }

    private fun checkActivePlayback(configs: List<AudioPlaybackConfiguration>) {
        // 清單中的配置本質上即代表目前正在發聲的會話，直接比對 Usage
        val hasGuidance = configs.any { config ->
            val usage = config.audioAttributes?.usage ?: -1
            monitoredUsages.contains(usage)
        }

        if (hasGuidance) {
            // 導航正在說話：立即取消冷卻，瞬時凍結調音！
            cooldownJob?.cancel()
            if (!isGuidanceActive) {
                isGuidanceActive = true
                onGuidanceStateChanged(true)
            }
        } else {
            // 語音暫停/結束：啟動冷卻計時器，防範句子之間的短暫空檔
            if (isGuidanceActive && cooldownJob?.isActive != true) {
                cooldownJob = scope.launch {
                    delay(cooldownMs)
                    isGuidanceActive = false
                    onGuidanceStateChanged(false)
                }
            }
        }
    }

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
            checkActivePlayback(audioManager.activePlaybackConfigurations ?: emptyList())
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                audioManager.unregisterAudioPlaybackCallback(playbackCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        cooldownJob?.cancel()
    }
}