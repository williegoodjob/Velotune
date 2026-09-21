package com.example.velotune.core

import com.example.velotune.system.AudioController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sign

/**
 * 音量平滑漸變器
 *
 * @param stepIntervalMs 每一步微調的時間間隔 (預設 60ms，保證高更新率)
 * @param maxStepDelta 每次微調的最大比例變量 (預設 0.015f，相當於 1 秒平滑過渡 25% 音量)
 */
class VolumeSmoother(
    private val scope: CoroutineScope,
    private val audioController: AudioController,
    private val stepIntervalMs: Long = 60L,
    private val maxStepDelta: Float = 0.015f
) {

    @Volatile
    private var targetRatio: Float = audioController.getCurrentVolumeRatio()

    @Volatile
    private var currentRatio: Float = targetRatio

    private var smoothingJob: Job? = null

    // 真正平滑中的即時音量 Flow (供 UI 儀表板細膩展示)
    private val _smoothedVolumeFlow = MutableStateFlow(currentRatio)
    val smoothedVolumeFlow = _smoothedVolumeFlow.asStateFlow()

    /**
     * 設定新的目標音量
     * @param target 目標音量百分比 (0.0 ~ 1.0)
     * @param immediate 是否瞬間跳轉 (靜音或手動強制歸零時使用)
     */
    fun setTargetVolume(target: Float, immediate: Boolean = false) {
        targetRatio = target.coerceIn(0f, 1f)

        if (immediate) {
            smoothingJob?.cancel()
            currentRatio = targetRatio
            _smoothedVolumeFlow.value = currentRatio
            audioController.setVolumeRatio(currentRatio)
            return
        }

        // 若漸變協程未在執行，啟動協程平滑跟隨
        if (smoothingJob?.isActive != true) {
            smoothingJob = scope.launch {
                while (abs(currentRatio - targetRatio) > 0.003f) {
                    val diff = targetRatio - currentRatio
                    val step = diff.sign * minOf(abs(diff), maxStepDelta)
                    currentRatio = (currentRatio + step).coerceIn(0f, 1f)

                    _smoothedVolumeFlow.value = currentRatio
                    audioController.setVolumeRatio(currentRatio)
                    delay(stepIntervalMs)
                }
                // 抵達精確目標點
                currentRatio = targetRatio
                _smoothedVolumeFlow.value = currentRatio
                audioController.setVolumeRatio(currentRatio)
            }
        }
    }

    fun getCurrentRatio(): Float = currentRatio
}