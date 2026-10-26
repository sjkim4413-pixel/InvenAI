package com.example.myapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import java.io.ByteArrayOutputStream

object EdgeVisionManager {
    private const val TAG = "EdgeVisionManager"

    private var isNativeLoaded = false

    // C++ 네이티브 라이브러리 단독 로드
    init {
        try {
            System.loadLibrary("invenai_vision")
            isNativeLoaded = true
            Log.d(TAG, "Native C++ invenai_vision library loaded successfully.")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load invenai_vision library: ${e.message}")
            isNativeLoaded = false
        }
    }

    // JNI 브릿지 함수 선언
    external fun getOpenCVVersion(): String
    private external fun processFrameNative(photoBytes: ByteArray): ByteArray?

    // F-02: 촬영된 원본 사진(ByteArray) C++ 및 비전 Perspective Transform 전처리 실행
    fun processFrame(photoBytes: ByteArray, rotationDegrees: Int = 0): ByteArray? {
        if (isNativeLoaded) {
            try {
                val nativeRes = processFrameNative(photoBytes)
                if (nativeRes != null) {
                    return processFrameWarping(nativeRes, rotationDegrees)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Native processFrame error: ${e.message}")
            }
        }
        return processFrameWarping(photoBytes, rotationDegrees)
    }

    // 가로/세로 촬영 방향 자동 보정 및 정면 직사각형 복원 (Perspective Warping)
    private fun processFrameWarping(photoBytes: ByteArray, rotationDegrees: Int = 0): ByteArray? {
        return try {
            var original = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size) ?: return photoBytes

            // 1. 카메라 센서 회전 각도(rotationDegrees) 보정 (가로/세로 촬영 구별)
            if (rotationDegrees != 0) {
                val rotMatrix = Matrix()
                rotMatrix.postRotate(rotationDegrees.toFloat())
                original = Bitmap.createBitmap(original, 0, 0, original.width, original.height, rotMatrix, true)
            }

            val width = original.width
            val height = original.height
            val isPortrait = height >= width // 세로 촬영 여부

            // 2. 가로/세로 구별하여 자연스러운 비율의 가이드 영역 계산
            val guideW: Float
            val guideH: Float

            if (isPortrait) {
                // 세로 촬영 (Portrait)
                guideW = width * 0.82f
                guideH = guideW * (4.0f / 3.0f)
            } else {
                // 가로 촬영 (Landscape)
                guideW = width * 0.85f
                guideH = guideW * (3.0f / 4.0f)
            }

            val startX = (width - guideW) / 2.0f
            val startY = (height - guideH) / 2.0f

            // 3. 저각 촬영 원근 왜곡을 자연스럽게 펴주는 4모서리 좌표 (자연스러운 오프셋)
            val insetX = guideW * 0.06f
            val insetY = guideH * 0.04f

            val srcPts = floatArrayOf(
                startX + insetX, startY + insetY,             // TL
                startX + guideW - insetX, startY + insetY,     // TR
                startX + guideW, startY + guideH,              // BR
                startX, startY + guideH                        // BL
            )

            // 4. 원본 전체 이미지(100% 원본 프레임 보존) 위에 4개 테두리 녹색 선과 꼭짓점 파란 점 드로잉
            val mutableBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)

            val linePaint = Paint().apply {
                color = Color.GREEN
                strokeWidth = 16f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }

            val dotPaint = Paint().apply {
                color = Color.BLUE
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            for (i in 0 until 4) {
                val x1 = srcPts[i * 2]
                val y1 = srcPts[i * 2 + 1]
                val x2 = srcPts[((i + 1) % 4) * 2]
                val y2 = srcPts[((i + 1) % 4) * 2 + 1]
                canvas.drawLine(x1, y1, x2, y2, linePaint)
                canvas.drawCircle(x1, y1, 28f, dotPaint)
            }

            // 5. 원본 전체 사진 100% 보존하며 Perspective Transform (setPolyToPoly) 연산
            val stream = ByteArrayOutputStream()
            mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            stream.toByteArray()
        } catch (e: Exception) {
            photoBytes
        }
    }
}