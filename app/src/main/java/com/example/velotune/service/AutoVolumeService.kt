package com.example.velotune.service

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.velotune.core.*
import com.example.velotune.data.*
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
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var btTracker: BluetoothTracker
    private lateinit var volumeSmoother: VolumeSmoother

    private val speedFilter = SpeedFilter(alpha = 0.35f)
    private var activeProfile: VolumeProfile? = null

    // 斷訊看門狗
    private var lastGpsTimestamp: Long = 0L
    private var isGpsLost = false

    // 靜音計時解鎖
    private var muteTimestamp: Long = 0L

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        audioController = AudioController(this)
        locationProvider = LocationProvider(this)
        profileRepo = ProfileRepository(this)
        settingsRepo = SettingsRepository(this)

        val settings = settingsRepo.getSettings()
        volumeSmoother = VolumeSmoother(
            scope = serviceScope,
            audioController = audioController,
            maxStepDelta = settings.smoothingLevel.delta
        )

        val all = profileRepo.getAllProfiles()
        val activeId = profileRepo.getActiveProfileId()
        val initialProfile = all.find { it.id == activeId } ?: profileRepo.getStarDefaultProfile()
        applyProfile(initialProfile)

        initBluetoothTracker()

        _serviceStateFlow.value = ServiceState.RUNNING
        _liveVolumeRatioFlow.value = audioController.getCurrentVolumeRatio()

        startForegroundServiceNotification()
        startSpeedTracking()
        startWatchdogs()
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
        val settings = settingsRepo.getSettings()
        if (device != null) {
            val matched = profileRepo.findProfileByBtAddress(device.address)
            if (matched != null) {
                applyProfile(matched)
                return
            }
        }
        if (settings.fallbackToStarProfileOnBtDisconnect) {
            val starDefault = profileRepo.getStarDefaultProfile()
            applyProfile(starDefault)
        }
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

                    if (isGpsLost) {
                        isGpsLost = false
                        _isGpsLostFlow.value = false
                        speedFilter.reset()
                    }

                    val filteredSpeed = speedFilter.filter(rawSpeed)
                    _liveSpeedFlow.value = filteredSpeed

                    // 檢查【起步自動解靜音】
                    val settings = settingsRepo.getSettings()
                    if (_serviceStateFlow.value == ServiceState.MUTED && settings.muteAutoResumeBySpeed) {
                        if (filteredSpeed >= settings.muteResumeSpeedThreshold) {
                            _serviceStateFlow.value = ServiceState.RUNNING
                        }
                    }

                    processVolumeUpdate()
                }
        }
    }

    /**
     * 定時看門狗 (GPS 斷訊判定 + 靜音超時自動解除)
     */
    private fun startWatchdogs() {
        serviceScope.launch {
            while (isActive) {
                delay(1000L)
                val settings = settingsRepo.getSettings()
                val now = System.currentTimeMillis()

                // 1. GPS 斷訊判定
                if (_serviceStateFlow.value == ServiceState.RUNNING && lastGpsTimestamp > 0) {
                    val timeSinceLastGps = now - lastGpsTimestamp
                    if (timeSinceLastGps > settings.gpsTimeoutMs && !isGpsLost) {
                        isGpsLost = true
                        _isGpsLostFlow.value = true
                        handleGpsLost(settings)
                    }
                }

                // 2. 靜音超時自動恢復判定
                if (_serviceStateFlow.value == ServiceState.MUTED && settings.muteResumeTimeoutSec > 0 && muteTimestamp > 0) {
                    if (now - muteTimestamp >= settings.muteResumeTimeoutSec * 1000L) {
                        _serviceStateFlow.value = ServiceState.RUNNING
                        processVolumeUpdate()
                    }
                }
            }
        }
    }

    private fun handleGpsLost(settings: AppSettings) {
        when (settings.gpsLossAction) {
            GpsLossAction.KEEP_LAST -> {
                // 維持最後音量，不調動
            }
            GpsLossAction.DROP_TO_SAFE -> {
                volumeSmoother.setTargetVolume(settings.gpsSafeVolumeRatio, immediate = false)
            }
            GpsLossAction.PAUSE_CONTROL -> {
                _serviceStateFlow.value = ServiceState.PAUSED
            }
        }

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
                if (!isGpsLost) {
                    val targetRatio = activeVolumeCurve.evaluate(compensatedSpeed)
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
                if (_serviceStateFlow.value == ServiceState.MUTED) {
                    _serviceStateFlow.value = ServiceState.RUNNING
                    muteTimestamp = 0L
                } else {
                    _serviceStateFlow.value = ServiceState.MUTED
                    muteTimestamp = System.currentTimeMillis()
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