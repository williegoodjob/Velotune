package com.example.velotune

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.velotune.core.ControlPoint
import com.example.velotune.core.ServiceState
import com.example.velotune.service.AutoVolumeService
import com.example.velotune.ui.VolumeCurveEditorView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var curveEditorView: VolumeCurveEditorView
    private lateinit var pointsContainerLayout: LinearLayout
    private lateinit var profileStatusText: TextView
    private lateinit var serviceStateBadge: TextView
    private lateinit var liveSpeedValText: TextView
    private lateinit var liveVolumeValText: TextView

    // 主畫面控制按鈕
    private lateinit var startBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var stopBtn: Button

    private var isDirty = false
    private var currentProfileName = "預設設定檔"
    private var highlightedPointIndex = -1

    private val currentPoints = mutableListOf<ControlPoint>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()

        currentPoints.addAll(
            listOf(
                ControlPoint(0f, 0.20f),
                ControlPoint(30f, 0.45f),
                ControlPoint(60f, 0.70f),
                ControlPoint(90f, 1.00f)
            )
        )

        buildModernUi()
        observeServiceData()
    }

    private fun buildModernUi() {
        val rootScrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#0E1116"))
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(32))
        }

        // 1. 儀表板
        val dashboardCard = createDashboardCard()

        // 2. 4 鍵控制操作面板 (啟動 / 暫停 / 靜音 / 停止)
        val actionControls = createFullControlPanel()

        // 3. 2D 曲線編輯器 (支援動態最大速限與高亮回呼)
        curveEditorView = VolumeCurveEditorView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(240)
            ).apply { setMargins(0, dpToPx(16), 0, dpToPx(16)) }
            setControlPoints(currentPoints)

            // 畫布拖動時同步更新暫存
            onPointsChangedListener = { updatedPoints ->
                currentPoints.clear()
                currentPoints.addAll(updatedPoints)
                markProfileAsDirty()
                AutoVolumeService.updateCurveFromEditor(currentPoints)
                refreshPointsListUi()
            }

            // 畫布點選時 -> 高亮對應列表項目
            onPointSelectedListener = { selectedIdx ->
                highlightedPointIndex = selectedIdx
                refreshPointsListUi()
            }
        }

        // 4. 控制點標題列
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

        pointsContainerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        mainLayout.addView(dashboardCard)
        mainLayout.addView(actionControls)
        mainLayout.addView(curveEditorView)
        mainLayout.addView(listHeader)
        mainLayout.addView(pointsContainerLayout)

        rootScrollView.addView(mainLayout)
        setContentView(rootScrollView)

        refreshPointsListUi()
        updateProfileStatusUi()
    }

    private fun createDashboardCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardBackground(Color.parseColor("#181B22"), 16f)
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        val statusHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        profileStatusText = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        serviceStateBadge = TextView(this).apply {
            text = "未啟動"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            background = createCardBackground(Color.parseColor("#3C4453"), 6f)
        }

        statusHeader.addView(profileStatusText)
        statusHeader.addView(serviceStateBadge)

        val metricRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(16), 0, 0)
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

        card.addView(statusHeader)
        card.addView(metricRow)
        return card
    }

    /**
     * 建立包含 啟動/暫停/靜音/停止 的控制面板
     */
    private fun createFullControlPanel(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(12), 0, 0)
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
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(42), 1f).apply { marginEnd = dpToPx(4) }
            setOnClickListener { sendServiceAction(AutoVolumeService.ACTION_START) }
        }

        pauseBtn = Button(this).apply {
            text = "暫停"
            setTextColor(Color.WHITE)
            background = createCardBackground(Color.parseColor("#262B36"), 8f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(42), 1f).apply {
                marginStart = dpToPx(4)
                marginEnd = dpToPx(4)
            }
            setOnClickListener { sendServiceAction(AutoVolumeService.ACTION_TOGGLE_PAUSE) }
        }

        muteBtn = Button(this).apply {
            text = "靜音"
            setTextColor(Color.WHITE)
            background = createCardBackground(Color.parseColor("#262B36"), 8f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(42), 1f).apply {
                marginStart = dpToPx(4)
                marginEnd = dpToPx(4)
            }
            setOnClickListener { sendServiceAction(AutoVolumeService.ACTION_TOGGLE_MUTE) }
        }

        stopBtn = Button(this).apply {
            text = "停止"
            setTextColor(Color.parseColor("#FF453A"))
            background = createCardBackground(Color.parseColor("#262B36"), 8f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(42), 1f).apply { marginStart = dpToPx(4) }
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
                // 若被選中，換上青藍色外邊框；否則維持深暗底色
                background = if (isSelected) {
                    createBorderedCardBackground(Color.parseColor("#1D2533"), Color.parseColor("#00E5FF"), 10f, 2)
                } else {
                    createCardBackground(Color.parseColor("#181B22"), 10f)
                }
                setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dpToPx(6), 0, dpToPx(6)) }

                // 點選整列也能選中並聯動上方圖表
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
                textSize = 14f
                setPadding(0, 0, dpToPx(12), 0)
            }

            // 車速編輯：失去焦點時自動觸發【依速度排序】
            val speedEdit = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(pt.speedKmh.toInt().toString())
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                isEnabled = (i != 0) // 第 0 點始終為 0 km/h
                background = createCardBackground(Color.parseColor("#242A36"), 6f)
                layoutParams = LinearLayout.LayoutParams(dpToPx(58), dpToPx(36))

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
                textSize = 13f
                setPadding(dpToPx(6), 0, dpToPx(14), 0)
            }

            // 音量編輯：失去焦點時寫入
            val volEdit = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText((pt.volumeRatio * 100).toInt().toString())
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                background = createCardBackground(Color.parseColor("#242A36"), 6f)
                layoutParams = LinearLayout.LayoutParams(dpToPx(56), dpToPx(36))

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
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dpToPx(6), 0, 0, 0)
            }

            val deleteBtn = Button(this).apply {
                text = "✕"
                textSize = 12f
                setTextColor(if (currentPoints.size > 2 && i != 0) Color.WHITE else Color.GRAY)
                background = createCardBackground(
                    if (currentPoints.size > 2 && i != 0) Color.parseColor("#FF453A") else Color.parseColor("#332222"),
                    6f
                )
                layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
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

    /**
     * 【核心排序機制】寫入數值後依速度重排，並重新錨定高亮焦點
     */
    private fun commitAndSortPoint(originIndex: Int, newSpeed: Float, newRatio: Float) {
        val targetPoint = ControlPoint(newSpeed, newRatio)
        currentPoints[originIndex] = targetPoint

        // 依速度重新排序 (第 0 點恆為 0)
        currentPoints.sortBy { it.speedKmh }
        if (currentPoints.first().speedKmh != 0f) {
            currentPoints[0] = currentPoints[0].copy(speedKmh = 0f)
        }

        // 重新定位該點在新陣列中的位置，保持焦點不丟失
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
        if (!isDirty) {
            isDirty = true
            updateProfileStatusUi()
        }
    }

    private fun updateProfileStatusUi() {
        if (isDirty) {
            profileStatusText.text = "● $currentProfileName (未儲存)*"
            profileStatusText.setTextColor(Color.parseColor("#FF9F0A"))
        } else {
            profileStatusText.text = "● $currentProfileName"
            profileStatusText.setTextColor(Color.parseColor("#30D158"))
        }
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

        // 即時監聽服務狀態，全面聯動按鈕樣式
        activityScope.launch {
            AutoVolumeService.serviceStateFlow.collect { state ->
                updateControlsByState(state)
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
        permissionLauncher.launch(permissions.toTypedArray())
    }
}