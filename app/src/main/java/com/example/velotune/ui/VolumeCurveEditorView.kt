package com.example.velotune.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.velotune.core.ControlPoint
import kotlin.math.ceil
import kotlin.math.hypot

class VolumeCurveEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val points = mutableListOf<ControlPoint>()

    // 選中或高亮的控制點索引 (-1 表示無)
    var highlightedIndex: Int = -1
        private set

    // 外部回呼
    var onPointsChangedListener: ((List<ControlPoint>) -> Unit)? = null
    var onPointSelectedListener: ((Int) -> Unit)? = null

    // 即時車速指示標記
    private var liveSpeedKmh: Float? = null
    private var liveVolumeRatio: Float? = null

    private var selectedPointIndex: Int = -1
    private val touchRadiusPx = dpToPx(32f)

    // 配色與畫筆
    private val bgPaint = Paint().apply { color = Color.parseColor("#181A20") }
    private val cornerRadius = dpToPx(16f)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#282C37")
        strokeWidth = dpToPx(1f)
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#787F95")
        textSize = dpToPx(10.5f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = dpToPx(3.5f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pointCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pointHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
    }

    // 選中狀態的高亮光環
    private val selectedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF3B30") // 鮮明橘紅或亮黃
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.5f)
    }

    // 即時光標樣式 (螢光綠)
    private val liveCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF66")
    }
    private val liveLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6600FF66")
        strokeWidth = dpToPx(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val paddingLeftPx = dpToPx(38f)
    private val paddingBottomPx = dpToPx(28f)
    private val paddingTopPx = dpToPx(20f)
    private val paddingRightPx = dpToPx(24f)

    /**
     * 動態計算 X 軸上限：至少 120 km/h，若有更大點則以 20 為級距向上取整
     */
    val dynamicMaxSpeed: Float
        get() {
            val maxPtSpeed = points.maxOfOrNull { it.speedKmh } ?: 120f
            val base = maxOf(120f, maxPtSpeed)
            return (ceil(base / 20f) * 20f)
        }

    fun setControlPoints(newPoints: List<ControlPoint>) {
        points.clear()
        points.addAll(newPoints.sortedBy { it.speedKmh })
        invalidate()
    }

    fun setHighlightedIndex(index: Int) {
        highlightedIndex = index
        invalidate()
    }

    fun setLiveIndicator(speedKmh: Float, volumeRatio: Float) {
        this.liveSpeedKmh = speedKmh
        this.liveVolumeRatio = volumeRatio
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val rectF = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, bgPaint)

        val plotW = width - paddingLeftPx - paddingRightPx
        val plotH = height - paddingTopPx - paddingBottomPx
        if (plotW <= 0 || plotH <= 0) return

        val maxSpeed = dynamicMaxSpeed

        // 1. 繪製縱軸 (音量 0% ~ 100%)
        for (i in 0..4) {
            val ratio = i / 4f
            val y = paddingTopPx + plotH * (1f - ratio)
            canvas.drawLine(paddingLeftPx, y, width - paddingRightPx, y, gridPaint)
            canvas.drawText("${(ratio * 100).toInt()}%", dpToPx(5f), y + dpToPx(4f), axisTextPaint)
        }

        // 2. 繪製橫軸刻度 (清晰數字，動態 4 等分)
        val divisions = 4
        for (i in 0..divisions) {
            val speed = (maxSpeed / divisions) * i
            val x = paddingLeftPx + (speed / maxSpeed) * plotW
            canvas.drawLine(x, paddingTopPx, x, height - paddingBottomPx, gridPaint)

            val label = speed.toInt().toString()
            canvas.drawText(label, x - dpToPx(8f), height - dpToPx(8f), axisTextPaint)
        }
        // 在右下角標註單位
        canvas.drawText("km/h", width - paddingRightPx - dpToPx(14f), height - dpToPx(8f), axisTextPaint)

        if (points.isEmpty()) return

        // 3. 繪製面積漸層與折線
        val curvePath = Path()
        val fillPath = Path()

        val startX = paddingLeftPx + (points.first().speedKmh / maxSpeed).coerceIn(0f, 1f) * plotW
        val startY = paddingTopPx + (1f - points.first().volumeRatio.coerceIn(0f, 1f)) * plotH

        curvePath.moveTo(startX, startY)
        fillPath.moveTo(startX, height - paddingBottomPx)
        fillPath.lineTo(startX, startY)

        for (i in 1 until points.size) {
            val pt = points[i]
            val x = paddingLeftPx + (pt.speedKmh / maxSpeed).coerceIn(0f, 1f) * plotW
            val y = paddingTopPx + (1f - pt.volumeRatio.coerceIn(0f, 1f)) * plotH
            curvePath.lineTo(x, y)
            fillPath.lineTo(x, y)
        }

        val lastX = paddingLeftPx + (points.last().speedKmh / maxSpeed).coerceIn(0f, 1f) * plotW
        fillPath.lineTo(lastX, height - paddingBottomPx)
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, paddingTopPx, 0f, height - paddingBottomPx,
            Color.parseColor("#4400E5FF"), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(curvePath, curvePaint)

        // 4. 繪製錨點與選中狀態
        for (i in points.indices) {
            val pt = points[i]
            val x = paddingLeftPx + (pt.speedKmh / maxSpeed).coerceIn(0f, 1f) * plotW
            val y = paddingTopPx + (1f - pt.volumeRatio.coerceIn(0f, 1f)) * plotH

            if (i == highlightedIndex) {
                // 選中/拖動中的錨點：外加聚焦外圈
                canvas.drawCircle(x, y, dpToPx(13f), selectedRingPaint)
                canvas.drawCircle(x, y, dpToPx(8f), pointHaloPaint)
                canvas.drawCircle(x, y, dpToPx(4f), pointCorePaint)
            } else {
                canvas.drawCircle(x, y, dpToPx(7f), pointHaloPaint)
                canvas.drawCircle(x, y, dpToPx(3.5f), pointCorePaint)
            }
        }

        // 5. 繪製即時車速指示光點
        liveSpeedKmh?.let { speed ->
            val ratio = liveVolumeRatio ?: 0f
            val x = paddingLeftPx + (speed / maxSpeed).coerceIn(0f, 1f) * plotW
            val y = paddingTopPx + (1f - ratio.coerceIn(0f, 1f)) * plotH

            canvas.drawLine(x, paddingTopPx, x, height - paddingBottomPx, liveLinePaint)
            liveCursorPaint.alpha = 70
            canvas.drawCircle(x, y, dpToPx(10f), liveCursorPaint)
            liveCursorPaint.alpha = 255
            canvas.drawCircle(x, y, dpToPx(5f), liveCursorPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val plotW = width - paddingLeftPx - paddingRightPx
        val plotH = height - paddingTopPx - paddingBottomPx
        if (plotW <= 0 || plotH <= 0) return false

        val maxSpeed = dynamicMaxSpeed

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                selectedPointIndex = -1
                var minDistance = Float.MAX_VALUE

                for (i in points.indices) {
                    val pt = points[i]
                    val px = paddingLeftPx + (pt.speedKmh / maxSpeed) * plotW
                    val py = paddingTopPx + (1f - pt.volumeRatio) * plotH
                    val dist = hypot(event.x - px, event.y - py)

                    if (dist < touchRadiusPx && dist < minDistance) {
                        minDistance = dist
                        selectedPointIndex = i
                    }
                }

                if (selectedPointIndex != -1) {
                    highlightedIndex = selectedPointIndex
                    onPointSelectedListener?.invoke(selectedPointIndex)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (selectedPointIndex != -1) {
                    val rawY = event.y - paddingTopPx
                    val newVolumeRatio = (1f - (rawY / plotH)).coerceIn(0f, 1f)

                    val rawX = event.x - paddingLeftPx
                    var newSpeed = ((rawX / plotW) * maxSpeed).coerceIn(0f, maxSpeed)

                    // 第 0 點車速鎖定為 0 km/h，其他點維持基本順序邊界
                    if (selectedPointIndex == 0) {
                        newSpeed = 0f
                    } else {
                        val prevSpeed = points[selectedPointIndex - 1].speedKmh + 1f
                        val nextSpeed = if (selectedPointIndex < points.size - 1) {
                            points[selectedPointIndex + 1].speedKmh - 1f
                        } else {
                            maxSpeed
                        }
                        newSpeed = newSpeed.coerceIn(prevSpeed, nextSpeed)
                    }

                    points[selectedPointIndex] = ControlPoint(newSpeed, newVolumeRatio)
                    invalidate()
                    onPointsChangedListener?.invoke(points.toList())
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (selectedPointIndex != -1) {
                    selectedPointIndex = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
}