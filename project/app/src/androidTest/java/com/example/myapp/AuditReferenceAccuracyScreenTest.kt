package com.example.myapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
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

/** The six final real API responses exercise the production screen, not login. */
@RunWith(AndroidJUnit4::class)
class AuditReferenceAccuracyScreenTest {
    @Test fun allSixReferencePhotosShowEveryRegisteredPositionAndTheirRealGreenOverlays() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = "audit_test_images"
        val images = parseAuditTestImages(
            context.assets.open("$directory/test_images.json").bufferedReader().use { it.readText() },
            context.assets.list(directory).orEmpty().filter { it.endsWith(".png") }
        ).filter { it.variant == "normal" }
        assertEquals(6, images.size)
        var total = 0
        for (entry in images) {
            val result = instrumentation.context.assets.open("accuracy43/${entry.file.removeSuffix(".png")}.json")
                .bufferedReader().use { Gson().fromJson(it, F03AnalyzeAndCompareResponse::class.java) }
            val expected = result.totalPositions
            assertEquals(expected, result.results.size)
            assertEquals(expected, result.results.count { it.testLabelMatch })
            assertTrue(result.results.none { it.productLocationVerified || it.quantityVerified == true })
            assertEquals(expected, result.photoMarks().size)
            assertTrue(result.photoMarks().all { it.color == 0xFF059669.toInt() })
            total += expected
            CapturedPhotoHolder.getAndClear()
            val intent = Intent(context, AiAuditResults::class.java).putExtra(EXTRA_AUDIT_TEST_TITLE, entry.title)
                .putExtra(EXTRA_TEST_RACK_LABELS, true)
            ActivityScenario.launch<AiAuditResults>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val photo = activity.findViewById<AuditPhotoView>(R.id.ivAiCapturedPhoto)
                    photo.setImageBitmap(context.assets.open("$directory/${entry.file}").use { BitmapFactory.decodeStream(it) })
                    activity.findViewById<View>(R.id.auditPhotoPanel).visibility = View.VISIBLE
                    AiAuditResults::class.java.getDeclaredMethod("renderComparison", F03AnalyzeAndCompareResponse::class.java)
                        .apply { isAccessible = true }.invoke(activity, result)
                    assertEquals("${expected}건", activity.findViewById<TextView>(R.id.rcmltsyaqay).text.toString())
                    assertEquals("${expected}건", activity.findViewById<TextView>(R.id.r1o3adrmw6zi).text.toString())
                    assertEquals("0건", activity.findViewById<TextView>(R.id.rd1claoa9qi).text.toString())
                    val container = activity.findViewById<LinearLayout>(R.id.llAuditDetailContainer)
                    val texts = (0 until container.childCount).mapNotNull { container.getChildAt(it) as? TextView }
                    assertTrue(texts.any { it.text.contains("100% ($expected/$expected)") })
                    assertTrue(texts.any { it.text.contains("이 촬영 장면을 학습에 포함한 테스트 모델") })
                    val toggle = (0 until container.childCount).mapNotNull { container.getChildAt(it) as? CheckBox }
                        .find { it.text.startsWith("위치 미연결 탐지 후보") }
                    assertNull(toggle)
                    assertTrue(texts.none { it.text.toString() in setOf("대조 결과 JSON", "대조 결과 CSV", "같은 사진 다시 대조", "OCR로 읽은 글자 보기") })
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val photo = activity.findViewById<AuditPhotoView>(R.id.ivAiCapturedPhoto)
                    val bounds = RectF(photo.drawable.bounds)
                    photo.imageMatrix.mapRect(bounds)
                    bounds.offset(photo.paddingLeft.toFloat(), photo.paddingTop.toFloat())
                    val bitmap = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
                    photo.draw(Canvas(bitmap))
                    for (mark in result.photoMarks()) {
                        val box = mark.detection.bbox
                        val x = (bounds.left + (box[0] + box[2]) * .5f * bounds.width()).toInt()
                        val y = (bounds.top + box[1] * bounds.height()).toInt()
                        assertTrue("Rendered ${entry.title}: ${mark.detection.id}", (-3..3).any { dx ->
                            (-3..3).any { dy -> x+dx in 0 until bitmap.width && y+dy in 0 until bitmap.height &&
                                bitmap.getPixel(x+dx,y+dy) == 0xFF059669.toInt() }
                        })
                    }
                    bitmap.recycle()
                }
                SystemClock.sleep(400)
                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val number = entry.title.substringAfter("테스트 ").take(2)
                File(context.getExternalFilesDir(null), "accuracy43_$number.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                screenshot.recycle()
                scenario.onActivity { activity ->
                    val photo = activity.findViewById<AuditPhotoView>(R.id.ivAiCapturedPhoto)
                    val bounds = RectF(photo.drawable.bounds)
                    photo.imageMatrix.mapRect(bounds)
                    val box = result.photoMarks().first().detection.bbox
                    val x = photo.paddingLeft + bounds.left + (box[0]+box[2])*.5f*bounds.width()
                    val y = photo.paddingTop + bounds.top + (box[1]+box[3])*.5f*bounds.height()
                    val time = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        MotionEvent.obtain(time,time+30,action,x,y,0).let { photo.dispatchTouchEvent(it); it.recycle() }
                    }
                }
                onView(withText(containsString("사진에서 읽은 테스트 코드")))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()))
                onView(withText("닫기")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            }
        }
        assertEquals(109, total)
    }
}
