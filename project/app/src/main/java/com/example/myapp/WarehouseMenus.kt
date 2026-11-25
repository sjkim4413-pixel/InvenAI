package com.example.myapp

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Shared outlined selection menus, using the home screen's teal palette. */
internal object WarehouseMenus {
    fun decorateAnchor(view: View) {
        val context = view.context
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        view.background = WarehouseAppearance.ripple(context, WarehouseAppearance.TEAL_LIGHT, 14)
        view.foreground = WarehouseAppearance.shape(context, Color.TRANSPARENT, 14, 0xFFB2DCD6.toInt())
        view.setPadding(dp(12), dp(10), dp(12), dp(10))
        view.minimumHeight = dp(48)
        view.isFocusable = true
    }

    fun decorateSelector(button: MaterialButton) {
        fun dp(value: Int) = (value * button.resources.displayMetrics.density).toInt()
        button.strokeWidth = dp(1).coerceAtLeast(1)
        button.strokeColor = ColorStateList.valueOf(0xFFB2DCD6.toInt())
        button.setTextColor(WarehouseAppearance.INK)
        button.setIconResource(R.drawable.ic_inven_chevron_down)
        button.iconTint = ColorStateList.valueOf(WarehouseAppearance.TEAL)
        button.iconGravity = MaterialButton.ICON_GRAVITY_END
        button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    fun show(activity: Activity, title: String, choices: List<String>, selectedIndex: Int = -1,
             onSelected: (Int) -> Unit): AlertDialog {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        if (choices.isEmpty()) return MaterialAlertDialogBuilder(activity).setTitle(title)
            .setMessage("선택할 수 있는 항목이 없습니다.").setPositiveButton("확인", null).show()
        val list = ListView(activity).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(8)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            clipToPadding = false
            isVerticalScrollBarEnabled = true
            background = ColorDrawable(Color.WHITE)
            adapter = object : BaseAdapter() {
                override fun getCount() = choices.size
                override fun getItem(position: Int) = choices[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val selected = position == selectedIndex
                    return LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(58)
                        setPadding(dp(14), dp(14), dp(14), dp(14))
                        background = WarehouseAppearance.ripple(activity,
                            if (selected) WarehouseAppearance.TEAL_LIGHT else Color.WHITE, 12)
                        foreground = WarehouseAppearance.shape(activity, Color.TRANSPARENT, 12,
                            if (selected) WarehouseAppearance.TEAL else WarehouseAppearance.BORDER)
                        contentDescription = choices[position] + if (selected) " · 선택됨" else ""
                        addView(TextView(activity).apply {
                            text = choices[position]
                            textSize = 14f
                            setTextColor(if (selected) WarehouseAppearance.TEAL else WarehouseAppearance.INK)
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            maxLines = 3
                            ellipsize = TextUtils.TruncateAt.END
                            setLineSpacing(dp(3).toFloat(), 1f)
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(ImageView(activity).apply {
                            setImageResource(if (selected) R.drawable.ic_inven_check else R.drawable.ic_inven_chevron_right)
                            imageTintList = ColorStateList.valueOf(if (selected) WarehouseAppearance.TEAL else 0xFF94A3B8.toInt())
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(12) })
                    }
                }
            }
        }
        val height = minOf(dp(choices.size * 74 + 16), (activity.resources.displayMetrics.heightPixels * .55f).toInt())
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(list, LinearLayout.LayoutParams(-1, height))
        }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(title).setView(container)
            .setNegativeButton("취소", null).create()
        list.setOnItemClickListener { _, _, index, _ -> dialog.dismiss(); onSelected(index) }
        dialog.show()
        dialog.window?.setBackgroundDrawable(WarehouseAppearance.shape(activity, Color.WHITE, 22, WarehouseAppearance.BORDER))
        return dialog
    }
}
