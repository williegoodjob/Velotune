package com.example.velotune.data

import android.content.Context
import org.json.JSONObject
import java.io.File

class SettingsRepository(context: Context) {

    private val file = File(context.filesDir, "app_settings.json")

    fun getSettings(): AppSettings {
        if (!file.exists()) return AppSettings()
        return try {
            val jsonStr = file.readText()
            AppSettings.fromJson(JSONObject(jsonStr))
        } catch (e: Exception) {
            e.printStackTrace()
            AppSettings()
        }
    }

    fun saveSettings(settings: AppSettings) {
        try {
            file.writeText(settings.toJson().toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}