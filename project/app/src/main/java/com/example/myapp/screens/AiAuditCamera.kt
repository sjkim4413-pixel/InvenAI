package com.example.myapp

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.firestore.ListenerRegistration
import java.io.InputStream
import java.util.concurrent.Executors

internal const val EXTRA_TEST_RACK_LABELS = "com.example.myapp.TEST_RACK_LABELS"
internal const val EXTRA_AUDIT_TEST_TITLE = "com.example.myapp.AUDIT_TEST_TITLE"
internal const val EXTRA_AUDIT_TEST_MODE = "com.example.myapp.AUDIT_TEST_MODE"

class AiAuditCamera : AppCompatActivity() {

	private lateinit var viewFinder: PreviewView
	private lateinit var ivLowLightWarning: ImageView
	private var imageCapture: ImageCapture? = null
	private var imageAnalysis: ImageAnalysis? = null
	private val cameraExecutor = Executors.newSingleThreadExecutor()
	private var processingPhoto = false
	private var previousLowLight = false
	private var warehouseCode: String? = null
	private var warehouseReady = false
	private var inventoryCompany: String? = null
	private var inventoryListener: ListenerRegistration? = null
	private var testImages = emptyList<AuditTestImage>()

	private var orientationEventListener: android.view.OrientationEventListener? = null
	private var captureRotation = Surface.ROTATION_0

	private val requestPermissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestPermission()
	) { isGranted ->
		if (isGranted) {
			startCamera()
		} else {
			Toast.makeText(this, "갤러리에서 사진을 선택할 수 있습니다.", Toast.LENGTH_SHORT).show()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_ai_audit_camera)
		warehouseCode = intent.getStringExtra("WAREHOUSE_CODE")?.takeIf(String::isNotBlank)
		if (warehouseCode == null) {
			startActivity(Intent(this, AuditStart::class.java))
			finish()
			return
		}
		testImages = runCatching { loadAuditTestImages(this) }.getOrDefault(emptyList())
		findViewById<TextView>(R.id.auditCameraWarehouse).apply {
			text = "실사 창고 · $warehouseCode\n창고 정보를 확인하고 있습니다."
			setOnClickListener {
				startActivity(Intent(this@AiAuditCamera, AuditStart::class.java).putExtra("WAREHOUSE_CODE", warehouseCode))
				finish()
			}
		}


		orientationEventListener = object : android.view.OrientationEventListener(this) {
			override fun onOrientationChanged(orientation: Int) {
				if (orientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return
				val rotation = when (orientation) {
					in 45..134 -> Surface.ROTATION_270
					in 135..224 -> Surface.ROTATION_180
					in 225..314 -> Surface.ROTATION_90
					else -> Surface.ROTATION_0
				}
				if (rotation != captureRotation) {
					captureRotation = rotation
					imageCapture?.targetRotation = rotation
				}
			}
		}
		if (orientationEventListener?.canDetectOrientation() == true) {
			orientationEventListener?.enable()
		}

		viewFinder = findViewById(R.id.viewFinder)
		ivLowLightWarning = findViewById(R.id.ivLowLightWarning)

		findViewById<ImageButton>(R.id.btnCloseCamera).setOnClickListener { finish() }

		findViewById<ImageButton>(R.id.btnShutter).setOnClickListener {
			takePhotoAndProcess()
		}
		updatePhotoControls()

		findViewById<View>(R.id.btnGallery)?.setOnClickListener {
			if (requireWarehouse()) selectPhoto.launch(arrayOf("image/jpeg", "image/png"))
		}

		findViewById<View>(R.id.btnSelectTestImage)?.setOnClickListener { showTestImagePicker() }

		if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
			startCamera()
		} else {
			requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
		}
	}

	override fun onStart() {
		super.onStart()
		if (isFinishing) return
		val company = InventoryManager.activeCompanyCode(this)
		if (company != inventoryCompany) warehouseReady = false
		inventoryCompany = company
		inventoryListener = InventoryManager.listenToRealtimeInventory(this, onError = {
			warehouseReady = false
			findViewById<TextView>(R.id.auditCameraWarehouse).text = "$it\n터치하여 창고 다시 선택"
			updatePhotoControls()
		}) { items ->
			warehouseReady = warehouseCode in auditWarehouseChoices(items, testImages)
			findViewById<TextView>(R.id.auditCameraWarehouse).text = if (warehouseReady)
				"실사 창고 · $warehouseCode\n터치하여 창고 변경" else
				"선택한 창고의 실사 재고를 확인할 수 없습니다.\n터치하여 창고 다시 선택"
			updatePhotoControls()
		}
	}

	override fun onStop() {
		inventoryListener?.remove()
		inventoryListener = null
		super.onStop()
	}

	private fun requireWarehouse(): Boolean {
		if (warehouseReady && warehouseCode != null && inventoryCompany != null &&
			InventoryManager.activeCompanyCode(this) == inventoryCompany) return true
		Toast.makeText(this, "실사할 창고를 선택하고 창고 정보를 확인해 주세요.", Toast.LENGTH_SHORT).show()
		return false
	}

	private fun showTestImagePicker() {
		if (!requireWarehouse()) return
		val directory = "audit_test_images"
		val images = auditImagesForWarehouse(testImages, warehouseCode)
		if (images.isEmpty()) {
			Toast.makeText(this, "이 창고에 연결된 테스트 이미지가 없습니다. 촬영하거나 앨범 사진을 선택해 주세요.", Toast.LENGTH_LONG).show()
			return
		}
		val title = if (images.any { it.group == "paired" }) "정상·오적재 테스트 ${images.size}장" else "테스트 이미지 선택"
		WarehouseMenus.show(this, title, images.map { it.title }) { index ->
			val selected = images[index]
			loadSelectedPhoto(selected.testRackLabels, selected.warehouseCode, selected.title, selected.mode) {
				assets.open("$directory/${selected.file}")
			}
		}
	}

	private val selectPhoto = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) loadSelectedPhoto {
			contentResolver.openInputStream(uri) ?: error("파일을 열 수 없습니다.")
		}
	}

	private fun loadSelectedPhoto(testRackLabels: Boolean = false, testWarehouse: String? = null,
	                              testTitle: String? = null, testMode: String? = null, openStream: () -> InputStream) {
		if (processingPhoto || !requireWarehouse()) return
		processingPhoto = true
		updatePhotoControls()
		cameraExecutor.execute {
			val result = runCatching {
				val maxBytes = 16 * 1024 * 1024
				openStream().use { input ->
					val output = java.io.ByteArrayOutputStream()
					val buffer = ByteArray(64 * 1024)
					var read = input.read(buffer)
					while (read != -1) {
						require(output.size() + read <= maxBytes) { "16MB 이하의 사진을 선택해주세요." }
						output.write(buffer, 0, read)
						read = input.read(buffer)
					}
					require(output.size() > 0) { "비어 있는 사진 파일입니다." }
					output.toByteArray()
				}
			}
			runOnUiThread {
				if (isFinishing || isDestroyed) return@runOnUiThread
				processingPhoto = false
				updatePhotoControls()
				result.fold(
					onSuccess = { showAuditResults(it, testRackLabels, testWarehouse, testTitle, testMode) },
					onFailure = {
						Toast.makeText(this, it.message ?: "사진을 읽을 수 없습니다.", Toast.LENGTH_SHORT).show()
					}
				)
			}
		}
	}

	private fun showAuditResults(bytes: ByteArray, testRackLabels: Boolean = false,
	                             testWarehouse: String? = null, testTitle: String? = null, testMode: String? = null) {
		if (!requireWarehouse()) return
		val problem = auditPhotoWarehouseProblem(warehouseCode, if (testRackLabels) "test_rack_labels" else testMode, testWarehouse)
		if (problem != null) {
			Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
			return
		}
		CapturedPhotoHolder.photoBytes = bytes
		startActivity(Intent(this, PhotoConfirmation::class.java).apply {
			putExtra("WAREHOUSE_CODE", warehouseCode)
			putExtra(EXTRA_AUDIT_TEST_WAREHOUSE, testWarehouse)
			putExtra(EXTRA_TEST_RACK_LABELS, testRackLabels)
			putExtra(EXTRA_AUDIT_TEST_TITLE, testTitle)
			putExtra(EXTRA_AUDIT_TEST_MODE, testMode)
		})
		finish()
	}

	private fun startCamera() {
		val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

		cameraProviderFuture.addListener({
			if (isFinishing || isDestroyed) return@addListener
			val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

			val preview = Preview.Builder()
				.build()
				.also {
					it.setSurfaceProvider(viewFinder.surfaceProvider)
				}

			val capture = ImageCapture.Builder().setTargetRotation(captureRotation).build()

			val analysis = ImageAnalysis.Builder()
				.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
				.build()

			analysis.setAnalyzer(cameraExecutor) { imageProxy ->
				detectLowLight(imageProxy)
			}

			val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

			try {
				cameraProvider.unbindAll()
				cameraProvider.bindToLifecycle(
					this, cameraSelector, preview, capture, analysis
				)
				imageCapture = capture
				imageAnalysis = analysis
				updatePhotoControls()
			} catch(exc: Exception) {
				analysis.clearAnalyzer()
				Log.e("AiAuditCamera", "Use case binding failed", exc)
			}

		}, ContextCompat.getMainExecutor(this))
	}

	private fun detectLowLight(imageProxy: ImageProxy) {
		val lowLight = try {
			val plane = imageProxy.planes[0]
			val buffer = plane.buffer
			val start = buffer.position()
			var sum = 0L
			var samples = 0
			for (y in 0 until imageProxy.height step 16) {
				for (x in 0 until imageProxy.width step 16) {
					val index = start + y * plane.rowStride + x * plane.pixelStride
					if (index < buffer.limit()) {
						sum += buffer.get(index).toInt() and 0xFF
						samples++
					}
				}
			}
			samples > 0 && sum < 60L * samples
		} finally {
			imageProxy.close()
		}
		if (lowLight == previousLowLight) return
		previousLowLight = lowLight
		runOnUiThread {
			if (isFinishing || isDestroyed) return@runOnUiThread
			if (lowLight) {
				ivLowLightWarning.visibility = View.VISIBLE
				startBlinkingAnimation(ivLowLightWarning)
			} else {
				ivLowLightWarning.visibility = View.GONE
				ivLowLightWarning.clearAnimation()
			}
		}
	}

	private fun startBlinkingAnimation(view: View) {
		val blinkAnim = AlphaAnimation(1.0f, 0.0f).apply {
			duration = 500
			interpolator = android.view.animation.LinearInterpolator()
			repeatCount = Animation.INFINITE
			repeatMode = Animation.REVERSE
		}
		view.startAnimation(blinkAnim)
	}

	override fun onDestroy() {
		orientationEventListener?.disable()
		imageAnalysis?.clearAnalyzer()
		cameraExecutor.shutdown()
		super.onDestroy()
	}

	private fun updatePhotoControls() {
		val ready = warehouseReady && inventoryCompany != null && InventoryManager.activeCompanyCode(this) == inventoryCompany
		findViewById<View>(R.id.btnShutter).isEnabled = ready && imageCapture != null && !processingPhoto
		findViewById<View>(R.id.btnGallery).isEnabled = ready && !processingPhoto
		findViewById<View>(R.id.btnSelectTestImage)?.apply {
			isEnabled = ready && !processingPhoto
			visibility = if (auditImagesForWarehouse(testImages, warehouseCode).isEmpty()) View.GONE else View.VISIBLE
		}
	}

	private fun captureFailed(message: String) {
		processingPhoto = false
		updatePhotoControls()
		Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
	}

	private fun takePhotoAndProcess() {
		val imageCapture = imageCapture ?: return
		if (processingPhoto || !requireWarehouse()) return
		processingPhoto = true
		updatePhotoControls()

		imageCapture.takePicture(
			cameraExecutor,
			object : ImageCapture.OnImageCapturedCallback() {
				override fun onCaptureSuccess(image: ImageProxy) {
					val rotation = image.imageInfo.rotationDegrees
					val processed = runCatching {
						val bytes = try {
							val buffer = image.planes[0].buffer
							ByteArray(buffer.remaining()).also { buffer.get(it) }
						} finally {
							image.close()
						}
						EdgeVisionManager.processFrame(bytes, rotation)
					}
					runOnUiThread {
						if (isFinishing || isDestroyed) return@runOnUiThread
						processed.fold(
							onSuccess = { bytes ->
								if (bytes != null) showAuditResults(bytes)
								else captureFailed("사진의 선명도를 확인하고 다시 촬영해주세요.")
							},
							onFailure = { captureFailed("사진 처리에 실패했습니다. 다시 촬영해주세요.") }
						)
					}
				}

				override fun onError(exc: ImageCaptureException) {
					Log.e("AiAuditCamera", "사진 캡처 실패: ${exc.message}", exc)
					runOnUiThread {
						if (!isFinishing && !isDestroyed) captureFailed("촬영에 실패했습니다. 다시 시도해주세요.")
					}
				}
			}
		)
	}
}
