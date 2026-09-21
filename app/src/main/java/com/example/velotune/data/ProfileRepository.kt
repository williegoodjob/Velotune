package com.example.velotune.data

import android.content.Context
import com.example.velotune.core.ControlPoint
import org.json.JSONArray
import java.io.File

class ProfileRepository(context: Context) {

    private val file = File(context.filesDir, "profiles.json")
    private val prefs = context.getSharedPreferences("velotune_prefs", Context.MODE_PRIVATE)

    private val presetProfiles: List<VolumeProfile>
        get() = listOf(
            VolumeProfile(
                id = "default_standard",
                name = "預設設定檔",
                isDefaultStar = true, // 初始唯一預設星星
                points = listOf(
                    ControlPoint(0f, 0.20f),
                    ControlPoint(30f, 0.45f),
                    ControlPoint(60f, 0.70f),
                    ControlPoint(90f, 1.00f)
                )
            ),
            VolumeProfile(
                id = "default_highway",
                name = "高速巡航",
                isDefaultStar = false,
                points = listOf(
                    ControlPoint(0f, 0.25f),
                    ControlPoint(50f, 0.40f),
                    ControlPoint(80f, 0.75f),
                    ControlPoint(110f, 1.00f)
                )
            ),
            VolumeProfile(
                id = "default_city",
                name = "市區通勤",
                isDefaultStar = false,
                points = listOf(
                    ControlPoint(0f, 0.15f),
                    ControlPoint(25f, 0.50f),
                    ControlPoint(50f, 0.85f),
                    ControlPoint(70f, 1.00f)
                )
            )
        )

    fun getAllProfiles(): MutableList<VolumeProfile> {
        if (!file.exists()) {
            val initial = presetProfiles.toMutableList()
            saveAllProfiles(initial)
            return initial
        }
        return try {
            val jsonStr = file.readText()
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<VolumeProfile>()
            for (i in 0 until arr.length()) {
                list.add(VolumeProfile.fromJson(arr.getJSONObject(i)))
            }
            if (list.isEmpty()) {
                presetProfiles.toMutableList()
            } else {
                // 防呆：確保清單中至少且僅有一顆 ⭐
                if (list.none { it.isDefaultStar }) {
                    list[0] = list[0].copy(isDefaultStar = true)
                    saveAllProfiles(list)
                }
                list
            }
        } catch (e: Exception) {
            e.printStackTrace()
            presetProfiles.toMutableList()
        }
    }

    fun saveAllProfiles(profiles: List<VolumeProfile>) {
        try {
            val arr = JSONArray()
            for (p in profiles) {
                arr.put(p.toJson())
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 唯一設定 ⭐ 預設檔：將目標設為 true，其他全部改為 false
     */
    fun setStarDefaultProfile(targetId: String) {
        val list = getAllProfiles()
        val updated = list.map {
            it.copy(isDefaultStar = (it.id == targetId))
        }
        saveAllProfiles(updated)
    }

    /**
     * 取得目前標記 ⭐ 的基準預設設定檔
     */
    fun getStarDefaultProfile(): VolumeProfile {
        val list = getAllProfiles()
        return list.find { it.isDefaultStar } ?: list.first()
    }

    /**
     * 依據藍牙 MAC 位址查找綁定的設定檔
     */
    fun findProfileByBtAddress(address: String): VolumeProfile? {
        return getAllProfiles().find { it.boundBtAddress.equals(address, ignoreCase = true) }
    }

    fun getActiveProfileId(): String {
        return prefs.getString("active_profile_id", "default_standard") ?: "default_standard"
    }

    fun setActiveProfileId(id: String) {
        prefs.edit().putString("active_profile_id", id).apply()
    }

    fun exportToJsonString(): String {
        val profiles = getAllProfiles()
        val arr = JSONArray()
        for (p in profiles) {
            arr.put(p.toJson())
        }
        return arr.toString(2)
    }

    fun importFromJsonString(jsonStr: String): Result<Int> {
        return try {
            val arr = JSONArray(jsonStr)
            val importedList = mutableListOf<VolumeProfile>()
            for (i in 0 until arr.length()) {
                importedList.add(VolumeProfile.fromJson(arr.getJSONObject(i)))
            }

            if (importedList.isEmpty()) {
                return Result.failure(IllegalArgumentException("檔案中無有效的設定檔資料"))
            }

            val currentList = getAllProfiles()
            var count = 0

            for (imported in importedList) {
                val existingIndex = currentList.indexOfFirst { it.id == imported.id }
                if (existingIndex != -1) {
                    currentList[existingIndex] = imported.copy(isDefaultStar = currentList[existingIndex].isDefaultStar)
                    count++
                } else {
                    currentList.add(imported.copy(isDefaultStar = false))
                    count++
                }
            }

            saveAllProfiles(currentList)
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}