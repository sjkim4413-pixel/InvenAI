package com.example.myapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BarcodeScanner : WarehouseScreen() {
    private val executor = Executors.newSingleThreadExecutor()
    private val reading = AtomicBoolean(false)
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private lateinit var preview: PreviewView
    private lateinit var flash: MaterialButton
    @Volatile private var mode = 0
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) startPreview() else message("카메라 권한이 필요합니다. 코드를 직접 입력할 수 있습니다.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openScreen("바코드 / QR 스캔", navigation = false)
        mode = savedInstanceState?.getInt("scanMode") ?: 0
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            background = WarehouseAppearance.shape(this@BarcodeScanner, WarehouseAppearance.INK)
            clipToOutline = true
        }
        body.addView(preview, LinearLayout.LayoutParams(-1, dp(330)).apply { bottomMargin = dp(16) })
        label("카메라에 코드를 맞춰 주세요.")
        val modeButton = selector(modeNames[mode]) {
            val selector = itButton()
            choose("인식 형식", modeNames, mode) { index -> mode = index; selector?.text = modeNames[index] }
        }.also { it.id = MODE_BUTTON_ID }
        flash = button("플래시 켜기", icon = R.drawable.ic_inven_flash) {
            val active = camera ?: return@button
            val turnOn = active.cameraInfo.torchState.value != androidx.camera.core.TorchState.ON
            val result = active.cameraControl.enableTorch(turnOn)
            result.addListener({
                if (isDestroyed) return@addListener
                runCatching { result.get() }.onSuccess {
                    flash.text = if (turnOn) "플래시 끄기" else "플래시 켜기"
                }.onFailure { message("플래시를 제어하지 못했습니다.") }
            }, ContextCompat.getMainExecutor(this))
        }.apply { isEnabled = false }
        actionRow(modeButton, flash)
        button("직접 입력", primary = true, icon = R.drawable.ic_inven_keyboard) {
            val value = EditText(this).apply {
                setSingleLine(true)
                hint = if (intent.getBooleanExtra(LOCATION_MODE, false)) "위치 코드" else "상품 코드 또는 바코드"
            }
            AlertDialog.Builder(this).setTitle("코드 입력").setView(value).setNegativeButton("취소", null)
                .setPositiveButton("확인") { _, _ -> complete(value.text.toString(), false) }.show()
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startPreview()
        } else permission.launch(Manifest.permission.CAMERA)
    }

    private fun itButton(): MaterialButton? = findViewById(MODE_BUTTON_ID)

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("scanMode", mode)
        super.onSaveInstanceState(outState)
    }

    private fun startPreview() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = providerFuture.get()
                val view = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                imageAnalysis.setAnalyzer(executor, ::decode)
                analysis = imageAnalysis
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, view, imageAnalysis)
                flash.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
            } catch (_: Exception) {
                message("카메라를 열지 못했습니다. 코드를 직접 입력할 수 있습니다.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun decode(image: ImageProxy) {
        try {
            if (reading.get()) return
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer.duplicate()
            val start = buffer.position()
            val pixels = ByteArray(width * height)
            if (plane.pixelStride == 1) {
                for (row in 0 until height) {
                    buffer.position(start + row * plane.rowStride)
                    buffer.get(pixels, row * width, width)
                }
            } else {
                for (row in 0 until height) for (column in 0 until width) {
                    pixels[row * width + column] = buffer.get(start + row * plane.rowStride + column * plane.pixelStride)
                }
            }
            val value = decodeWarehouseBarcode(pixels, width, height, mode) ?: return
            if (reading.compareAndSet(false, true)) runOnUiThread {
                if (!isFinishing && !isDestroyed) complete(value, true)
            }
        } catch (_: RuntimeException) {
            // An incomplete image is discarded; the next frame can still be scanned.
        } finally {
            image.close()
        }
    }

    private fun complete(raw: String, fromCamera: Boolean) {
        val location = intent.getBooleanExtra(LOCATION_MODE, false)
        val value = warehouseScannedValue(raw, location, InventoryManager.activeCompanyCode(this))
        if (value == null) {
            if (fromCamera) preview.postDelayed({ reading.set(false) }, 1000)
            Toast.makeText(this, "현재 회사와 작업에 맞는 코드를 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (intent.getBooleanExtra(RETURN_RESULT, false)) {
            setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_CODE, value))
        } else {
            startActivity(Intent(this, ScanResult::class.java).putExtra(RESULT_CODE, value))
        }
        finish()
    }

    override fun onDestroy() {
        analysis?.clearAnalyzer()
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val RETURN_RESULT = "RETURN_BARCODE"
        const val RESULT_CODE = "SCANNED_CODE"
        const val LOCATION_MODE = "SCAN_LOCATION"
        private val MODE_BUTTON_ID = android.view.View.generateViewId()
        private val modeNames = listOf("자동 인식", "바코드만", "QR만")
    }
}

internal fun decodeWarehouseBarcode(pixels: ByteArray, width: Int, height: Int, mode: Int): String? {
    val formats = when (mode) {
        1 -> listOf(BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
            BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.ITF, BarcodeFormat.CODABAR)
        2 -> listOf(BarcodeFormat.QR_CODE)
        else -> null
    }
    val hints = mutableMapOf<DecodeHintType, Any>(DecodeHintType.TRY_HARDER to true)
    if (formats != null) hints[DecodeHintType.POSSIBLE_FORMATS] = formats
    val reader = MultiFormatReader()
    fun read(data: ByteArray, w: Int, h: Int): String? = try {
        val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: ReaderException) { null } finally { reader.reset() }

    read(pixels, width, height)?.let { return it }
    val rotated = ByteArray(pixels.size)
    for (row in 0 until height) for (column in 0 until width) {
        rotated[column * height + height - 1 - row] = pixels[row * width + column]
    }
    return read(rotated, height, width)
}

internal fun warehouseScannedValue(raw: String, location: Boolean, company: String?): String? {
    val text = raw.trim()
    val value = if (text.startsWith("{")) {
        val structured = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (structured.optInt("ivenai") != 1 || company == null || structured.optString("companyCode") != company) return null
        if (location && structured.optString("kind") == "location") structured.optString("location")
        else if (!location && structured.optString("kind") == "product") structured.optString("itemCode")
        else return null
    } else text
    return value.takeIf { it.isNotBlank() && it.length <= (if (location) 160 else 120) &&
        it.none { char -> char.isISOControl() } && (location || '/' !in it) }
}
