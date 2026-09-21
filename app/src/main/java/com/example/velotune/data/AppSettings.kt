package com.example.velotune.data

import org.json.JSONObject

enum class GpsLossAction {
    KEEP_LAST,       // 維持最後已知音量
    DROP_TO_SAFE,    // 漸變降至安全預設音量
    PAUSE_CONTROL    // 暫停自動調音
}

enum class SmoothingLevel(val delta: Float, val label: String) {
    FAST(0.035f, "極速靈敏 (機車推薦)"),
    BALANCED(0.015f, "標準平滑 (預設)"),
    LUXURY(0.007f, "豪華沉浸 (緩降慢升)")
}

data class AppSettings(
    val gpsLossAction: GpsLossAction = GpsLossAction.KEEP_LAST,
    val gpsSafeVolumeRatio: Float = 0.25f,               // 安全音量 (25%)
    val gpsTimeoutMs: Long = 4500L,                      // 4.5 秒斷訊判定
    val muteAutoResumeBySpeed: Boolean = true,           // 車速起步自動解靜音
    val muteResumeSpeedThreshold: Float = 15f,           // 解靜音速度門檻 (15 km/h)
    val muteResumeTimeoutSec: Int = 60,                  // 超時自動解靜音秒數 (0 代表停用)
    val smoothingLevel: SmoothingLevel = SmoothingLevel.BALANCED,
    val autoStartServiceOnAppOpen: Boolean = false,      // 打開 App 自動開跑
    val fallbackToStarProfileOnBtDisconnect: Boolean = true // 藍牙斷線切回 ⭐ 預設檔
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("gpsLossAction", gpsLossAction.name)
        obj.put("gpsSafeVolumeRatio", gpsSafeVolumeRatio.toDouble())
        obj.put("gpsTimeoutMs", gpsTimeoutMs)
        obj.put("muteAutoResumeBySpeed", muteAutoResumeBySpeed)
        obj.put("muteResumeSpeedThreshold", muteResumeSpeedThreshold.toDouble())
        obj.put("muteResumeTimeoutSec", muteResumeTimeoutSec)
        obj.put("smoothingLevel", smoothingLevel.name)
        obj.put("autoStartServiceOnAppOpen", autoStartServiceOnAppOpen)
        obj.put("fallbackToStarProfileOnBtDisconnect", fallbackToStarProfileOnBtDisconnect)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): AppSettings {
            return AppSettings(
                gpsLossAction = runCatching { GpsLossAction.valueOf(obj.optString("gpsLossAction")) }.getOrDefault(GpsLossAction.KEEP_LAST),
                gpsSafeVolumeRatio = obj.optDouble("gpsSafeVolumeRatio", 0.25).toFloat(),
                gpsTimeoutMs = obj.optLong("gpsTimeoutMs", 4500L),
                muteAutoResumeBySpeed = obj.optBoolean("muteAutoResumeBySpeed", true),
                muteResumeSpeedThreshold = obj.optDouble("muteResumeSpeedThreshold", 15.0).toFloat(),
                muteResumeTimeoutSec = obj.optInt("muteResumeTimeoutSec", 60),
                smoothingLevel = runCatching { SmoothingLevel.valueOf(obj.optString("smoothingLevel")) }.getOrDefault(SmoothingLevel.BALANCED),
                autoStartServiceOnAppOpen = obj.optBoolean("autoStartServiceOnAppOpen", false),
                fallbackToStarProfileOnBtDisconnect = obj.optBoolean("fallbackToStarProfileOnBtDisconnect", true)
            )
        }
    }
}