package com.example.velotune.core

/**
 * 速度平滑濾波器 (EMA)
 *
 * @param alpha 平滑係數 (0.0 < alpha <= 1.0)。
 *              數值越低越平滑但有微小延遲；數值越高對速度突變越敏感。
 */
class SpeedFilter(private val alpha: Float = 0.35f) {

    private var currentFilteredSpeed: Float? = null

    /**
     * 輸入 GPS 原始速度 (km/h)，輸出平滑後的數值
     */
    fun filter(rawSpeedKmh: Float): Float {
        val prev = currentFilteredSpeed
        val result = if (prev == null) {
            rawSpeedKmh
        } else {
            alpha * rawSpeedKmh + (1f - alpha) * prev
        }
        currentFilteredSpeed = result
        return result
    }

    /**
     * 重設濾波狀態（訊號斷線重連或重新啟動服務時使用）
     */
    fun reset() {
        currentFilteredSpeed = null
    }
}