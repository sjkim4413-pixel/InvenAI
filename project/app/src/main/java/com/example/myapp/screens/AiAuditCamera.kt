package com.example.myapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import java.nio.ByteBuffer

class AiAuditCamera : AppCompatActivity() {

	private lateinit var viewFinder: PreviewView
	private lateinit var ivLowLightWarning: ImageView
	private var imageCapture: ImageCapture? = null

	private var orientationEventListener: android.view.OrientationEventListener? = null
	private var devicePhysicalRotation: Int = 0

	// 1. 카메라 권한 요청
	private val requestPermissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestPermission()
	) { isGranted ->
		if (isGranted) {
			startCamera()
		} else {
			Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
			finish()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_ai_audit_camera)

		// 가속도 센서를 통해 폰을 가로로 눕혔는지/세웠는지 물리적 방향 실시간 감지
		orientationEventListener = object : android.view.OrientationEventListener(this) {
			override fun onOrientationChanged(orientation: Int) {
				if (orientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return
				devicePhysicalRotation = when (orientation) {
					in 45..135 -> 270  // 가로로 눕힘
					in 135..225 -> 180 // 거꾸로
					in 225..315 -> 90  // 가로로 눕힘
					else -> 0          // 정세로
				}
			}
		}
		if (orientationEventListener?.canDetectOrientation() == true) {
			orientationEventListener?.enable()
		}

		viewFinder = findViewById(R.id.viewFinder)
		ivLowLightWarning = findViewById(R.id.ivLowLightWarning)

		// 닫기 버튼
		findViewById<ImageButton>(R.id.btnCloseCamera).setOnClickListener { finish() }

		// 셔터 버튼
		findViewById<ImageButton>(R.id.btnShutter).setOnClickListener {
			takePhotoAndProcess()
		}

		// 권한 확인 및 카메라 실행
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
			startCamera()
		} else {
			requestPermissionLauncher.launch(Manifest.permission.CAMERA)
		}
	}

	// 2. CameraX 라이프사이클 바인딩
	private fun startCamera() {
		val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

		cameraProviderFuture.addListener({
			val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

			val preview = Preview.Builder()
				.build()
				.also {
					it.setSurfaceProvider(viewFinder.surfaceProvider)
				}

			imageCapture = ImageCapture.Builder().build()

			// 추가 3: 실시간 프레임 분석 (ImageAnalysis) 유스케이스 설정
			val imageAnalysis = ImageAnalysis.Builder()
				.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
				.build()

			imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
				detectLowLight(imageProxy)
			}

			val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

			try {
				cameraProvider.unbindAll()
				// 추가 4: imageAnalysis를 라이프사이클에 함께 바인딩
				cameraProvider.bindToLifecycle(
					this, cameraSelector, preview, imageCapture, imageAnalysis
				)
			} catch(exc: Exception) {
				Log.e("AiAuditCamera", "Use case binding failed", exc)
			}

		}, ContextCompat.getMainExecutor(this))
	}

	// 추가 5: 실시간 조도(밝기) 분석 및 플래시 아이콘 점멸 처리 로직
	private fun detectLowLight(imageProxy: ImageProxy) {
		val buffer = imageProxy.planes[0].buffer
		val data = ByteArray(buffer.remaining())
		buffer.get(data)

		// Y(명도) 채널의 픽셀 평균값 계산
		var sum = 0L
		for (byte in data) {
			sum += (byte.toInt() and 0xFF)
		}
		val averageLuminance = sum.toDouble() / data.size

		// 평균 명도가 60 미만이면 어두운 환경으로 간주
		if (averageLuminance < 60.0) {
			if (ivLowLightWarning.visibility == View.GONE) {
				ivLowLightWarning.visibility = View.VISIBLE
				startBlinkingAnimation(ivLowLightWarning)
			}
		} else {
			if (ivLowLightWarning.visibility == View.VISIBLE) {
				ivLowLightWarning.visibility = View.GONE
				ivLowLightWarning.clearAnimation()
			}
		}
		imageProxy.close() // 메모리 누수 방지를 위해 분석 후 프레임 닫기 필수
	}

	// 추가 6: 경고 아이콘 깜빡임(Blink) 애니메이션
	private fun startBlinkingAnimation(view: View) {
		val blinkAnim = AlphaAnimation(1.0f, 0.0f).apply {
			duration = 500 // 0.5초 주기로 깜빡임
			interpolator = android.view.animation.LinearInterpolator()
			repeatCount = Animation.INFINITE
			repeatMode = Animation.REVERSE
		}
		view.startAnimation(blinkAnim)
	}

	override fun onDestroy() {
		super.onDestroy()
		orientationEventListener?.disable()
	}

	// 3. 1초 이내 처리 목표: 인메모리 캡처 및 Edge 연산 실행 (기존 로직 유지)
	private fun takePhotoAndProcess() {
		val imageCapture = imageCapture ?: return

		imageCapture.takePicture(
			ContextCompat.getMainExecutor(this),
			object : ImageCapture.OnImageCapturedCallback() {
				override fun onCaptureSuccess(image: ImageProxy) {
					val buffer:ByteBuffer = image.planes[0].buffer
					val bytes = ByteArray(buffer.remaining())
					buffer.get(bytes)
					val rotation = image.imageInfo.rotationDegrees
					val effectiveRotation = if (devicePhysicalRotation != 0) devicePhysicalRotation else rotation

					val processedBytes = EdgeVisionManager.processFrame(bytes, effectiveRotation)

					if (processedBytes != null) {
						CapturedPhotoHolder.photoBytes = processedBytes

						val intent = Intent(this@AiAuditCamera, AiAuditResults::class.java)
						startActivity(intent)
						finish()
					} else {
						Snackbar.make(
							findViewById(android.R.id.content),
							"🚨 흔들림이 감지되었습니다. 다시 촬영해주세요.",
							Snackbar.LENGTH_SHORT
						).show()
					}

					image.close()
				}

				override fun onError(exc: ImageCaptureException) {
					Log.e("AiAuditCamera", "사진 캡처 실패: ${exc.message}", exc)
				}
			}
		)
	}
}