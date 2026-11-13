package com.example.myapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import com.google.android.material.imageview.ShapeableImageView

internal data class AuditPhotoMark(val detection: F03Detection, val position: F03ComparedPosition?) {
    val color: Int get() = when {
        position?.testLabelMatch == true && detection.testLabelMatch -> 0xFF059669.toInt()
        position?.isVerifiedMatch == true && detection.locationVerified &&
            (detection.verificationPassed || detection.productLocationVerified) -> 0xFF059669.toInt()
        position?.isConfirmedAnomaly == true && detection.locationVerified &&
            (!position.appearanceLocationVerified || detection.source == "registered_appearance") -> 0xFFDC2626.toInt()
        else -> 0xFFD97706.toInt()
    }
}

internal fun F03AnalyzeAndCompareResponse.photoMarks(): List<AuditPhotoMark> {
    val positionsByDetection = results.orEmpty().flatMap { row ->
        row.detectionIds.orEmpty().map { id -> id to row }
    }.groupBy({ it.first }, { it.second })
    return detections.orEmpty().mapNotNull { detection ->
        if (detection.overlayParentId != null) return@mapNotNull null
        val box = detection.bbox.orEmpty()
        if (box.size != 4 || box.any { !it.isFinite() || it !in 0f..1f } ||
            box[2] <= box[0] || box[3] <= box[1]) return@mapNotNull null
        val position = positionsByDetection[detection.id]?.singleOrNull()
            ?.takeIf { detection.locationVerified || it.locationVerified || (it.floor == null && it.ocrTokenIds != null) }
        AuditPhotoMark(detection, position)
    }
}

class AuditPhotoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ShapeableImageView(context, attrs, defStyleAttr) {
    private val imageBounds = RectF()
    private val markBounds = RectF()
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * resources.displayMetrics.density
    }
    private var marks = emptyList<AuditPhotoMark>()
    private var selectedMark: AuditPhotoMark? = null
    internal var onMarkClick: ((AuditPhotoMark) -> Unit)? = null

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = markAt(event.x, event.y) != null

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            selectedMark = markAt(event.x, event.y)
            return if (selectedMark != null) performClick() else false
        }
    })

    internal fun showMarks(value: List<AuditPhotoMark>) {
        marks = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (marks.isEmpty() || !updateImageBounds()) return

        canvas.save()
        canvas.clipRect(imageBounds)
        for (mark in marks) {
            placeMark(mark)
            border.color = mark.color
            canvas.drawRect(markBounds, border)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gesture.onTouchEvent(event)

    override fun performClick(): Boolean {
        super.performClick()
        val mark = selectedMark ?: return false
        selectedMark = null
        onMarkClick?.invoke(mark)
        return true
    }

    private fun updateImageBounds(): Boolean {
        val image = drawable ?: return false
        imageBounds.set(image.bounds)
        imageMatrix.mapRect(imageBounds)
        imageBounds.offset(paddingLeft.toFloat(), paddingTop.toFloat())
        return !imageBounds.isEmpty
    }

    private fun placeMark(mark: AuditPhotoMark) {
        val box = mark.detection.bbox
        markBounds.set(
            imageBounds.left + box[0] * imageBounds.width(),
            imageBounds.top + box[1] * imageBounds.height(),
            imageBounds.left + box[2] * imageBounds.width(),
            imageBounds.top + box[3] * imageBounds.height()
        )
    }

    private fun markAt(x: Float, y: Float): AuditPhotoMark? {
        if (!updateImageBounds()) return null
        return marks.filter { mark ->
            placeMark(mark)
            markBounds.contains(x, y)
        }.minByOrNull { mark ->
            val box = mark.detection.bbox
            (box[2] - box[0]) * (box[3] - box[1])
        }
    }
}
