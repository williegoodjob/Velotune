package com.example.velotune.core

class VolumeCurve(points: List<ControlPoint>) {

    // 確保控制點依照速度由低至高排序
    private val sortedPoints: List<ControlPoint> = points.sortedBy { it.speedKmh }

    init {
        require(sortedPoints.isNotEmpty()) { "VolumeCurve 至少需要包含一個錨點。" }
    }

    /**
     * 給定車速 (km/h)，計算出當前對應的目標音量百分比 (0.0f ~ 1.0f)
     */
    fun evaluate(speedKmh: Float): Float {
        if (sortedPoints.size == 1) {
            return sortedPoints.first().volumeRatio.coerceIn(0f, 1f)
        }

        // 低於最低設定速度，回傳最低速對應音量
        if (speedKmh <= sortedPoints.first().speedKmh) {
            return sortedPoints.first().volumeRatio.coerceIn(0f, 1f)
        }

        // 高於最高設定速度，回傳最高速對應音量
        if (speedKmh >= sortedPoints.last().speedKmh) {
            return sortedPoints.last().volumeRatio.coerceIn(0f, 1f)
        }

        // 尋找所在速度區間進行線性插值
        for (i in 0 until sortedPoints.size - 1) {
            val p1 = sortedPoints[i]
            val p2 = sortedPoints[i + 1]

            if (speedKmh in p1.speedKmh..p2.speedKmh) {
                val speedSpan = p2.speedKmh - p1.speedKmh
                if (speedSpan == 0f) return p1.volumeRatio.coerceIn(0f, 1f)

                val factor = (speedKmh - p1.speedKmh) / speedSpan
                val calculatedVolume = p1.volumeRatio + factor * (p2.volumeRatio - p1.volumeRatio)
                return calculatedVolume.coerceIn(0f, 1f)
            }
        }

        return sortedPoints.last().volumeRatio.coerceIn(0f, 1f)
    }

    companion object {
        /**
         * 預設音量曲線設定
         */
        fun defaultCurve(): VolumeCurve {
            return VolumeCurve(
                listOf(
                    ControlPoint(0f, 0.20f),   // 靜止/怠速：20% 音量
                    ControlPoint(30f, 0.45f),  // 市區慢行：45% 音量
                    ControlPoint(60f, 0.70f),  // 一般巡航：70% 音量
                    ControlPoint(90f, 1.00f)   // 高速行駛：100% 音量
                )
            )
        }
    }
}