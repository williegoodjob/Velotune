package com.example.velotune

import android.Manifest
import android.app.AlertDialog
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
import com.example.velotune.data.ProfileRepository
import com.example.velotune.data.VolumeProfile
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

    // 當前作用中的設定檔物件
    private lateinit var activeProfile: VolumeProfile
    private val currentPoints = mutableListOf<ControlPoint>()

    private var isDirty = false
    private var highlightedPointIndex = -1

    // UI 元件
    private lateinit var curveEditorView: VolumeCurveEditorView
    private lateinit var pointsContainerLayout: LinearLayout
    private lateinit var profileStatusText: TextView
    private lateinit var serviceStateBadge: TextView
    private lateinit var liveSpeedValText: TextView
    private lateinit var liveVolumeValText: TextView

    private lateinit var startBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var stopBtn: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()

        profileRepo = ProfileRepository(this)
        loadInitialProfile()

        buildModernUi()
        observeServiceData()
    }

    private fun loadInitialProfile() {
        val all = profileRepo.getAllProfiles()
        val activeId = profileRepo.getActiveProfileId()
        activeProfile = all.find { it.id == activeId } ?: all.first()

        currentPoints.clear()
        currentPoints.addAll(activeProfile.points.sortedBy { it.speedKmh })
        isDirty = false

        // 同步通知背景 Service
        AutoVolumeService.updateCurveFromEditor(currentPoints)
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

        val dashboardCard = createDashboardCard()
        val actionControls = createFullControlPanel()

        curveEditorView = VolumeCurveEditorView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(240)
            ).apply { setMargins(0, dpToPx(16), 0, dpToPx(16)) }
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

        // 管理設定檔按鈕
        val profileMenuBtn = Button(this).apply {
            text = "設定檔 ▼"
            textSize = 12f
            setTextColor(Color.parseColor("#00E5FF"))
            background = createCardBackground(Color.parseColor("#262B36"), 6f)
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(32)
            ).apply { marginEnd = dpToPx(8) }
            setOnClickListener { showProfileOptionsMenu() }
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
        statusHeader.addView(profileMenuBtn)
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
     * 點擊「設定檔 ▼」彈出操作清單
     */
    private fun showProfileOptionsMenu() {
        val options = mutableListOf<String>()
        options.add("💾 儲存目前變更" + if (isDirty) " (有變動)" else "")
        options.add("📝 另存為新設定檔...")
        options.add("🔄 還原變更 (捨棄未存修改)")
        options.add("📑 切換 / 管理設定檔...")

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("設定檔管理 - ${activeProfile.name}")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> saveCurrentProfile()
                    1 -> showSaveAsNewDialog()
                    2 -> revertChanges()
                    3 -> showSwitchProfileDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 1. 儲存目前變更
     */
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

        isDirty = false
        updateProfileStatusUi()
        Toast.makeText(this, "已儲存至「${activeProfile.name}」", Toast.LENGTH_SHORT).show()
    }

    /**
     * 2. 另存為新設定檔
     */
    private fun showSaveAsNewDialog() {
        val input = EditText(this).apply {
            hint = "輸入新設定檔名稱"
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("另存新設定檔")
            .setView(input)
            .setPositiveButton("建立") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val newProfile = VolumeProfile(
                        name = name,
                        points = currentPoints.toList(),
                        isDefault = false
                    )
                    val all = profileRepo.getAllProfiles()
                    all.add(newProfile)
                    profileRepo.saveAllProfiles(all)
                    profileRepo.setActiveProfileId(newProfile.id)

                    activeProfile = newProfile
                    isDirty = false
                    updateProfileStatusUi()
                    Toast.makeText(this, "已建立並切換至「$name」", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 3. 還原變更
     */
    private fun revertChanges() {
        currentPoints.clear()
        currentPoints.addAll(activeProfile.points.sortedBy { it.speedKmh })
        isDirty = false
        highlightedPointIndex = -1

        curveEditorView.setHighlightedIndex(-1)
        curveEditorView.setControlPoints(currentPoints)
        AutoVolumeService.updateCurveFromEditor(currentPoints)

        refreshPointsListUi()
        updateProfileStatusUi()
        Toast.makeText(this, "已還原為上次儲存的數值", Toast.LENGTH_SHORT).show()
    }

    /**
     * 4. 切換或刪除設定檔
     */
    private fun showSwitchProfileDialog() {
        val all = profileRepo.getAllProfiles()
        val itemNames = all.map {
            val prefix = if (it.id == activeProfile.id) "✔ " else "    "
            "$prefix${it.name}" + if (it.isDefault) " [預設]" else ""
        }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("選擇設定檔 (長按可刪除)")
            .setItems(itemNames) { _, which ->
                val selected = all[which]
                if (selected.id != activeProfile.id) {
                    profileRepo.setActiveProfileId(selected.id)
                    activeProfile = selected
                    currentPoints.clear()
                    currentPoints.addAll(selected.points.sortedBy { it.speedKmh })
                    isDirty = false
                    highlightedPointIndex = -1

                    curveEditorView.setHighlightedIndex(-1)
                    curveEditorView.setControlPoints(currentPoints)
                    AutoVolumeService.updateCurveFromEditor(currentPoints)

                    refreshPointsListUi()
                    updateProfileStatusUi()
                    Toast.makeText(this, "已切換至「${selected.name}」", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("刪除自訂設定檔") { _, _ ->
                showDeleteProfileDialog()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteProfileDialog() {
        val all = profileRepo.getAllProfiles()
        val customProfiles = all.filter { !it.isDefault }

        if (customProfiles.isEmpty()) {
            Toast.makeText(this, "沒有可刪除的自訂設定檔", Toast.LENGTH_SHORT).show()
            return
        }

        val names = customProfiles.map { it.name }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("選擇要刪除的設定檔")
            .setItems(names) { _, which ->
                val target = customProfiles[which]
                all.removeAll { it.id == target.id }
                profileRepo.saveAllProfiles(all)

                // 若剛好刪除當前使用的，切回預設
                if (target.id == activeProfile.id) {
                    loadInitialProfile()
                    curveEditorView.setControlPoints(currentPoints)
                    refreshPointsListUi()
                    updateProfileStatusUi()
                }
                Toast.makeText(this, "已刪除「${target.name}」", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

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

            val speedEdit = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(pt.speedKmh.toInt().toString())
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                isEnabled = (i != 0)
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
        if (!isDirty) {
            isDirty = true
            updateProfileStatusUi()
        }
    }

    private fun updateProfileStatusUi() {
        if (isDirty) {
            profileStatusText.text = "● ${activeProfile.name} (未儲存)*"
            profileStatusText.setTextColor(Color.parseColor("#FF9F0A"))
        } else {
            profileStatusText.text = "● ${activeProfile.name}"
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