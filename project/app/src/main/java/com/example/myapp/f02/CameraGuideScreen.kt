
package com.example.myapp.f02

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

class CameraGuideScreen(context: Context) : FrameLayout(context) {

    init {
        setBackgroundColor(Color.BLACK)

        val title = TextView(context).apply {
            text = "InvenAI F-02 카메라 촬영 가이드"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        addView(
            title,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        )
    }
}
