
package com.example.myapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide

class AiAuditCamera : AppCompatActivity() {

	// 카메라 권한 요청
	private val cameraPermissionLauncher =
		registerForActivityResult(
			ActivityResultContracts.RequestPermission()
		) { isGranted ->

			if (isGranted) {
				startCamera()
			} else {
				Toast.makeText(
					this,
					"카메라 권한이 필요합니다.",
					Toast.LENGTH_SHORT
				).show()
			}
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		setContentView(R.layout.activity_ai_audit_camera)

		// 카메라 권한 확인
		if (
			ContextCompat.checkSelfPermission(
				this,
				Manifest.permission.CAMERA
			) == PackageManager.PERMISSION_GRANTED
		) {
			startCamera()
		} else {
			cameraPermissionLauncher.launch(
				Manifest.permission.CAMERA
			)
		}

		// 기존 화면 이미지 유지
		val images = listOf(
			R.id.ritt5ng5o198 to
					"9e3p4of1_expires_30_days.png",

			R.id.r15dwcp3t57c to
					"gbcu6atm_expires_30_days.png",

			R.id.re9v2i5j5okr to
					"4lciicxp_expires_30_days.png",

			R.id.rs7jq8zl0qw to
					"69d7qhmf_expires_30_days.png",

			R.id.rxsy19v2hpcm to
					"t0mm0pnj_expires_30_days.png",

			R.id.r9f1976gvrjv to
					"9x7sgbjh_expires_30_days.png",

			R.id.rtk0p0bn1qq to
					"3phvlt17_expires_30_days.png",

			R.id.ryfcmlkyd0x to
					"cp73up0q_expires_30_days.png",

			R.id.r16363f6acfj to
					"asrws35g_expires_30_days.png",

			R.id.r2jog2g9gibp to
					"h3ngit5y_expires_30_days.png",

			R.id.r9ig1u678w9v to
					"pajsw497_expires_30_days.png"
		)

		for ((viewId, fileName) in images) {
			val imageView =
				findViewById<android.widget.ImageView>(viewId)

			if (imageView != null) {
				Glide.with(this)
					.load(
						"https://storage.googleapis.com/" +
								"tagjs-prod.appspot.com/v1/DCxOgkdP9z/" +
								fileName
					)
					.into(imageView)
			}
		}

		// 기존 촬영 버튼 동작 유지
		val btnCapture: View? =
			findViewById(R.id.r2u5n27nrh39)

		btnCapture?.setOnClickListener {
			startActivity(
				Intent(
					this,
					AiAuditResults::class.java
				)
			)
			finish()
		}
	}

	// CameraX 후면 카메라 실행
	private fun startCamera() {

		val cameraProviderFuture =
			ProcessCameraProvider.getInstance(this)

		cameraProviderFuture.addListener({

			try {
				val cameraProvider =
					cameraProviderFuture.get()

				val preview =
					Preview.Builder().build()

				val previewView =
					findViewById<PreviewView>(
						R.id.f02CameraPreview
					)

				preview.setSurfaceProvider(
					previewView.surfaceProvider
				)

				val cameraSelector =
					CameraSelector.DEFAULT_BACK_CAMERA

				cameraProvider.unbindAll()

				cameraProvider.bindToLifecycle(
					this,
					cameraSelector,
					preview
				)

			} catch (e: Exception) {
				android.util.Log.e(
					"F02_CAMERA",
					"Camera start failed",
					e
				)

				Toast.makeText(
					this,
					"카메라 실행에 실패했습니다.",
					Toast.LENGTH_SHORT
				).show()
			}

		}, ContextCompat.getMainExecutor(this))
	}
}
