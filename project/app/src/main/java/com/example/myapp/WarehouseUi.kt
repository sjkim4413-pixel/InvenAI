package com.example.myapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import java.text.DateFormat
import java.util.Date

internal object WarehouseAppearance {
    const val TEAL = 0xFF0D9488.toInt()
    const val TEAL_LIGHT = 0xFFF0FDFA.toInt()
    const val BACKGROUND = 0xFFF8FAFC.toInt()
    const val INK = 0xFF0F172A.toInt()
    const val MUTED = 0xFF64748B.toInt()
    const val BORDER = 0xFFE2E8F0.toInt()
    const val DISABLED = 0xFFCBD5E1.toInt()

    fun shape(context: Context, color: Int, radius: Int = 16, border: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * context.resources.displayMetrics.density
            if (border != null) setStroke(context.resources.displayMetrics.density.toInt().coerceAtLeast(1), border)
        }

    fun ripple(context: Context, color: Int = Color.WHITE, radius: Int = 16) =
        RippleDrawable(ColorStateList.valueOf(0x160D9488), shape(context, color, radius),
            shape(context, Color.WHITE, radius))

    fun states(disabled: Int, checked: Int, normal: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(disabled, checked, normal)
    )
}

internal fun inventoryIntent(activity: Activity, destination: Class<*>, item: InventoryItem): Intent =
    Intent(activity, destination).putExtra("INVENTORY_ID", item.documentId)

internal fun InventoryItem.matchesScan(code: String): Boolean =
    itemCode.equals(code, ignoreCase = true) || code in barcodes

internal fun openWarehouseHome(activity: Activity) {
    val target = when (InventoryManager.role(activity)) {
        "QA" -> HomeDashboard::class.java
        "LOADER" -> LoaderHomeDashboard::class.java
        else -> Frame1::class.java
    }
    activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    activity.finish()
}

internal fun InventoryItem.statusLabel(): String = when (status) {
    "NORMAL", "정상" -> "정상"
    "ANOMALY" -> "이상 확인 필요"
    "PENDING_QA" -> "검수 대기"
    "OUTBOUND_COMPLETE" -> "출고 완료"
    else -> status.ifBlank { "상태 미등록" }
}

internal fun inventoryDate(time: Long): String =
    if (time <= 0) "기록 없음" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

internal fun warehouseRow(
    activity: Activity,
    title: String,
    details: String,
    icon: Int = R.drawable.ic_inven_inventory,
    open: () -> Unit
): View {
    val density = activity.resources.displayMetrics.density
    fun pixels(value: Int) = (value * density).toInt()
    return LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(pixels(14), pixels(16), pixels(12), pixels(16))
        background = WarehouseAppearance.ripple(activity)
        foreground = WarehouseAppearance.shape(activity, Color.TRANSPARENT, border = WarehouseAppearance.BORDER)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pixels(10) }

        addView(ImageView(activity).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(WarehouseAppearance.TEAL)
            setPadding(pixels(9), pixels(9), pixels(9), pixels(9))
            background = WarehouseAppearance.shape(activity, WarehouseAppearance.TEAL_LIGHT, 12)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(pixels(40), pixels(40)).apply { marginEnd = pixels(12) })

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(WarehouseAppearance.INK)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            })
            addView(TextView(activity).apply {
                text = details
                textSize = 12f
                setTextColor(WarehouseAppearance.MUTED)
                setLineSpacing(pixels(3).toFloat(), 1f)
                setPadding(0, pixels(7), 0, 0)
                includeFontPadding = false
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))

        addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_inven_chevron_right)
            imageTintList = ColorStateList.valueOf(0xFF94A3B8.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(pixels(18), pixels(18)).apply { marginStart = pixels(6) })
        isFocusable = true
        setOnClickListener { open() }
    }
}

internal fun warehouseEventCard(activity: Activity, event: WarehouseEvent): View {
    val position = if (event.alertId.isNotBlank() && event.action != WarehouseAction.AUDIT_RELOCATION)
        "발견 ${event.fromLocation} · 조치 목표 ${event.toLocation.ifBlank { "확인 필요" }}"
    else if (event.toLocation.isBlank()) "${event.fromLocation} · 이동 위치 확인 필요"
    else if (event.fromLocation.isBlank() || event.fromLocation == event.toLocation) event.toLocation
    else "${event.fromLocation} → ${event.toLocation}"
    val person = if (event.actorUid.isBlank()) "처리자 미기록"
    else if (FirebaseAuth.getInstance().currentUser?.uid == event.actorUid) {
        activity.getSharedPreferences("app_user_session", Activity.MODE_PRIVATE)
            .getString("USER_ID", null) ?: event.actorUid.take(8)
    } else event.actorUid.take(8)
    val quantities = if (!event.quantityKnown) "수량 미확정" else if (event.beforeQuantity == event.afterQuantity) "${event.afterQuantity}개" else
        "${event.beforeQuantity}개 → ${event.afterQuantity}개"
    val icon = if (event.action.anomaly) R.drawable.ic_inven_alert else R.drawable.ic_inven_transfer
    val details = buildString {
        append("${event.itemCode} · ${event.warehouse.ifBlank { "창고 미등록" }} · $position\n$quantities · 작업자 $person · ${inventoryDate(event.occurredAt)}")
        if (event.detail.isNotBlank()) append("\n${event.detail}")
        if (event.action in setOf(WarehouseAction.AUDIT_RELOCATION, WarehouseAction.REVIEW_REQUEST)) append("\n조치 회신 · 재검수 대기")
        if (event.note.isNotBlank()) append("\n메모 · ${event.note}")
    }
    return warehouseRow(activity, "${if (event.isTest) "[테스트] " else ""}${event.action.label} · ${event.itemName}", details,
        icon
    ) {
        if (event.alertId.isNotBlank()) {
            androidx.appcompat.app.AlertDialog.Builder(activity).setTitle(event.action.label)
                .setMessage(details + if (event.inferred) "\n\n기존 알림에 남아 있는 발생 시각 또는 최종 상태 기준 기록입니다." else "")
                .setPositiveButton("확인", null).show()
        } else activity.startActivity(Intent(activity, InventoryDetail::class.java).putExtra("INVENTORY_ID", event.documentId))
    }
}

internal fun warehouseNavigation(activity: Activity): View = LinearLayout(activity).apply {
    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(Color.WHITE)
    addView(View(activity).apply { setBackgroundColor(WarehouseAppearance.BORDER) },
        LinearLayout.LayoutParams(-1, dp(1)))
    addView(LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(6))
        fun destination(title: String, icon: Int, current: Boolean = false, open: () -> Unit) {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = WarehouseAppearance.ripple(activity,
                    if (current) WarehouseAppearance.TEAL_LIGHT else Color.WHITE, 14)
                addView(ImageView(activity).apply {
                    setImageResource(icon)
                    if (title != "스캔") imageTintList = ColorStateList.valueOf(
                        if (current) WarehouseAppearance.TEAL else WarehouseAppearance.MUTED)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(if (title == "스캔") 44 else 22), dp(if (title == "스캔") 44 else 22)))
                addView(TextView(activity).apply {
                    text = title
                    textSize = 11f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setPadding(0, dp(5), 0, 0)
                    setTextColor(if (current) WarehouseAppearance.TEAL else WarehouseAppearance.MUTED)
                    if (current) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                if (!current) {
                    isFocusable = true
                    setOnClickListener { open() }
                }
            }, LinearLayout.LayoutParams(0, dp(62), 1f))
        }
        destination("홈", R.drawable.ic_inven_home, activity is HomeDashboard) { openWarehouseHome(activity) }
        destination("재고", R.drawable.ic_inven_inventory, activity is InventoryList) {
            activity.startActivity(Intent(activity, InventoryList::class.java))
        }
        destination("스캔", R.drawable.ic_inven_scan_fab) {
            activity.startActivity(Intent(activity, BarcodeScanner::class.java))
        }
        destination("작업", R.drawable.ic_inven_work, activity is WorkHistory) {
            activity.startActivity(Intent(activity, WorkHistory::class.java))
        }
        destination("더보기", R.drawable.ic_inven_more) { MenuHelper.showMoreMenu(activity) }
    }, LinearLayout.LayoutParams(-1, -2))
}

abstract class WarehouseScreen : AppCompatActivity() {
    protected lateinit var body: LinearLayout
    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    protected fun openScreen(title: String, navigation: Boolean = true) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WarehouseAppearance.BACKGROUND)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(6), dp(20), dp(6))
        }
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_inven_back)
            contentDescription = "뒤로"
            background = WarehouseAppearance.ripple(this@WarehouseScreen, radius = 14)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = title
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(WarehouseAppearance.INK)
            setPadding(dp(8), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        layout.addView(header, LinearLayout.LayoutParams(-1, dp(64)))
        layout.addView(View(this).apply { setBackgroundColor(WarehouseAppearance.BORDER) },
            LinearLayout.LayoutParams(-1, dp(1)))
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        layout.addView(ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (navigation) layout.addView(warehouseNavigation(this))
        setContentView(layout)
    }

    protected fun label(text: String, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = if (bold) 17f else 14f
            setTextColor(if (bold) WarehouseAppearance.INK else WarehouseAppearance.MUTED)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }

    protected fun button(
        text: String,
        primary: Boolean = false,
        icon: Int? = null,
        action: () -> Unit
    ): MaterialButton = MaterialButton(this).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isAllCaps = false
        cornerRadius = dp(14)
        insetTop = 0
        insetBottom = 0
        minHeight = 0
        minimumHeight = 0
        elevation = 0f
        stateListAnimator = null
        setPadding(dp(16), 0, dp(16), 0)
        backgroundTintList = WarehouseAppearance.states(WarehouseAppearance.BORDER,
            WarehouseAppearance.TEAL_LIGHT, if (primary) WarehouseAppearance.TEAL else Color.WHITE)
        setTextColor(WarehouseAppearance.states(0xFF94A3B8.toInt(), WarehouseAppearance.TEAL,
            if (primary) Color.WHITE else WarehouseAppearance.TEAL))
        strokeWidth = if (primary) 0 else dp(1)
        strokeColor = WarehouseAppearance.states(WarehouseAppearance.BORDER, 0xFF99F6E4.toInt(),
            WarehouseAppearance.BORDER)
        rippleColor = ColorStateList.valueOf(if (primary) 0x30FFFFFF else 0x160D9488)
        if (icon != null) {
            setIconResource(icon)
            iconTint = WarehouseAppearance.states(0xFF94A3B8.toInt(), WarehouseAppearance.TEAL,
                if (primary) Color.WHITE else WarehouseAppearance.TEAL)
            iconSize = dp(20)
            iconPadding = dp(8)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
        setOnClickListener { action() }
        body.addView(this, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) })
    }

    protected fun actionRow(vararg actions: MaterialButton) {
        val position = actions.map { body.indexOfChild(it) }.filter { it >= 0 }.minOrNull() ?: body.childCount
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.forEachIndexed { index, action ->
            (action.parent as? ViewGroup)?.removeView(action)
            row.addView(action, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        body.addView(row, position, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }

    protected fun card(vararg contents: View) {
        val position = contents.map { body.indexOfChild(it) }.filter { it >= 0 }.minOrNull() ?: body.childCount
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(6))
            background = WarehouseAppearance.shape(this@WarehouseScreen, Color.WHITE, border = WarehouseAppearance.BORDER)
        }
        for (view in contents) {
            (view.parent as? ViewGroup)?.removeView(view)
            card.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        body.addView(card, position, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }

    protected fun field(hint: String, numeric: Boolean = false): EditText =
        EditText(this).apply {
            this.hint = hint
            contentDescription = hint
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            textSize = if (numeric) 20f else 14f
            setTextColor(WarehouseAppearance.INK)
            setHintTextColor(0xFF94A3B8.toInt())
            if (numeric) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            setSingleLine(true)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused),
                    WarehouseAppearance.shape(this@WarehouseScreen, Color.WHITE, 14, WarehouseAppearance.TEAL))
                addState(intArrayOf(),
                    WarehouseAppearance.shape(this@WarehouseScreen, Color.WHITE, 14, WarehouseAppearance.BORDER))
            }
            backgroundTintList = null
            minimumHeight = dp(56)
            body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }

    protected fun message(message: String) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("확인", null).show()
    }

    protected fun choose(title: String, choices: List<String>, selectedIndex: Int = -1, onSelected: (Int) -> Unit) {
        if (choices.isEmpty()) return message("조건에 맞는 항목이 없습니다.")
        WarehouseMenus.show(this, title, choices, selectedIndex, onSelected)
    }

    protected fun selector(text: String, action: () -> Unit): MaterialButton =
        button(text, action = action).also(WarehouseMenus::decorateSelector)
}
