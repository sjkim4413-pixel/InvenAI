package com.example.myapp.overlay

import com.example.myapp.R

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.min

// F-04 대신 사용하는 시연용 대조 데이터입니다.
// box는 원본 사진 기준 0~1 비율 좌표: 왼쪽, 위, 오른쪽, 아래입니다.
data class OverlayItem(
    val location: String,
    val box: RectF,
    val expectedId: String,
    var recognizedId: String,
    var status: Int,
    var manuallyEdited: Boolean = false
)

class OverlayActivity : AppCompatActivity() {
    private lateinit var overlay: WarehouseOverlayView
    private lateinit var summary: LinearLayout
    private lateinit var accountButton: android.widget.Button
    private lateinit var reinspection: F07ReinspectionController
    private val inspection by lazy { InspectionCase.from(intent) }
    private val preferences by lazy { inspection.preferences(this) }
    // 사진 1의 팔레트 적재 묶음 45곳을 수동으로 지정한 데모 좌표입니다.
    // 개별 종이상자 탐지 결과가 아닙니다. 실제 연결 시 F-04 배열로 교체합니다.
    private val items by lazy { if (inspection.isDemo) createDemoItems() else mutableListOf<OverlayItem>() }

    private fun createDemoItems(): MutableList<OverlayItem> {
        val rows = listOf(
            listOf(RectF(.02f,.22f,.125f,.265f), RectF(.145f,.167f,.238f,.265f),
                RectF(.26f,.166f,.353f,.265f), RectF(.36f,.165f,.448f,.265f),
                RectF(.474f,.17f,.558f,.267f), RectF(.578f,.181f,.663f,.267f),
                RectF(.676f,.18f,.78f,.268f), RectF(.797f,.176f,.883f,.268f), RectF(.906f,.18f,.995f,.27f)),
            listOf(RectF(.004f,.296f,.103f,.397f), RectF(.11f,.293f,.214f,.399f),
                RectF(.244f,.311f,.34f,.402f), RectF(.351f,.315f,.44f,.403f),
                RectF(.468f,.30f,.563f,.405f), RectF(.577f,.306f,.664f,.406f),
                RectF(.694f,.3f,.793f,.407f), RectF(.805f,.305f,.903f,.409f), RectF(.918f,.312f,.998f,.412f)),
            listOf(RectF(.003f,.467f,.083f,.55f), RectF(.09f,.43f,.202f,.551f),
                RectF(.222f,.439f,.325f,.552f), RectF(.333f,.442f,.435f,.553f),
                RectF(.46f,.445f,.556f,.554f), RectF(.571f,.467f,.655f,.554f),
                RectF(.701f,.452f,.794f,.556f), RectF(.802f,.442f,.904f,.556f), RectF(.928f,.445f,.998f,.556f)),
            listOf(RectF(.003f,.631f,.077f,.707f), RectF(.081f,.593f,.185f,.71f),
                RectF(.212f,.584f,.318f,.713f), RectF(.326f,.591f,.437f,.715f),
                RectF(.461f,.592f,.556f,.714f), RectF(.578f,.595f,.681f,.715f),
                RectF(.707f,.594f,.808f,.717f), RectF(.82f,.595f,.928f,.719f), RectF(.951f,.602f,.999f,.72f)),
            listOf(RectF(.003f,.749f,.076f,.89f), RectF(.085f,.755f,.174f,.89f),
                RectF(.192f,.747f,.307f,.892f), RectF(.322f,.743f,.436f,.893f),
                RectF(.463f,.749f,.561f,.895f), RectF(.58f,.779f,.693f,.897f),
                RectF(.713f,.782f,.836f,.901f), RectF(.839f,.798f,.969f,.902f), RectF(.976f,.752f,.999f,.903f))
        )
        return rows.flatMapIndexed { row, boxes ->
            boxes.mapIndexed { col, box ->
                val index = row * 9 + col
                val status = when (index) {
                    3, 12, 25, 38 -> 2
                    16, 31 -> 3
                    7, 29 -> 4
                    19, 41 -> 5
                    else -> 1
                }
                val expected = if (status == 4) "" else "ITEM-${101 + index}"
                val recognized = when (status) {
                    2 -> "ITEM-${901 + index}"
                    3 -> ""
                    4 -> "ITEM-${101 + index}"
                    5 -> "ITEM-?${index}"
                    else -> expected
                }
                OverlayItem("R${row + 1}-${col + 1}", box, expected, recognized, status)
            }
        }.toMutableList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.overlay_main)
        val root = findViewById<FrameLayout>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // 이 사진의 데모 데이터에 대한 수정값을 기기에서 복원합니다.
        items.forEach { item ->
            val key = item.location
            item.recognizedId = preferences.getString("id_$key", item.recognizedId) ?: item.recognizedId
            item.status = preferences.getInt("status_$key", item.status)
            item.manuallyEdited = preferences.getBoolean("edited_$key", false)
        }

        // 사진과 테두리를 하나의 Canvas에 그려 크기와 위치가 어긋나지 않게 합니다.
        root.removeAllViews()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 247, 251))
        }
        val toolbar = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8),0,dp(16),0)
        }
        toolbar.addView(TextView(this).apply {
            text="←"; textSize=24f; includeFontPadding=false
            setTextColor(Color.rgb(15,23,42))
            gravity=android.view.Gravity.CENTER; contentDescription="뒤로가기"
            setOnClickListener { finish() }
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        toolbar.addView(TextView(this).apply {
            text="${inspection.title(this@OverlayActivity)} · 현장 검수"; textSize=18f; includeFontPadding=false
            setTextColor(Color.rgb(15,23,42))
            typeface=android.graphics.Typeface.DEFAULT_BOLD
        },LinearLayout.LayoutParams(0,-2,1f))
        column.addView(toolbar,LinearLayout.LayoutParams(-1,dp(56)))
        column.addView(View(this).apply { setBackgroundColor(Color.rgb(226,232,240)) },
            LinearLayout.LayoutParams(-1,dp(1)))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        if (!inspection.isDemo) {
            preferences // Register this photo's inspection without creating fabricated detections.
            val photo = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                contentDescription = inspection.title(this@OverlayActivity) + " 검수 사진"
            }
            column.addView(photo, LinearLayout.LayoutParams(-1, 0, 1f))
            com.bumptech.glide.Glide.with(this).load(inspection.photo(this))
                .error(android.R.drawable.ic_dialog_alert).into(photo)
            column.addView(TextView(this).apply {
                text = if (inspection.photo(this@OverlayActivity).isFile)
                    "분석 대기\nYOLO 분석 및 재고 비교 결과를 받으면 조치·재검수를 시작할 수 있어요."
                else "사진을 찾을 수 없습니다. 사진 목록에서 다시 선택해주세요."
                textSize=16f; setPadding(dp(16),dp(16),dp(16),dp(24))
                setTextColor(Color.rgb(22,39,60))
            })
            root.addView(column, FrameLayout.LayoutParams(-1,-1))
            return
        }
        header.addView(TextView(this).apply {
            text="기존 사진 · 데모 검수"; textSize=14f
        })
        val normalToggle = android.widget.Switch(this).apply {
            text = "정상 영역도 표시"
            textSize = 16f
            setTextColor(Color.rgb(22, 39, 60))
            setOnCheckedChangeListener { _, checked ->
                this@OverlayActivity.overlay.showNormal = checked
                this@OverlayActivity.overlay.invalidate()
            }
        }
        header.addView(normalToggle)
        accountButton = android.widget.Button(this).apply {
            textSize = 14f
            setOnClickListener { reinspection.chooseActor() }
        }
        header.addView(accountButton)
        summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(10), dp(12))
        }
        overlay = WarehouseOverlayView(this, items) { showDetail(it) }
        overlay.setBackgroundColor(Color.rgb(16, 32, 48))
        column.addView(header)
        column.addView(overlay, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(summary)
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        reinspection = F07ReinspectionController(this, items, inspection) {
            refreshWorkflow()
        }
        refreshWorkflow()
        F07DeviceNotice.requestPermission(this)
        openNotification(intent)
    }

    private fun refreshWorkflow() {
        overlay.recheckLocations = reinspection.pendingLocations()
        overlay.workingLocations = reinspection.workingLocations()
        overlay.needsActionLocations = reinspection.needsActionLocations()
        accountButton.text = "시연 계정: ${reinspection.actor.name}  ▾"
        overlay.invalidate()
        updateSummary()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val next = InspectionCase.from(intent)
        if (next != inspection) {
            setIntent(intent); recreate(); return
        }
        setIntent(intent)
        if (inspection.isDemo) openNotification(intent)
    }

    private fun openNotification(intent: android.content.Intent?) {
        val location = intent?.getStringExtra("action_location") ?: return
        intent.removeExtra("action_location")
        items.firstOrNull { it.location == location }?.let { item ->
            overlay.post { reinspection.showDetail(item) }
        }
    }

    private fun showStatusItems(code: Int) {
        val description = when (code) {
            1 -> "전산 예상 ID와 인식 ID가 일치하는 항목입니다."
            2 -> "전산 예상 ID와 인식 ID가 다른 항목입니다."
            3 -> "전산에는 예정되어 있지만 해당 위치에서 화물이 확인되지 않은 항목입니다."
            4 -> "전산 예상 정보 없이 현장에서 확인된 항목입니다."
            else -> "판독이 불확실해 검수자가 직접 확인해야 하는 항목입니다."
        }
        val matches = items.filter { it.status == code }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        content.addView(TextView(this).apply {
            text = description
            textSize = 17f
            setPadding(0, 0, 0, dp(16))
        })
        val list = android.widget.ListView(this).apply {
            adapter = android.widget.ArrayAdapter<String>(this@OverlayActivity,
                android.R.layout.simple_list_item_1,
                matches.map { "${displayLocation(it.location)}   ·   ${it.recognizedId.ifBlank { "인식 ID 없음" }}" })
        }
        content.addView(list, LinearLayout.LayoutParams(-1, dp(250)))
        val dialog = AlertDialog.Builder(this)
            .setTitle("${statusName(code)} · ${matches.size}개")
            .setView(content)
            .setPositiveButton("닫기", null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            showDetail(matches[position])
        }
        dialog.show()
    }

    private fun showDetail(item: OverlayItem) {
        reinspection.showDetail(item)
    }

    private fun updateSummary() {
        summary.removeAllViews()
        val workflowBar = LinearLayout(this)
        workflowBar.addView(android.widget.Button(this).apply {
            text = "조치 중 ${reinspection.workingLocations().size} · 검수 대기 ${reinspection.pendingLocations().size}"
            textSize = 13f
            setOnClickListener { reinspection.showDashboard() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        workflowBar.addView(android.widget.Button(this).apply {
            text = if (reinspection.isClosed()) "검수 완료됨" else "검수 완료"
            textSize = 15f
            isEnabled = reinspection.canFinish()
            setOnClickListener { reinspection.confirmFinish() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(workflowBar)
        summary.addView(TextView(this).apply {
            text = "확인 필요 ${items.count { it.status != 1 }}개  ·  정상 ${items.count { it.status == 1 }}개"
            textSize = 17f
            setTextColor(Color.rgb(22, 39, 60))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, dp(10))
        })
        for (codes in listOf(listOf(2, 3, 4, 5))) {
            val row = LinearLayout(this)
            codes.forEach { code ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(7), dp(10), dp(7), dp(10))
                    background = roundedBackground(lightColor(statusColor(code)), 12f)
                    setOnClickListener { showStatusItems(code) }
                }
                card.addView(TextView(this).apply {
                    text = statusName(code)
                    textSize = 13f
                    setTextColor(statusColor(code))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                card.addView(TextView(this).apply {
                    text = "${items.count { it.status == code }}"
                    textSize = 24f
                    setTextColor(Color.rgb(22, 39, 60))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    setMargins(0, 0, dp(6), dp(6))
                })
            }
            summary.addView(row)
        }
    }

    private fun roundedBackground(color: Int, radius: Float) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * resources.displayMetrics.density
        }

    override fun onSaveInstanceState(outState: Bundle) {
        items.forEachIndexed { index, item ->
            outState.putString("id_$index", item.recognizedId)
            outState.putInt("status_$index", item.status)
            outState.putBoolean("edited_$index", item.manuallyEdited)
        }
        super.onSaveInstanceState(outState)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

// 저장용 위치 키(R1-1)는 유지하고 화면에는 A-01 형식으로 표시합니다.
fun displayLocation(location: String): String {
    val match = Regex("R([1-5])-([1-9])").matchEntire(location) ?: return location
    val row = match.groupValues[1].toInt() - 1
    val column = match.groupValues[2].toInt()
    return "${('A'.code + row).toChar()}-${column.toString().padStart(2, '0')}"
}

fun statusName(status: Int): String = when (status) {
    1 -> "정상"
    2 -> "오적재"
    3 -> "누락"
    4 -> "미등록"
    5 -> "수동확인"
    else -> "알 수 없음"
}

// 3~5를 각기 다른 색으로 구분한 UI 변경안입니다.
fun statusColor(status: Int): Int = when (status) {
    1 -> Color.rgb(18, 153, 96)     // 정상: 초록
    2 -> Color.rgb(225, 61, 74)     // 오적재: 빨강
    3 -> Color.rgb(203, 116, 9)     // 누락: 주황
    4 -> Color.rgb(124, 76, 205)    // 미등록: 보라
    else -> Color.rgb(34, 114, 201) // 수동확인: 파랑
}

fun lightColor(color: Int): Int = Color.rgb(
    (Color.red(color) * 0.10f + 255 * 0.90f).toInt(),
    (Color.green(color) * 0.10f + 255 * 0.90f).toInt(),
    (Color.blue(color) * 0.10f + 255 * 0.90f).toInt()
)

class WarehouseOverlayView(
    context: Context,
    private val items: List<OverlayItem>,
    private val onItemClick: (OverlayItem) -> Unit
) : View(context) {
    var showNormal = false
    var recheckLocations: Set<String> = emptySet()
    var workingLocations: Set<String> = emptySet()
    var needsActionLocations: Set<String> = emptySet()
    private val contrastStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(30, 42, 55)
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val scaler = android.view.ScaleGestureDetector(context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
                if (zoom == 1f) { panX = 0f; panY = 0f }
                tappedItem = null
                invalidate()
                return true
            }
        })
    private val bitmap = BitmapFactory.decodeResource(resources, R.drawable.warehouse)
    private val imageRect = RectF()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val locationTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val locationBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 16, 32, 48)
    }
    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val statusLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tappedItem: OverlayItem? = null

    init {
        isClickable = true
        contentDescription = "창고 사진과 검수 영역. 색상 테두리 안쪽을 누르면 상세 정보가 열립니다."
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val scale = min(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val imageWidth = bitmap.width * scale
        val imageHeight = bitmap.height * scale
        val left = (w - imageWidth) / 2f
        val top = (h - imageHeight) / 2f
        imageRect.set(left, top, left + imageWidth, top + imageHeight)
    }

    private fun screenBox(item: OverlayItem): RectF = RectF(
        imageRect.left + item.box.left * imageRect.width(),
        imageRect.top + item.box.top * imageRect.height(),
        imageRect.left + item.box.right * imageRect.width(),
        imageRect.top + item.box.bottom * imageRect.height()
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(zoom, zoom, width / 2f, height / 2f)
        canvas.drawBitmap(bitmap, null, imageRect, bitmapPaint)
        canvas.save()
        canvas.clipRect(imageRect)
        items.filter { showNormal || it.status != 1 || it.location in recheckLocations }.forEach { item ->
            val box = screenBox(item)
            val pending = item.location in recheckLocations
            val working = item.location in workingLocations
            val retry = item.location in needsActionLocations
            val displayColor = when {
                pending -> Color.WHITE
                working -> Color.rgb(225, 155, 30)
                retry -> Color.rgb(225, 61, 74)
                else -> statusColor(item.status)
            }
            stroke.color = displayColor
            val d = resources.displayMetrics.density
            stroke.pathEffect = if (pending) android.graphics.DashPathEffect(floatArrayOf(5f*d, 3f*d), 0f) else null
            if (pending) {
                contrastStroke.pathEffect = stroke.pathEffect
                canvas.drawRect(box, contrastStroke)
            }
            canvas.drawRect(box, stroke)
            // 위치 번호는 테두리 위에, 상태명은 테두리 안에 표시합니다.
            val locationLabel = displayLocation(item.location)
            val densityForLocation = resources.displayMetrics.density
            locationTextPaint.textSize = 10f * resources.displayMetrics.scaledDensity
            val availableLocationWidth = (box.width() - 4f * densityForLocation).coerceAtLeast(1f)
            val locationWidth = locationTextPaint.measureText(locationLabel)
            if (locationWidth > availableLocationWidth) {
                locationTextPaint.textSize *= availableLocationWidth / locationWidth
            }
            val fm = locationTextPaint.fontMetrics
            val locationHeight = fm.descent - fm.ascent + 4f * densityForLocation
            val tagWidth = min(box.width(), locationTextPaint.measureText(locationLabel) + 4f * densityForLocation)
            val tagBottom = box.top.coerceAtLeast(imageRect.top + locationHeight)
            val tag = RectF(box.centerX() - tagWidth / 2, tagBottom - locationHeight,
                box.centerX() + tagWidth / 2, tagBottom)
            canvas.drawRoundRect(tag, 2f * densityForLocation, 2f * densityForLocation, locationBackground)
            canvas.drawText(locationLabel, tag.centerX(),
                tag.centerY() - (fm.ascent + fm.descent) / 2, locationTextPaint)
            // 정상은 테두리만 표시하고, 나머지는 영역 안에 상태명만 표시합니다.
            if (item.status != 1 || pending) {
                val text = when {
                    pending -> "검수 대기"
                    working -> "조치 중"
                    retry -> "재조치 필요"
                    else -> statusName(item.status)
                }
                val density = resources.displayMetrics.density
                val horizontalPadding = min(4f * density, box.width() * .08f)
                statusTextPaint.textSize = 12f * resources.displayMetrics.scaledDensity
                val availableWidth = (box.width() - horizontalPadding * 2).coerceAtLeast(1f)
                val measuredWidth = statusTextPaint.measureText(text)
                if (measuredWidth > availableWidth) {
                    statusTextPaint.textSize *= availableWidth / measuredWidth
                }
                var metrics = statusTextPaint.fontMetrics
                val maxTextHeight = box.height() * .65f
                val textHeight = metrics.descent - metrics.ascent
                if (textHeight > maxTextHeight) {
                    statusTextPaint.textSize *= maxTextHeight / textHeight
                    metrics = statusTextPaint.fontMetrics
                }
                val labelHeight = min(box.height(), metrics.descent - metrics.ascent + 6f * density)
                val labelWidth = min(box.width(), statusTextPaint.measureText(text) + horizontalPadding * 2)
                val labelRect = RectF(
                    box.centerX() - labelWidth / 2,
                    box.top,
                    box.centerX() + labelWidth / 2,
                    box.top + labelHeight
                )
                statusLabelPaint.color = displayColor
                canvas.drawRoundRect(labelRect, 3f * density, 3f * density, statusLabelPaint)
                statusTextPaint.color = if (pending) Color.rgb(22, 39, 60) else Color.WHITE
                val baseline = labelRect.centerY() - (metrics.ascent + metrics.descent) / 2
                canvas.drawText(text, labelRect.centerX(), baseline, statusTextPaint)
            }
        }
        canvas.restore()
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        val x = (event.x - panX - width / 2f) / zoom + width / 2f
        val y = (event.y - panY - height / 2f) / zoom + height / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 배경 영역은 무시합니다. 겹친 영역은 마지막에 그린 영역을 선택합니다.
                tappedItem = items.lastOrNull { (showNormal || it.status != 1 || it.location in recheckLocations) && screenBox(it).contains(x, y) }
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val item = tappedItem
                tappedItem = null
                if (item != null && abs(event.x - downX) <= touchSlop &&
                    abs(event.y - downY) <= touchSlop && screenBox(item).contains(x, y)) {
                    performClick()
                    onItemClick(item)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { tappedItem = null; return true }
            MotionEvent.ACTION_MOVE -> {
                if (zoom > 1f && !scaler.isInProgress && event.pointerCount == 1) {
                    panX = (panX + event.x - lastX).coerceIn(-width * (zoom - 1) / 2, width * (zoom - 1) / 2)
                    panY = (panY + event.y - lastY).coerceIn(-height * (zoom - 1) / 2, height * (zoom - 1) / 2)
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) tappedItem = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}