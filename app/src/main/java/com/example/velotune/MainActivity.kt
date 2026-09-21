package com.example.velotune

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.velotune.core.ControlPoint
import com.example.velotune.core.ServiceState
import com.example.velotune.data.*
import com.example.velotune.system.*
import com.example.velotune.service.AutoVolumeService
import com.example.velotune.ui.VolumeCurveEditorView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var profileRepo: ProfileRepository
    private lateinit var settingsRepo: SettingsRepository

    private lateinit var activeProfile: VolumeProfile
    private val currentPoints = mutableListOf<ControlPoint>()

    private var hasUnsavedChanges = false
    private var highlightedPointIndex = -1

    // UI 動態元件
    private lateinit var curveEditorView: VolumeCurveEditorView
    private lateinit var pointsContainerLayout: LinearLayout
    private lateinit var profileStatusText: TextView
    private lateinit var serviceStateBadge: TextView
    private lateinit var liveSpeedValText: TextView
    private lateinit var liveVolumeValText: TextView

    private lateinit var quickSaveBtn: Button
    private lateinit var quickRevertBtn: Button

    private lateinit var startBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var stopBtn: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else true

        if (btGranted) {
            checkConnectedBluetoothOnLaunch()
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { saveExportFileToUri(it) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { readImportFileFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        requestRequiredPermissions()

        profileRepo = ProfileRepository(this)
        settingsRepo = SettingsRepository(this)
        loadInitialProfile()

        // 👈 加入此行：啟動時檢查是否已經連線藍牙
        checkConnectedBluetoothOnLaunch()

        if (settingsRepo.getSettings().autoStartServiceOnAppOpen) {
            sendServiceAction(AutoVolumeService.ACTION_START)
        }

        buildResponsiveUi()
        observeServiceData()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildResponsiveUi()
        syncCurrentUiState()
    }

    private fun loadInitialProfile() {
        val all = profileRepo.getAllProfiles()
        val activeId = profileRepo.getActiveProfileId()
        activeProfile = all.find { it.id == activeId } ?: profileRepo.getStarDefaultProfile()

        currentPoints.clear()
        currentPoints.addAll(activeProfile.points.sortedBy { it.speedKmh })
        hasUnsavedChanges = false

        AutoVolumeService.updateCurveFromEditor(currentPoints)
    }

    /**
     * App 啟動或取得權限後，主動檢查是否已連線至已綁定的藍牙裝置
     */
    private fun checkConnectedBluetoothOnLaunch() {
        BluetoothTracker.queryConnectedAudioDevices(this) { activeDevices ->
            for (device in activeDevices) {
                val matched = profileRepo.findProfileByBtAddress(device.address)
                if (matched != null && matched.id != activeProfile.id) {
                    profileRepo.setActiveProfileId(matched.id)
                    activeProfile = matched
                    currentPoints.clear()
                    currentPoints.addAll(matched.points.sortedBy { it.speedKmh })
                    this@MainActivity.hasUnsavedChanges = false
                    highlightedPointIndex = -1

                    curveEditorView.setHighlightedIndex(-1)
                    curveEditorView.setControlPoints(currentPoints)
                    AutoVolumeService.updateCurveFromEditor(currentPoints)

                    refreshPointsListUi()
                    updateProfileStatusUi()
                    Toast.makeText(this@MainActivity, "已自動識別藍牙裝置，載入「${matched.name}」", Toast.LENGTH_SHORT).show()
                    break
                }
            }
        }
    }
    /**
     * 核心響應式畫面構建：自動區分直屏 (Portrait) 與橫屏 (Landscape)
     */
    private fun buildResponsiveUi() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            buildLandscapeDualPaneUi()
        } else {
            buildPortraitUi()
        }

        refreshPointsListUi()
        updateProfileStatusUi()
    }

    // ==========================================
    // 橫屏模式：雙欄 HUD 儀表板佈局
    // ==========================================
    private fun buildLandscapeDualPaneUi() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0E1116"))
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        }

        // --- 左側欄位：HUD 數據 + 滿版 2D 曲線 (佔 55% 寬度) ---
        val leftPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f).apply {
                marginEnd = dpToPx(10)
            }
        }

        // 左上角 HUD 儀表橫條
        val hudCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createCardBackground(Color.parseColor("#181B22"), 12f)
            setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
        }

        val speedCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val speedTitle = TextView(this).apply {
            text = "即時車速"
            textSize = 11f
            setTextColor(Color.parseColor("#8C93A4"))
        }
        liveSpeedValText = TextView(this).apply {
            text = "0.0 km/h"
            textSize = 22f
            setTextColor(Color.parseColor("#00FF66"))
            typeface = Typeface.DEFAULT_BOLD
        }
        speedCol.addView(speedTitle)
        speedCol.addView(liveSpeedValText)

        val volumeCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val volumeTitle = TextView(this).apply {
            text = "即時音量"
            textSize = 11f
            setTextColor(Color.parseColor("#8C93A4"))
        }
        liveVolumeValText = TextView(this).apply {
            text = "20%"
            textSize = 22f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.DEFAULT_BOLD
        }
        volumeCol.addView(volumeTitle)
        volumeCol.addView(liveVolumeValText)

        serviceStateBadge = TextView(this).apply {
            text = "未啟動"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            background = createCardBackground(Color.parseColor("#3C4453"), 6f)
        }

        hudCard.addView(speedCol)
        hudCard.addView(volumeCol)
        hudCard.addView(serviceStateBadge)

        // 左下角全高滿版 2D 曲線編輯器
        curveEditorView = createCurveEditorView().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        leftPane.addView(hudCard)
        leftPane.addView(curveEditorView)

        // --- 右側欄位：操作、設定與列表 (佔 45% 寬度，獨立捲動) ---
        val rightScrollView = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        val rightPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val profileControlCard = createLandscapeProfileHeaderCard()
        val actionControls = createFullControlPanel()

        val listHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(2), dpToPx(6), dpToPx(2), dpToPx(6))
        }
        val listTitle = TextView(this).apply {
            text = "錨點微調"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addPointBtn = Button(this).apply {
            text = "+ 新增錨點"
            setTextColor(Color.parseColor("#00E5FF"))
            background = createCardBackground(Color.parseColor("#1F2430"), 6f)
            setOnClickListener { addNewPoint() }
        }
        listHeader.addView(listTitle)
        listHeader.addView(addPointBtn)

        pointsContainerLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        rightPane.addView(profileControlCard)
        rightPane.addView(actionControls)
        rightPane.addView(listHeader)
        rightPane.addView(pointsContainerLayout)
        rightScrollView.addView(rightPane)

        rootLayout.addView(leftPane)
        rootLayout.addView(rightScrollView)
        setContentView(rootLayout)
    }

    private fun createLandscapeProfileHeaderCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardBackground(Color.parseColor("#181B22"), 12f)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        profileStatusText = TextView(this).apply {
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val settingsBtn = Button(this).apply {
            text = "⚙️"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = createCardBackground(Color.parseColor("#262B36"), 6f)
            layoutParams = LinearLayout.LayoutParams(dpToPx(34), dpToPx(30))
            setOnClickListener { showSettingsDialog() }
        }
        row1.addView(profileStatusText)
        row1.addView(settingsBtn)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(8), 0, 0)
        }
        val openListBtn = Button(this).apply {
            text = "📑 清單"
            textSize = 11f
            setTextColor(Color.parseColor("#00E5FF"))
            background = createCardBackground(Color.parseColor("#262B36"), 6f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(30), 1f).apply { marginEnd = dpToPx(4) }
            setOnClickListener { showFlatProfileListDialog() }
        }
        quickSaveBtn = Button(this).apply {
            text = "💾 儲存"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = createCardBackground(Color.parseColor("#059669"), 6f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(30), 1f).apply { marginEnd = dpToPx(4) }
            visibility = View.GONE
            setOnClickListener { saveCurrentProfile() }
        }
        quickRevertBtn = Button(this).apply {
            text = "🔄 還原"
            textSize = 11f
            setTextColor(Color.parseColor("#FF9F0A"))
            background = createCardBackground(Color.parseColor("#2C2418"), 6f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(30), 1f)
            visibility = View.GONE
            setOnClickListener { revertChanges() }
        }

        row2.addView(openListBtn)
        row2.addView(quickSaveBtn)
        row2.addView(quickRevertBtn)

        card.addView(row1)
        card.addView(row2)
        return card
    }

    // ==========================================
    // 直屏模式：單欄垂直卡片佈局
    // ==========================================
    private fun buildPortraitUi() {
        val rootScrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#0E1116"))
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(32))
        }

        val dashboardCard = createPortraitDashboardCard()
        val actionControls = createFullControlPanel()

        curveEditorView = createCurveEditorView().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(240)
            ).apply { setMargins(0, dpToPx(16), 0, dpToPx(16)) }
        }

        val listHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(4), dpToPx(8), dpToPx(4), dpToPx(8))
        }
        val listTitle = TextView(this).apply {
            text = "錨點微調"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addPointBtn = Button(this).apply {
            text = "+ 新增錨點"
            setTextColor(Color.parseColor("#00E5FF"))
            background = createCardBackground(Color.parseColor("#1F2430"), 8f)
            setOnClickListener { addNewPoint() }
        }
        listHeader.addView(listTitle)
        listHeader.addView(addPointBtn)

        pointsContainerLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        mainLayout.addView(dashboardCard)
        mainLayout.addView(actionControls)
        mainLayout.addView(curveEditorView)
        mainLayout.addView(listHeader)
        mainLayout.addView(pointsContainerLayout)

        rootScrollView.addView(mainLayout)
        setContentView(rootScrollView)
    }

    private fun createPortraitDashboardCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardBackground(Color.parseColor("#181B22"), 16f)
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        val topStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        profileStatusText = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val settingsBtn = Button(this).apply {
            text = "⚙️"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = createCardBackground(Color.parseColor("#262B36"), 6f)
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(32)).apply { marginEnd = dpToPx(8) }
            setOnClickListener { showSettingsDialog() }
        }

        serviceStateBadge = TextView(this).apply {
            text = "未啟動"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            background = createCardBackground(Color.parseColor("#3C4453"), 6f)
        }

        topStatusRow.addView(profileStatusText)
        topStatusRow.addView(settingsBtn)
        topStatusRow.addView(serviceStateBadge)

        val profileActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(10), 0, 0)
        }

        val openListBtn = Button(this).apply {
            text = "📑 設定檔清單"
            textSize = 12f
            setTextColor(Color.parseColor("#00E5FF"))
            background = createCardBackground(Color.parseColor("#262B36"), 6f)
            setPadding(dpToPx(10), 0, dpToPx(10), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(32)).apply { marginEnd = dpToPx(8) }
            setOnClickListener { showFlatProfileListDialog() }
        }

        quickSaveBtn = Button(this).apply {
            text = "💾 儲存"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardBackground(Color.parseColor("#059669"), 6f)
            setPadding(dpToPx(10), 0, dpToPx(10), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(32)).apply { marginEnd = dpToPx(8) }
            visibility = View.GONE
            setOnClickListener { saveCurrentProfile() }
        }

        quickRevertBtn = Button(this).apply {
            text = "🔄 還原"
            textSize = 12f
            setTextColor(Color.parseColor("#FF9F0A"))
            background = createCardBackground(Color.parseColor("#2C2418"), 6f)
            setPadding(dpToPx(10), 0, dpToPx(10), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(32))
            visibility = View.GONE
            setOnClickListener { revertChanges() }
        }

        profileActionRow.addView(openListBtn)
        profileActionRow.addView(quickSaveBtn)
        profileActionRow.addView(quickRevertBtn)

        val metricRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(14), 0, 0)
        }

        val speedCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val speedTitle = TextView(this).apply {
            text = "即時車速"
            textSize = 12f
            setTextColor(Color.parseColor("#8C93A4"))
        }
        liveSpeedValText = TextView(this).apply {
            text = "0.0 km/h"
            textSize = 24f
            setTextColor(Color.parseColor("#00FF66"))
            typeface = Typeface.DEFAULT_BOLD
        }
        speedCol.addView(speedTitle)
        speedCol.addView(liveSpeedValText)

        val volumeCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val volumeTitle = TextView(this).apply {
            text = "即時音量"
            textSize = 12f
            setTextColor(Color.parseColor("#8C93A4"))
        }
        liveVolumeValText = TextView(this).apply {
            text = "20%"
            textSize = 24f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.DEFAULT_BOLD
        }
        volumeCol.addView(volumeTitle)
        volumeCol.addView(liveVolumeValText)

        metricRow.addView(speedCol)
        metricRow.addView(volumeCol)

        card.addView(topStatusRow)
        card.addView(profileActionRow)
        card.addView(metricRow)
        return card
    }

    private fun createCurveEditorView(): VolumeCurveEditorView {
        return VolumeCurveEditorView(this).apply {
            setControlPoints(currentPoints)
            onPointsChangedListener = { updatedPoints ->
                currentPoints.clear()
                currentPoints.addAll(updatedPoints)
                markProfileAsDirty()
                AutoVolumeService.updateCurveFromEditor(currentPoints)
                refreshPointsListUi()
            }
            onPointSelectedListener = { selectedIdx ->
                highlightedPointIndex = selectedIdx
                refreshPointsListUi()
            }
        }
    }

    private fun createFullControlPanel(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(6), 0, 0)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        startBtn = Button(this).apply {
            text = "啟動"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = createCardBackground(Color.parseColor("#0070F3"), 8f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f).apply { marginEnd = dpToPx(4) }
            setOnClickListener { sendServiceAction(AutoVolumeService.ACTION_START) }
        }

        pauseBtn = Button(this).apply {
            text = "暫停"
            setTextColor(Color.WHITE)
            background = createCardBackground(Color.parseColor("#262B36"), 8f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f).apply {
                marginStart = dpToPx(4)
                marginEnd = dpToPx(4)
            }
            setOnClickListener { sendServiceAction(AutoVolumeService.ACTION_TOGGLE_PAUSE) }
        }

        muteBtn = Button(this).apply {
            text = "靜音"
            setTextColor(Color.WHITE)
            background = createCardBackground(Color.parseColor("#262B36"), 8f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f).apply {
                marginStart = dpToPx(4)
                marginEnd = dpToPx(4)
            }
            setOnClickListener { sendServiceAction(AutoVolumeService.ACTION_TOGGLE_MUTE) }
        }

        stopBtn = Button(this).apply {
            text = "停止"
            setTextColor(Color.parseColor("#FF453A"))
            background = createCardBackground(Color.parseColor("#262B36"), 8f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f).apply { marginStart = dpToPx(4) }
            setOnClickListener { sendServiceAction(AutoVolumeService.ACTION_STOP) }
        }

        row.addView(startBtn)
        row.addView(pauseBtn)
        row.addView(muteBtn)
        row.addView(stopBtn)
        container.addView(row)
        return container
    }

    private fun refreshPointsListUi() {
        pointsContainerLayout.removeAllViews()

        for (i in currentPoints.indices) {
            val pt = currentPoints[i]
            val isSelected = (i == highlightedPointIndex)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (isSelected) {
                    createBorderedCardBackground(Color.parseColor("#1F2736"), Color.parseColor("#00E5FF"), 8f, 2)
                } else {
                    createCardBackground(Color.parseColor("#181B22"), 8f)
                }
                setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dpToPx(4), 0, dpToPx(4)) }

                setOnClickListener {
                    highlightedPointIndex = i
                    curveEditorView.setHighlightedIndex(i)
                    refreshPointsListUi()
                }
            }

            val indexLabel = TextView(this).apply {
                text = "P${i + 1}"
                setTextColor(if (isSelected) Color.parseColor("#00E5FF") else Color.parseColor("#8C93A4"))
                typeface = Typeface.DEFAULT_BOLD
                textSize = 13f
                setPadding(0, 0, dpToPx(8), 0)
            }

            val speedEdit = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(pt.speedKmh.toInt().toString())
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                isEnabled = (i != 0)
                background = createCardBackground(Color.parseColor("#242A36"), 6f)
                layoutParams = LinearLayout.LayoutParams(dpToPx(52), dpToPx(34))

                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val newSpeed = text.toString().toFloatOrNull() ?: pt.speedKmh
                        if (newSpeed != pt.speedKmh) {
                            commitAndSortPoint(i, newSpeed, pt.volumeRatio)
                        }
                    }
                }
            }

            val unitKm = TextView(this).apply {
                text = "km/h"
                setTextColor(Color.parseColor("#8C93A4"))
                textSize = 12f
                setPadding(dpToPx(4), 0, dpToPx(10), 0)
            }

            val volEdit = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText((pt.volumeRatio * 100).toInt().toString())
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                background = createCardBackground(Color.parseColor("#242A36"), 6f)
                layoutParams = LinearLayout.LayoutParams(dpToPx(52), dpToPx(34))

                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val newVol = text.toString().toFloatOrNull() ?: (pt.volumeRatio * 100)
                        val ratio = (newVol / 100f).coerceIn(0f, 1f)
                        if (ratio != pt.volumeRatio) {
                            commitAndSortPoint(i, pt.speedKmh, ratio)
                        }
                    }
                }
            }

            val unitPercent = TextView(this).apply {
                text = "%"
                setTextColor(Color.parseColor("#8C93A4"))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dpToPx(4), 0, 0, 0)
            }

            val deleteBtn = Button(this).apply {
                text = "✕"
                textSize = 11f
                setTextColor(if (currentPoints.size > 2 && i != 0) Color.WHITE else Color.GRAY)
                background = createCardBackground(
                    if (currentPoints.size > 2 && i != 0) Color.parseColor("#FF453A") else Color.parseColor("#332222"),
                    6f
                )
                layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32))
                isEnabled = (currentPoints.size > 2 && i != 0)
                setOnClickListener {
                    currentPoints.removeAt(i)
                    highlightedPointIndex = -1
                    curveEditorView.setHighlightedIndex(-1)
                    curveEditorView.setControlPoints(currentPoints)
                    markProfileAsDirty()
                    AutoVolumeService.updateCurveFromEditor(currentPoints)
                    refreshPointsListUi()
                }
            }

            row.addView(indexLabel)
            row.addView(speedEdit)
            row.addView(unitKm)
            row.addView(volEdit)
            row.addView(unitPercent)
            row.addView(deleteBtn)

            pointsContainerLayout.addView(row)
        }
    }

    private fun commitAndSortPoint(originIndex: Int, newSpeed: Float, newRatio: Float) {
        val targetPoint = ControlPoint(newSpeed, newRatio)
        currentPoints[originIndex] = targetPoint

        currentPoints.sortBy { it.speedKmh }
        if (currentPoints.first().speedKmh != 0f) {
            currentPoints[0] = currentPoints[0].copy(speedKmh = 0f)
        }

        highlightedPointIndex = currentPoints.indexOf(targetPoint)
        curveEditorView.setHighlightedIndex(highlightedPointIndex)
        curveEditorView.setControlPoints(currentPoints)

        markProfileAsDirty()
        AutoVolumeService.updateCurveFromEditor(currentPoints)
        refreshPointsListUi()
    }

    private fun addNewPoint() {
        val maxSpeed = curveEditorView.dynamicMaxSpeed
        val lastSpeed = currentPoints.lastOrNull()?.speedKmh ?: 60f
        val newSpeed = (lastSpeed + 20f).coerceAtMost(maxSpeed + 20f)
        val newPoint = ControlPoint(newSpeed, 0.8f)

        currentPoints.add(newPoint)
        currentPoints.sortBy { it.speedKmh }

        highlightedPointIndex = currentPoints.indexOf(newPoint)
        curveEditorView.setHighlightedIndex(highlightedPointIndex)
        curveEditorView.setControlPoints(currentPoints)

        markProfileAsDirty()
        AutoVolumeService.updateCurveFromEditor(currentPoints)
        refreshPointsListUi()
    }

    private fun markProfileAsDirty() {
        if (!hasUnsavedChanges) {
            hasUnsavedChanges = true
            updateProfileStatusUi()
        }
    }

    private fun updateProfileStatusUi() {
        if (hasUnsavedChanges) {
            profileStatusText.text = "● ${activeProfile.name} (未儲存)*"
            profileStatusText.setTextColor(Color.parseColor("#FF9F0A"))
            quickSaveBtn.visibility = View.VISIBLE
            quickRevertBtn.visibility = View.VISIBLE
        } else {
            profileStatusText.text = "● ${activeProfile.name}"
            profileStatusText.setTextColor(Color.parseColor("#30D158"))
            quickSaveBtn.visibility = View.GONE
            quickRevertBtn.visibility = View.GONE
        }
    }

    private fun syncCurrentUiState() {
        liveSpeedValText.text = "%.1f km/h".format(AutoVolumeService.liveSpeedFlow.value)
        liveVolumeValText.text = "${(AutoVolumeService.liveVolumeRatioFlow.value * 100).toInt()}%"
        curveEditorView.setLiveIndicator(AutoVolumeService.liveSpeedFlow.value, AutoVolumeService.liveVolumeRatioFlow.value)
        updateControlsByState(AutoVolumeService.serviceStateFlow.value)
    }

    private fun observeServiceData() {
        activityScope.launch {
            AutoVolumeService.liveSpeedFlow.collect { speed ->
                liveSpeedValText.text = "%.1f km/h".format(speed)
                curveEditorView.setLiveIndicator(speed, AutoVolumeService.liveVolumeRatioFlow.value)
            }
        }

        activityScope.launch {
            AutoVolumeService.liveVolumeRatioFlow.collect { ratio ->
                liveVolumeValText.text = "${(ratio * 100).toInt()}%"
                curveEditorView.setLiveIndicator(AutoVolumeService.liveSpeedFlow.value, ratio)
            }
        }

        activityScope.launch {
            AutoVolumeService.activeProfileNameFlow.collect { profileName ->
                if (profileName != activeProfile.name) {
                    val all = profileRepo.getAllProfiles()
                    all.find { it.name == profileName }?.let { switched ->
                        activeProfile = switched
                        currentPoints.clear()
                        currentPoints.addAll(switched.points.sortedBy { it.speedKmh })
                        this@MainActivity.hasUnsavedChanges = false
                        curveEditorView.setControlPoints(currentPoints)
                        refreshPointsListUi()
                        updateProfileStatusUi()
                    }
                }
            }
        }

        activityScope.launch {
            AutoVolumeService.serviceStateFlow.collect { state ->
                updateControlsByState(state)
            }
        }

        activityScope.launch {
            AutoVolumeService.isGpsLostFlow.collect { isLost ->
                if (isLost) {
                    liveSpeedValText.text = "⚠️ 訊號中斷"
                    liveSpeedValText.setTextColor(Color.parseColor("#FF9F0A"))
                } else {
                    liveSpeedValText.setTextColor(Color.parseColor("#00FF66"))
                }
            }
        }
        // 觀察導航避讓狀態
        activityScope.launch {
            AutoVolumeService.isDuckingFlow.collect { isDucking ->
                if (isDucking) {
                    serviceStateBadge.text = "🗣️ 導航避讓"
                    serviceStateBadge.background = createCardBackground(Color.parseColor("#6366F1"), 6f) // 亮靛藍色
                } else {
                    updateControlsByState(AutoVolumeService.serviceStateFlow.value)
                }
            }
        }
    }

    private fun updateControlsByState(state: ServiceState) {
        when (state) {
            ServiceState.RUNNING -> {
                serviceStateBadge.text = "運行中"
                serviceStateBadge.background = createCardBackground(Color.parseColor("#059669"), 6f)
                startBtn.isEnabled = false
                pauseBtn.isEnabled = true
                pauseBtn.text = "暫停"
                pauseBtn.background = createCardBackground(Color.parseColor("#262B36"), 8f)
                muteBtn.isEnabled = true
                muteBtn.text = "靜音"
                muteBtn.background = createCardBackground(Color.parseColor("#262B36"), 8f)
                stopBtn.isEnabled = true
            }
            ServiceState.PAUSED -> {
                serviceStateBadge.text = "已暫停"
                serviceStateBadge.background = createCardBackground(Color.parseColor("#D97706"), 6f)
                startBtn.isEnabled = false
                pauseBtn.isEnabled = true
                pauseBtn.text = "繼續"
                pauseBtn.background = createCardBackground(Color.parseColor("#D97706"), 8f)
                muteBtn.isEnabled = true
                stopBtn.isEnabled = true
            }
            ServiceState.MUTED -> {
                serviceStateBadge.text = "已靜音"
                serviceStateBadge.background = createCardBackground(Color.parseColor("#DC2626"), 6f)
                startBtn.isEnabled = false
                pauseBtn.isEnabled = true
                muteBtn.isEnabled = true
                muteBtn.text = "恢復"
                muteBtn.background = createCardBackground(Color.parseColor("#DC2626"), 8f)
                stopBtn.isEnabled = true
            }
            ServiceState.STOPPED -> {
                serviceStateBadge.text = "未啟動"
                serviceStateBadge.background = createCardBackground(Color.parseColor("#374151"), 6f)
                startBtn.isEnabled = true
                pauseBtn.isEnabled = false
                pauseBtn.text = "暫停"
                pauseBtn.background = createCardBackground(Color.parseColor("#1F2430"), 8f)
                muteBtn.isEnabled = false
                muteBtn.text = "靜音"
                muteBtn.background = createCardBackground(Color.parseColor("#1F2430"), 8f)
                stopBtn.isEnabled = false
            }
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, AutoVolumeService::class.java).apply { this.action = action }
        if (action == AutoVolumeService.ACTION_START) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun showSettingsDialog() {
        var settings = settingsRepo.getSettings()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#181B22"))
            setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(20))
        }

        val title = TextView(this).apply {
            text = "Velotune 全域偏好設定"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dpToPx(16))
        }
        container.addView(title)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(340)
            )
        }
        val contentLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        contentLayout.addView(createSectionHeader("GPS 訊號中斷保護"))

        val actionBtn = Button(this).apply {
            val label = when (settings.gpsLossAction) {
                GpsLossAction.KEEP_LAST -> "維持最後車速音量"
                GpsLossAction.DROP_TO_SAFE -> "降至安全音量 (25%)"
                GpsLossAction.PAUSE_CONTROL -> "暫停自動調音"
                else -> "維持最後車速音量"
            }
            text = "斷訊處置: $label ▼"
            textSize = 12f
            setTextColor(Color.parseColor("#00E5FF"))
            background = createCardBackground(Color.parseColor("#222631"), 6f)
            setOnClickListener {
                val actions = arrayOf("維持最後車速音量", "降至安全音量 (25%)", "暫停自動調音")
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("選擇 GPS 斷訊保護行為")
                    .setItems(actions) { _, which ->
                        val selectedAction = when (which) {
                            0 -> GpsLossAction.KEEP_LAST
                            1 -> GpsLossAction.DROP_TO_SAFE
                            else -> GpsLossAction.PAUSE_CONTROL
                        }
                        settings = settings.copy(gpsLossAction = selectedAction)
                        settingsRepo.saveSettings(settings)
                        text = "斷訊處置: ${actions[which]} ▼"
                    }
                    .show()
            }
        }
        contentLayout.addView(actionBtn)

        contentLayout.addView(createSectionHeader("暫時靜音自動解除"))

        val speedResumeSwitch = CheckBox(this).apply {
            text = "起步加速自動解靜音 (超過 15 km/h)"
            setTextColor(Color.WHITE)
            isChecked = settings.muteAutoResumeBySpeed
            setOnCheckedChangeListener { _, isChecked ->
                settings = settings.copy(muteAutoResumeBySpeed = isChecked)
                settingsRepo.saveSettings(settings)
            }
        }
        contentLayout.addView(speedResumeSwitch)

        val timeoutResumeBtn = Button(this).apply {
            val sec = settings.muteResumeTimeoutSec
            text = if (sec > 0) "超時自動恢復: $sec 秒 ▼" else "超時自動恢復: 關閉 ▼"
            textSize = 12f
            setTextColor(Color.parseColor("#00E5FF"))
            background = createCardBackground(Color.parseColor("#222631"), 6f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(38)).apply { topMargin = dpToPx(6) }
            setOnClickListener {
                val opts = arrayOf("關閉 (僅手動解靜音)", "30 秒", "60 秒", "120 秒")
                val vals = intArrayOf(0, 30, 60, 120)
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("靜音超時自動恢復時間")
                    .setItems(opts) { _, which ->
                        settings = settings.copy(muteResumeTimeoutSec = vals[which])
                        settingsRepo.saveSettings(settings)
                        text = "超時自動恢復: ${opts[which]} ▼"
                    }
                    .show()
            }
        }
        contentLayout.addView(timeoutResumeBtn)

        contentLayout.addView(createSectionHeader("音量平滑調整速度"))

        val smoothBtn = Button(this).apply {
            text = "反應速率: ${settings.smoothingLevel.label} ▼"
            textSize = 12f
            setTextColor(Color.parseColor("#00E5FF"))
            background = createCardBackground(Color.parseColor("#222631"), 6f)
            setOnClickListener {
                val levels = SmoothingLevel.values()
                val names = levels.map { it.label }.toTypedArray()
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("選擇調音反應速度")
                    .setItems(names) { _, which ->
                        settings = settings.copy(smoothingLevel = levels[which])
                        settingsRepo.saveSettings(settings)
                        text = "反應速率: ${levels[which].label} ▼"
                        Toast.makeText(this@MainActivity, "重啟服務後生效新平滑速率", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
        contentLayout.addView(smoothBtn)

        contentLayout.addView(createSectionHeader("啟動與自動化"))

        val autoStartSwitch = CheckBox(this).apply {
            text = "開啟 App 時自動啟動調音服務"
            setTextColor(Color.WHITE)
            isChecked = settings.autoStartServiceOnAppOpen
            setOnCheckedChangeListener { _, isChecked ->
                settings = settings.copy(autoStartServiceOnAppOpen = isChecked)
                settingsRepo.saveSettings(settings)
            }
        }
        contentLayout.addView(autoStartSwitch)

        val fallbackStarSwitch = CheckBox(this).apply {
            text = "藍牙全斷開時切回 ⭐ 預設設定檔"
            setTextColor(Color.WHITE)
            isChecked = settings.fallbackToStarProfileOnBtDisconnect
            setOnCheckedChangeListener { _, isChecked ->
                settings = settings.copy(fallbackToStarProfileOnBtDisconnect = isChecked)
                settingsRepo.saveSettings(settings)
            }
        }
        contentLayout.addView(fallbackStarSwitch)

        scrollView.addView(contentLayout)
        container.addView(scrollView)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(container)
            .setPositiveButton("完成", null)
            .show()
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#8C93A4"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(12), 0, dpToPx(4))
        }
    }

    private fun showFlatProfileListDialog() {
        val allProfiles = profileRepo.getAllProfiles()

        val dialogContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#181B22"))
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        val title = TextView(this).apply {
            text = "設定檔管理"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dpToPx(12))
        }
        dialogContainer.addView(title)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(240)
            )
        }
        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        var dialogRef: AlertDialog? = null

        for (p in allProfiles) {
            val isActive = (p.id == activeProfile.id)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (isActive) {
                    createBorderedCardBackground(Color.parseColor("#1F2736"), Color.parseColor("#00E5FF"), 8f, 1)
                } else {
                    createCardBackground(Color.parseColor("#222631"), 8f)
                }
                setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dpToPx(4), 0, dpToPx(4)) }

                setOnClickListener {
                    if (p.id != activeProfile.id) {
                        profileRepo.setActiveProfileId(p.id)
                        activeProfile = p
                        currentPoints.clear()
                        currentPoints.addAll(p.points.sortedBy { it.speedKmh })
                        this@MainActivity.hasUnsavedChanges = false
                        highlightedPointIndex = -1

                        curveEditorView.setHighlightedIndex(-1)
                        curveEditorView.setControlPoints(currentPoints)
                        AutoVolumeService.updateCurveFromEditor(currentPoints)

                        refreshPointsListUi()
                        updateProfileStatusUi()
                        Toast.makeText(this@MainActivity, "已切換至「${p.name}」", Toast.LENGTH_SHORT).show()
                    }
                    dialogRef?.dismiss()
                }
            }

            val starBtn = TextView(this).apply {
                text = if (p.isDefaultStar) "★" else "☆"
                setTextColor(if (p.isDefaultStar) Color.parseColor("#FFD700") else Color.parseColor("#6B7280"))
                textSize = 18f
                setPadding(0, 0, dpToPx(8), 0)
                setOnClickListener {
                    if (!p.isDefaultStar) {
                        profileRepo.setStarDefaultProfile(p.id)
                        dialogRef?.dismiss()
                        showFlatProfileListDialog()
                        Toast.makeText(this@MainActivity, "已將「${p.name}」設為基準預設檔", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameText = TextView(this).apply {
                text = p.name + if (p.isDefaultStar) " [預設基準]" else ""
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            val btStatus = if (p.boundBtName != null) "🔗 ${p.boundBtName}" else "未綁定藍牙"
            val descText = TextView(this).apply {
                text = "${p.points.size} 個錨點 | $btStatus"
                setTextColor(if (p.boundBtName != null) Color.parseColor("#00E5FF") else Color.parseColor("#8C93A4"))
                textSize = 11f
            }
            infoCol.addView(nameText)
            infoCol.addView(descText)

            val bindBtBtn = Button(this).apply {
                text = if (p.boundBtAddress == null) "綁定" else "解綁"
                textSize = 10f
                setTextColor(Color.WHITE)
                background = createCardBackground(Color.parseColor("#374151"), 4f)
                layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(32)).apply { marginEnd = dpToPx(4) }
                setOnClickListener {
                    dialogRef?.dismiss()
                    if (p.boundBtAddress == null) {
                        showBindBluetoothDialog(p)
                    } else {
                        unbindBluetooth(p)
                    }
                }
            }

            row.addView(starBtn)
            row.addView(infoCol)
            row.addView(bindBtBtn)

            if (!p.isDefaultStar) {
                val delBtn = Button(this).apply {
                    text = "✕"
                    textSize = 11f
                    setTextColor(Color.parseColor("#FF453A"))
                    background = createCardBackground(Color.parseColor("#331F22"), 4f)
                    layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32))
                    setOnClickListener {
                        allProfiles.removeAll { it.id == p.id }
                        profileRepo.saveAllProfiles(allProfiles)

                        if (p.id == activeProfile.id) {
                            loadInitialProfile()
                            curveEditorView.setControlPoints(currentPoints)
                            refreshPointsListUi()
                            updateProfileStatusUi()
                        }
                        dialogRef?.dismiss()
                        Toast.makeText(this@MainActivity, "已刪除「${p.name}」", Toast.LENGTH_SHORT).show()
                    }
                }
                row.addView(delBtn)
            }

            listLayout.addView(row)
        }
        scrollView.addView(listLayout)
        dialogContainer.addView(scrollView)

        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#282C37"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)).apply { setMargins(0, dpToPx(10), 0, dpToPx(10)) }
        }
        dialogContainer.addView(divider)

        val saveAsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameInput = EditText(this).apply {
            hint = "輸入新名稱..."
            setHintTextColor(Color.parseColor("#5A6275"))
            setTextColor(Color.WHITE)
            textSize = 13f
            background = createCardBackground(Color.parseColor("#222631"), 6f)
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(36), 1f).apply { marginEnd = dpToPx(8) }
        }
        val createBtn = Button(this).apply {
            text = "＋ 另存新檔"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardBackground(Color.parseColor("#0070F3"), 6f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(36))
            setOnClickListener {
                val inputName = nameInput.text.toString().trim()
                if (inputName.isNotEmpty()) {
                    val newProfile = VolumeProfile(
                        name = inputName,
                        points = currentPoints.toList(),
                        isDefaultStar = false
                    )
                    allProfiles.add(newProfile)
                    profileRepo.saveAllProfiles(allProfiles)
                    profileRepo.setActiveProfileId(newProfile.id)

                    activeProfile = newProfile
                    this@MainActivity.hasUnsavedChanges = false
                    updateProfileStatusUi()
                    dialogRef?.dismiss()
                    Toast.makeText(this@MainActivity, "已建立「$inputName」", Toast.LENGTH_SHORT).show()
                }
            }
        }
        saveAsRow.addView(nameInput)
        saveAsRow.addView(createBtn)
        dialogContainer.addView(saveAsRow)

        val backupActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(8), 0, 0)
        }
        val exportBtn = Button(this).apply {
            text = "📤 匯出備份"
            textSize = 11f
            setTextColor(Color.parseColor("#00E5FF"))
            background = createCardBackground(Color.parseColor("#222631"), 6f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(34), 1f).apply { marginEnd = dpToPx(4) }
            setOnClickListener {
                dialogRef?.dismiss()
                exportLauncher.launch("velotune_profiles.json")
            }
        }
        val importBtn = Button(this).apply {
            text = "📥 匯入設定檔"
            textSize = 11f
            setTextColor(Color.parseColor("#30D158"))
            background = createCardBackground(Color.parseColor("#222631"), 6f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(34), 1f).apply { marginStart = dpToPx(4) }
            setOnClickListener {
                dialogRef?.dismiss()
                importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
            }
        }
        backupActionRow.addView(exportBtn)
        backupActionRow.addView(importBtn)
        dialogContainer.addView(backupActionRow)

        dialogRef = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(dialogContainer)
            .setNegativeButton("關閉", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun showBindBluetoothDialog(profile: VolumeProfile) {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        val pairedDevices = adapter?.bondedDevices?.toList() ?: emptyList()

        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "手機目前沒有任何已配對的藍牙裝置", Toast.LENGTH_SHORT).show()
            return
        }

        var dialogRef: AlertDialog? = null
        val dialogContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#181B22"))
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        val titleText = TextView(this).apply {
            text = "綁定藍牙 - ${profile.name}"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }
        val hintText = TextView(this).apply {
            text = "👇 請點擊下方要綁定的裝置，連線時將自動套用此設定檔："
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 12f
            setPadding(0, dpToPx(4), 0, dpToPx(12))
        }
        dialogContainer.addView(titleText)
        dialogContainer.addView(hintText)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(240))
        }
        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        for (device in pairedDevices) {
            val devName = device.name ?: "未命名裝置"
            val devAddr = device.address

            val itemCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = createBorderedCardBackground(Color.parseColor("#222631"), Color.parseColor("#374151"), 8f, 1)
                setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dpToPx(4), 0, dpToPx(4)) }

                setOnClickListener {
                    val all = profileRepo.getAllProfiles()
                    val idx = all.indexOfFirst { it.id == profile.id }
                    if (idx != -1) {
                        all[idx] = all[idx].copy(boundBtAddress = devAddr, boundBtName = devName)
                        profileRepo.saveAllProfiles(all)
                        if (activeProfile.id == profile.id) {
                            activeProfile = all[idx]
                        }
                        Toast.makeText(this@MainActivity, "已成功綁定至「$devName」！", Toast.LENGTH_SHORT).show()
                    }
                    dialogRef?.dismiss()
                }
            }

            val iconText = TextView(this).apply {
                text = "🎧"
                textSize = 18f
                setPadding(0, 0, dpToPx(10), 0)
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameView = TextView(this).apply {
                text = devName
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            val addrView = TextView(this).apply {
                text = "MAC: $devAddr"
                setTextColor(Color.parseColor("#8C93A4"))
                textSize = 11f
            }
            infoCol.addView(nameView)
            infoCol.addView(addrView)

            val selectActionHint = TextView(this).apply {
                text = "選擇 ▶"
                setTextColor(Color.parseColor("#00E5FF"))
                textSize = 12f
            }

            itemCard.addView(iconText)
            itemCard.addView(infoCol)
            itemCard.addView(selectActionHint)
            listLayout.addView(itemCard)
        }

        scrollView.addView(listLayout)
        dialogContainer.addView(scrollView)

        dialogRef = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(dialogContainer)
            .setNegativeButton("取消", null)
            .show()
    }

    private fun unbindBluetooth(profile: VolumeProfile) {
        val all = profileRepo.getAllProfiles()
        val idx = all.indexOfFirst { it.id == profile.id }
        if (idx != -1) {
            all[idx] = all[idx].copy(boundBtAddress = null, boundBtName = null)
            profileRepo.saveAllProfiles(all)
            if (activeProfile.id == profile.id) {
                activeProfile = all[idx]
            }
            Toast.makeText(this, "已解除藍牙綁定", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCurrentProfile() {
        val all = profileRepo.getAllProfiles()
        val index = all.indexOfFirst { it.id == activeProfile.id }

        val updatedProfile = activeProfile.copy(points = currentPoints.toList())
        if (index != -1) {
            all[index] = updatedProfile
        } else {
            all.add(updatedProfile)
        }
        profileRepo.saveAllProfiles(all)
        activeProfile = updatedProfile

        this.hasUnsavedChanges = false
        updateProfileStatusUi()
        Toast.makeText(this, "已儲存至「${activeProfile.name}」", Toast.LENGTH_SHORT).show()
    }

    private fun revertChanges() {
        currentPoints.clear()
        currentPoints.addAll(activeProfile.points.sortedBy { it.speedKmh })
        this.hasUnsavedChanges = false
        highlightedPointIndex = -1

        curveEditorView.setHighlightedIndex(-1)
        curveEditorView.setControlPoints(currentPoints)
        AutoVolumeService.updateCurveFromEditor(currentPoints)

        refreshPointsListUi()
        updateProfileStatusUi()
        Toast.makeText(this, "已還原為上次儲存狀態", Toast.LENGTH_SHORT).show()
    }

    private fun saveExportFileToUri(uri: android.net.Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val json = profileRepo.exportToJsonString()
                outputStream.write(json.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "設定檔已成功匯出！", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "匯出失敗: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readImportFileFromUri(uri: android.net.Uri) {
        try {
            val jsonStr = contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8).readText()
            } ?: return

            val result = profileRepo.importFromJsonString(jsonStr)
            result.onSuccess { count ->
                Toast.makeText(this, "成功匯入 $count 個設定檔！", Toast.LENGTH_SHORT).show()
                loadInitialProfile()
                curveEditorView.setControlPoints(currentPoints)
                refreshPointsListUi()
                updateProfileStatusUi()
            }.onFailure { err ->
                Toast.makeText(this, "匯入失敗 (${err.message})", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "讀取失敗: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private fun createCardBackground(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, radiusDp, resources.displayMetrics
            )
        }
    }

    private fun createBorderedCardBackground(bgColor: Int, strokeColor: Int, radiusDp: Float, strokeWidthDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, radiusDp, resources.displayMetrics
            )
            setStroke(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, strokeWidthDp.toFloat(), resources.displayMetrics).toInt(),
                strokeColor
            )
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}