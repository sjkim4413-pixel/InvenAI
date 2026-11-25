package com.example.myapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.hamcrest.CoreMatchers.containsString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual pixel-comparison responses drive the installed production renderer. */
@RunWith(AndroidJUnit4::class)
class F05ReferencePairsScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun images(): List<AuditTestImage> {
        val files = context.assets.list("audit_test_images").orEmpty().filter { it.endsWith(".png") }
        return parseAuditTestImages(context.assets.open("audit_test_images/test_images.json")
            .bufferedReader().use { it.readText() }, files)
    }

    @Test fun pickerContainsExactlySixCompletePairsWithoutTheRetiredTwoPhotos() {
        val entries = images()
        assertEquals(12, entries.size)
        assertEquals(6, entries.count { it.variant == "normal" })
        assertEquals(6, entries.count { it.variant == "misplaced" })
        assertTrue(entries.all { it.group == "paired" && it.mode == "test_reference_pairs" })
        assertFalse(context.assets.list("audit_test_images").orEmpty().any { it.startsWith("compare_0") })
        assertEquals((1..12).toList(), entries.map { it.title.substringAfter("테스트 ").take(2).toInt() })
        assertTrue(entries.chunked(2).all { it[0].pairId == it[1].pairId && it[0].warehouseCode == it[1].warehouseCode })
    }

    @Test fun twelvePhotosRenderAllActualGreenRedOrangeMarksAndOpenTheirEvidence() {
        for (entry in images()) {
            val result = instrumentation.context.assets.open("paired44/${entry.file.removeSuffix(".png")}.json")
                .bufferedReader().use { Gson().fromJson(it, F03AnalyzeAndCompareResponse::class.java) }
            val normal = entry.variant == "normal"
            val total = result.totalPositions
            val matched = result.results.count { it.isMatch }
            assertEquals(if (normal) total else total - 2, matched)
            assertTrue(result.results.none { it.productLocationVerified || it.quantityVerified == true || it.verificationPassed })
            assertEquals(total, result.photoMarks().size)
            assertEquals(if (normal) 0 else 2, result.photoMarks().count { it.color != 0xFF059669.toInt() })
            CapturedPhotoHolder.getAndClear()
            val intent = Intent(context, AiAuditResults::class.java).putExtra(EXTRA_AUDIT_TEST_TITLE, entry.title)
                .putExtra(EXTRA_AUDIT_TEST_MODE, entry.mode)
            ActivityScenario.launch<AiAuditResults>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val photo = activity.findViewById<AuditPhotoView>(R.id.ivAiCapturedPhoto)
                    photo.setImageBitmap(context.assets.open("audit_test_images/${entry.file}").use { BitmapFactory.decodeStream(it) })
                    activity.findViewById<View>(R.id.auditPhotoPanel).visibility = View.VISIBLE
                    AiAuditResults::class.java.getDeclaredMethod("renderComparison", F03AnalyzeAndCompareResponse::class.java)
                        .apply { isAccessible = true }.invoke(activity, result)
                    assertEquals("${total}건", activity.findViewById<TextView>(R.id.rcmltsyaqay).text.toString())
                    assertEquals("${matched}건", activity.findViewById<TextView>(R.id.r1o3adrmw6zi).text.toString())
                    assertEquals(if (normal) "0건" else "2건", activity.findViewById<TextView>(R.id.rd1claoa9qi).text.toString())
                    val container = activity.findViewById<LinearLayout>(R.id.llAuditDetailContainer)
                    assertTrue((0 until container.childCount).mapNotNull { container.getChildAt(it) as? TextView }
                        .any { it.text.contains("기준 사진·위치 일치율") })
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val photo = activity.findViewById<AuditPhotoView>(R.id.ivAiCapturedPhoto)
                    val bounds = RectF(photo.drawable.bounds)
                    photo.imageMatrix.mapRect(bounds)
                    bounds.offset(photo.paddingLeft.toFloat(), photo.paddingTop.toFloat())
                    val rendered = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
                    photo.draw(Canvas(rendered))
                    for (mark in result.photoMarks()) {
                        val box = mark.detection.bbox
                        val x = (bounds.left + (box[0]+box[2])*.5f*bounds.width()).toInt()
                        val y = (bounds.top + box[1]*bounds.height()).toInt()
                        assertTrue("${entry.title}: ${mark.detection.id}", (-3..3).any { dx -> (-3..3).any { dy ->
                            x+dx in 0 until rendered.width && y+dy in 0 until rendered.height && rendered.getPixel(x+dx,y+dy) == mark.color
                        } })
                    }
                    rendered.recycle()
                }
                SystemClock.sleep(250)
                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(context.getExternalFilesDir(null), "paired44_${entry.pairId}_${entry.variant}.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG,100,it))
                }
                screenshot.recycle()
                scenario.onActivity { activity ->
                    val photo = activity.findViewById<AuditPhotoView>(R.id.ivAiCapturedPhoto)
                    val bounds = RectF(photo.drawable.bounds);photo.imageMatrix.mapRect(bounds)
                    val mark = result.photoMarks().firstOrNull { it.color != 0xFF059669.toInt() } ?: result.photoMarks().first()
                    val box = mark.detection.bbox
                    val x = photo.paddingLeft+bounds.left+(box[0]+box[2])*.5f*bounds.width()
                    val y = photo.paddingTop+bounds.top+(box[1]+box[3])*.5f*bounds.height()
                    val time = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                        MotionEvent.obtain(time,time+30,action,x,y,0).let { photo.dispatchTouchEvent(it);it.recycle() }
                    }
                }
                onView(withText(containsString("상품 비교 · 원본 기준 사진")))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()))
                onView(withText("닫기")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            }
        }
    }
}
