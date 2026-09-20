package com.example.velotune.core

data class ControlPoint(
    val speedKmh: Float,    // 速度 (km/h)
    val volumeRatio: Float  // 音量百分比 (0.0f ~ 1.0f)
)