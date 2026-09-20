package com.example.velotune.system

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class LocationProvider(context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    /**
     * 訂閱即時車速更新 (Flow)
     * @param intervalMs 定位更新頻率 (毫秒)
     * 注意：呼叫此方法前，外部必須確保已取得定位權限。
     */
    @SuppressLint("MissingPermission")
    fun getSpeedFlow(intervalMs: Long = 1000L): Flow<Float> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    // Android location.speed 單位為 m/s，乘 3.6 轉為 km/h
                    val speedKmh = if (location.hasSpeed()) {
                        location.speed * 3.6f
                    } else {
                        0f
                    }
                    trySend(speedKmh.coerceAtLeast(0f))
                }
            }
        }

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

        // 當 Flow 收集終止或協程被取消時，自動註銷監聽，避免記憶體洩漏
        awaitClose {
            fusedClient.removeLocationUpdates(callback)
        }
    }
}