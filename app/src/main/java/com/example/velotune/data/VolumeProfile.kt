package com.example.velotune.data

import com.example.velotune.core.ControlPoint
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class VolumeProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val points: List<ControlPoint>,
    val isDefault: Boolean = false
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("isDefault", isDefault)
        val arr = JSONArray()
        for (pt in points) {
            val ptObj = JSONObject()
            ptObj.put("speed", pt.speedKmh.toDouble())
            ptObj.put("volume", pt.volumeRatio.toDouble())
            arr.put(ptObj)
        }
        obj.put("points", arr)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): VolumeProfile {
            val id = obj.optString("id", UUID.randomUUID().toString())
            val name = obj.optString("name", "未命名設定檔")
            val isDefault = obj.optBoolean("isDefault", false)
            val ptsList = mutableListOf<ControlPoint>()
            val arr = obj.optJSONArray("points")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val ptObj = arr.getJSONObject(i)
                    ptsList.add(
                        ControlPoint(
                            speedKmh = ptObj.optDouble("speed", 0.0).toFloat(),
                            volumeRatio = ptObj.optDouble("volume", 0.0).toFloat()
                        )
                    )
                }
            }
            return VolumeProfile(id, name, ptsList, isDefault)
        }
    }
}