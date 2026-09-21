package com.example.velotune.data

import android.content.Context
import com.example.velotune.core.ControlPoint
import org.json.JSONArray
import java.io.File

class ProfileRepository(context: Context) {

    private val file = File(context.filesDir, "profiles.json")
    private val prefs = context.getSharedPreferences("velotune_prefs", Context.MODE_PRIVATE)

    // 初始內建的三組經典調音範本
    private val presetProfiles: List<VolumeProfile>
        get() = listOf(
            VolumeProfile(
                id = "default_standard",
                name = "預設設定檔",
                isDefault = true,
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
                isDefault = false,
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
                isDefault = false,
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
            if (list.isEmpty()) presetProfiles.toMutableList() else list
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

    fun getActiveProfileId(): String {
        return prefs.getString("active_profile_id", "default_standard") ?: "default_standard"
    }

    fun setActiveProfileId(id: String) {
        prefs.edit().putString("active_profile_id", id).apply()
    }
    /**
     * 匯出：產出具備縮排美化的 JSON 字串
     */
    fun exportToJsonString(): String {
        val profiles = getAllProfiles()
        val arr = JSONArray()
        for (p in profiles) {
            arr.put(p.toJson())
        }
        return arr.toString(2) // 縮排 2 格方便人類閱讀
    }

    /**
     * 匯入：解析 JSON 字串並智慧合併至本地
     * @return 成功匯入/更新的設定檔數量
     */
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
                    // 若存在且非系統預設檔，進行內容覆寫更新
                    if (!currentList[existingIndex].isDefault) {
                        currentList[existingIndex] = imported.copy(isDefault = false)
                        count++
                    }
                } else {
                    // 若為全新設定檔，直接加入列表 (強制非預設)
                    currentList.add(imported.copy(isDefault = false))
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
