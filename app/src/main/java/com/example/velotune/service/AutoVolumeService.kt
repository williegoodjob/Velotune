package com.example.velotune.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.velotune.core.ControlPoint
import com.example.velotune.core.ServiceState
import com.example.velotune.core.SpeedFilter
import com.example.velotune.core.VolumeCurve
import com.example.velotune.system.AudioController
import com.example.velotune.system.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class AutoVolumeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var audioController: AudioController
    private lateinit var locationProvider: LocationProvider

    private val speedFilter = SpeedFilter(alpha = 0.35f)

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        audioController = AudioController(this)
        locationProvider = LocationProvider(this)

        _serviceStateFlow.value = ServiceState.RUNNING
        _liveVolumeRatioFlow.value = audioController.getCurrentVolumeRatio()

        startForegroundServiceNotification()
        startSpeedTracking()
    }

    private fun startForegroundServiceNotification() {
        val initialNotification = notificationHelper.buildNotification(
            speedKmh = _liveSpeedFlow.value,
            volumeRatio = _liveVolumeRatioFlow.value,
            state = _serviceStateFlow.value
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                initialNotification
            )
        }
    }

    private fun startSpeedTracking() {
        serviceScope.launch {
            locationProvider.getSpeedFlow(intervalMs = 800L)
                .catch { e -> e.printStackTrace() }
                .collect { rawSpeed ->
                    val filteredSpeed = speedFilter.filter(rawSpeed)
                    _liveSpeedFlow.value = filteredSpeed
                    processVolumeUpdate()
                }
        }
    }

    private fun processVolumeUpdate() {
        val state = _serviceStateFlow.value
        val speed = _liveSpeedFlow.value

        when (state) {
            ServiceState.RUNNING -> {
                val targetRatio = activeVolumeCurve.evaluate(speed)
                _liveVolumeRatioFlow.value = targetRatio
                audioController.setVolumeRatio(targetRatio)
            }
            ServiceState.MUTED -> {
                _liveVolumeRatioFlow.value = 0f
                audioController.setVolumeRatio(0f)
            }
            ServiceState.PAUSED, ServiceState.STOPPED -> {
                // 暫停維持現狀
            }
        }
        notificationHelper.updateNotification(speed, _liveVolumeRatioFlow.value, state)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> {
                _serviceStateFlow.value = if (_serviceStateFlow.value == ServiceState.PAUSED) {
                    ServiceState.RUNNING
                } else {
                    ServiceState.PAUSED
                }
                processVolumeUpdate()
            }
            ACTION_TOGGLE_MUTE -> {
                _serviceStateFlow.value = if (_serviceStateFlow.value == ServiceState.MUTED) {
                    ServiceState.RUNNING
                } else {
                    ServiceState.MUTED
                }
                processVolumeUpdate()
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        _serviceStateFlow.value = ServiceState.STOPPED
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.velotune.ACTION_START"
        const val ACTION_STOP = "com.example.velotune.ACTION_STOP"
        const val ACTION_TOGGLE_PAUSE = "com.example.velotune.ACTION_TOGGLE_PAUSE"
        const val ACTION_TOGGLE_MUTE = "com.example.velotune.ACTION_TOGGLE_MUTE"

        // 共享狀態流 (供 UI 即時觀察)
        private val _liveSpeedFlow = MutableStateFlow(0f)
        val liveSpeedFlow = _liveSpeedFlow.asStateFlow()

        private val _liveVolumeRatioFlow = MutableStateFlow(0.2f)
        val liveVolumeRatioFlow = _liveVolumeRatioFlow.asStateFlow()

        private val _serviceStateFlow = MutableStateFlow(ServiceState.STOPPED)
        val serviceStateFlow = _serviceStateFlow.asStateFlow()

        // 供 UI 即時動態寫入新曲線
        var activeVolumeCurve: VolumeCurve = VolumeCurve.defaultCurve()
            private set

        fun updateCurveFromEditor(points: List<ControlPoint>) {
            if (points.isNotEmpty()) {
                activeVolumeCurve = VolumeCurve(points)
            }
        }
    }
}