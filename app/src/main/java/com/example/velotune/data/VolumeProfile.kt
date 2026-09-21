package com.example.velotune.data

import com.example.velotune.core.ControlPoint
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class VolumeProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val points: List<ControlPoint>,
    val isDefaultStar: Boolean = false,          // 全域唯一 ⭐ 預設標記
    val boundBtAddress: String? = null,         // 綁定的藍牙 MAC 位址 (null 代表未綁定)
    val boundBtName: String? = null,            // 綁定的藍牙裝置名稱 (顯示用)
    val speedOffsetKmh: Float = 0f              // GPS 速度補償 (± km/h)
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("isDefaultStar", isDefaultStar)
        obj.put("boundBtAddress", boundBtAddress ?: JSONObject.NULL)
        obj.put("boundBtName", boundBtName ?: JSONObject.NULL)
        obj.put("speedOffsetKmh", speedOffsetKmh.toDouble())

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
            // 相容舊版 isDefault 欄位
            val isDefaultStar = obj.optBoolean("isDefaultStar", obj.optBoolean("isDefault", false))
            val boundBtAddress = if (obj.isNull("boundBtAddress")) null else obj.optString("boundBtAddress")
            val boundBtName = if (obj.isNull("boundBtName")) null else obj.optString("boundBtName")
            val speedOffsetKmh = obj.optDouble("speedOffsetKmh", 0.0).toFloat()

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
            return VolumeProfile(
                id = id,
                name = name,
                points = ptsList,
                isDefaultStar = isDefaultStar,
                boundBtAddress = boundBtAddress,
                boundBtName = boundBtName,
                speedOffsetKmh = speedOffsetKmh
            )
        }
    }
}