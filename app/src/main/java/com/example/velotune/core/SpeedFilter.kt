package com.example.velotune.core

import kotlin.math.abs

/**
 * 專業車載複合速度濾波器
 *
 * @param deadbandKmh 速度滯後死區 (預設 1.6 km/h，擺動小於此門檻鎖定不變)
 * @param zeroCutoffKmh 靜止零點截斷 (預設 1.5 km/h，低於此值視為停車 0 km/h)
 * @param slowAlpha 巡航微幅波動時的平滑係數 (預設 0.08，極強抗噪)
 * @param fastAlpha 急加減速時的反應係數 (預設 0.45，迅捷跟隨)
 */
class SpeedFilter(
    private val deadbandKmh: Float = 1.6f,
    private val zeroCutoffKmh: Float = 1.5f,
    private val slowAlpha: Float = 0.08f,
    private val fastAlpha: Float = 0.45f
) {

    private var filteredSpeed: Float? = null
    private var lockedStableSpeed: Float = 0f

    /**
     * 輸入原始 GPS 車速，輸出消噪後的穩態車速
     */
    fun filter(rawSpeed: Float): Float {
        // 1. 靜止截斷：消除紅燈停等時的 GPS 多路徑漂移
        val cleanSpeed = if (rawSpeed < zeroCutoffKmh) 0f else rawSpeed

        val currentFiltered = filteredSpeed
        if (currentFiltered == null) {
            filteredSpeed = cleanSpeed
            lockedStableSpeed = cleanSpeed
            return cleanSpeed
        }

        // 2. 自適應雙速率 EMA 濾波
        val diffFromFiltered = abs(cleanSpeed - currentFiltered)
        val alpha = when {
            diffFromFiltered > 6.0f -> fastAlpha // 急加速 / 重煞車
            diffFromFiltered > 2.5f -> (slowAlpha + fastAlpha) / 2f // 正常變速
            else -> slowAlpha // 勻速巡航 / 雜訊擺動
        }

        val newFiltered = alpha * cleanSpeed + (1f - alpha) * currentFiltered
        filteredSpeed = newFiltered

        // 3. 滯後死區 (Deadband Hysteresis) 鎖定
        // 若過濾後的變化量小於死區門檻，鎖定輸出，不再微幅浮動
        val diffFromStable = abs(newFiltered - lockedStableSpeed)
        if (cleanSpeed == 0f) {
            lockedStableSpeed = 0f
        } else if (diffFromStable >= deadbandKmh) {
            lockedStableSpeed = newFiltered
        }

        return lockedStableSpeed
    }

    /**
     * 重設濾波狀態 (斷訊恢復或手動重置時調用)
     */
    fun reset() {
        filteredSpeed = null
        lockedStableSpeed = 0f
    }
}