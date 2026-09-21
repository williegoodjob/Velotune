package com.example.velotune.service

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.velotune.core.*
import com.example.velotune.data.ProfileRepository
import com.example.velotune.data.VolumeProfile
import com.example.velotune.system.AudioController
import com.example.velotune.system.BluetoothTracker
import com.example.velotune.system.LocationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch

class AutoVolumeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var audioController: AudioController
    private lateinit var locationProvider: LocationProvider
    private lateinit var profileRepo: ProfileRepository
    private lateinit var btTracker: BluetoothTracker
    private lateinit var volumeSmoother: VolumeSmoother

    private val speedFilter = SpeedFilter(alpha = 0.35f)
    private var activeProfile: VolumeProfile? = null

    // GPS 訊號看門狗變數
    private var lastGpsTimestamp: Long = 0L
    private val GPS_TIMEOUT_MS = 4500L // 4.5 秒未收到定位更新即判定為斷訊 (進隧道/高架下)
    private var isGpsLost = false

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        audioController = AudioController(this)
        locationProvider = LocationProvider(this)
        profileRepo = ProfileRepository(this)

        // 建立音量平滑器
        volumeSmoother = VolumeSmoother(serviceScope, audioController)

        val all = profileRepo.getAllProfiles()
        val activeId = profileRepo.getActiveProfileId()
        val initialProfile = all.find { it.id == activeId } ?: profileRepo.getStarDefaultProfile()
        applyProfile(initialProfile)

        initBluetoothTracker()

        _serviceStateFlow.value = ServiceState.RUNNING
        _liveVolumeRatioFlow.value = audioController.getCurrentVolumeRatio()

        startForegroundServiceNotification()
        startSpeedTracking()
        startGpsWatchdog()
        observeSmoothedVolume()
    }

    private fun observeSmoothedVolume() {
        serviceScope.launch {
            volumeSmoother.smoothedVolumeFlow.collect { smoothRatio ->
                _liveVolumeRatioFlow.value = smoothRatio
                notificationHelper.updateNotification(
                    speedKmh = _liveSpeedFlow.value,
                    volumeRatio = smoothRatio,
                    state = _serviceStateFlow.value,
                    isGpsLost = isGpsLost
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initBluetoothTracker() {
        btTracker = BluetoothTracker(this) { topDevice ->
            handleBluetoothDeviceChange(topDevice)
        }
        btTracker.start()
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothDeviceChange(device: BluetoothDevice?) {
        if (device != null) {
            val matched = profileRepo.findProfileByBtAddress(device.address)
            if (matched != null) {
                applyProfile(matched)
                return
            }
        }
        val starDefault = profileRepo.getStarDefaultProfile()
        applyProfile(starDefault)
    }

    private fun applyProfile(profile: VolumeProfile) {
        activeProfile = profile
        profileRepo.setActiveProfileId(profile.id)
        activeVolumeCurve = VolumeCurve(profile.points)
        _activeProfileNameFlow.value = profile.name
        processVolumeUpdate()
    }

    private fun startForegroundServiceNotification() {
        val initialNotification = notificationHelper.buildNotification(
            speedKmh = _liveSpeedFlow.value,
            volumeRatio = _liveVolumeRatioFlow.value,
            state = _serviceStateFlow.value,
            isGpsLost = isGpsLost
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
                    lastGpsTimestamp = System.currentTimeMillis()

                    // 若剛從斷訊中恢復，重設濾波器防突波
                    if (isGpsLost) {
                        isGpsLost = false
                        _isGpsLostFlow.value = false
                        speedFilter.reset()
                    }

                    val filteredSpeed = speedFilter.filter(rawSpeed)
                    _liveSpeedFlow.value = filteredSpeed
                    processVolumeUpdate()
                }
        }
    }

    /**
     * GPS 斷訊監控看門狗 (每秒檢查一次是否進隧道)
     */
    private fun startGpsWatchdog() {
        serviceScope.launch {
            while (isActive) {
                delay(1000L)
                if (_serviceStateFlow.value == ServiceState.RUNNING && lastGpsTimestamp > 0) {
                    val timeSinceLastGps = System.currentTimeMillis() - lastGpsTimestamp
                    if (timeSinceLastGps > GPS_TIMEOUT_MS && !isGpsLost) {
                        isGpsLost = true
                        _isGpsLostFlow.value = true
                        handleGpsLost()
                    }
                }
            }
        }
    }

    /**
     * 觸發 GPS 斷訊保護 (維持最後已知音量，避免亂調)
     */
    private fun handleGpsLost() {
        notificationHelper.updateNotification(
            speedKmh = _liveSpeedFlow.value,
            volumeRatio = _liveVolumeRatioFlow.value,
            state = _serviceStateFlow.value,
            isGpsLost = true
        )
    }

    private fun processVolumeUpdate() {
        val state = _serviceStateFlow.value
        val rawSpeed = _liveSpeedFlow.value

        val offset = activeProfile?.speedOffsetKmh ?: 0f
        val compensatedSpeed = (rawSpeed + offset).coerceAtLeast(0f)

        when (state) {
            ServiceState.RUNNING -> {
                // 如果目前是斷訊狀態，凍結音量計算，不調整
                if (!isGpsLost) {
                    val targetRatio = activeVolumeCurve.evaluate(compensatedSpeed)
                    // 交付給平滑器慢慢過渡！
                    volumeSmoother.setTargetVolume(targetRatio, immediate = false)
                }
            }
            ServiceState.MUTED -> {
                volumeSmoother.setTargetVolume(0f, immediate = true)
            }
            ServiceState.PAUSED, ServiceState.STOPPED -> {
                // 維持現狀
            }
        }
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
        btTracker.stop()
        _serviceStateFlow.value = ServiceState.STOPPED
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.velotune.ACTION_START"
        const val ACTION_STOP = "com.example.velotune.ACTION_STOP"
        const val ACTION_TOGGLE_PAUSE = "com.example.velotune.ACTION_TOGGLE_PAUSE"
        const val ACTION_TOGGLE_MUTE = "com.example.velotune.ACTION_TOGGLE_MUTE"

        private val _liveSpeedFlow = MutableStateFlow(0f)
        val liveSpeedFlow = _liveSpeedFlow.asStateFlow()

        private val _liveVolumeRatioFlow = MutableStateFlow(0.2f)
        val liveVolumeRatioFlow = _liveVolumeRatioFlow.asStateFlow()

        private val _serviceStateFlow = MutableStateFlow(ServiceState.STOPPED)
        val serviceStateFlow = _serviceStateFlow.asStateFlow()

        private val _activeProfileNameFlow = MutableStateFlow("預設設定檔")
        val activeProfileNameFlow = _activeProfileNameFlow.asStateFlow()

        // GPS 斷訊狀態流
        private val _isGpsLostFlow = MutableStateFlow(false)
        val isGpsLostFlow = _isGpsLostFlow.asStateFlow()

        var activeVolumeCurve: VolumeCurve = VolumeCurve.defaultCurve()
            private set

        fun updateCurveFromEditor(points: List<ControlPoint>) {
            if (points.isNotEmpty()) {
                activeVolumeCurve = VolumeCurve(points)
            }
        }
    }
}