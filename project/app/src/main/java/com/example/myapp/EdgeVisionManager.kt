package com.example.myapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import java.io.ByteArrayOutputStream

object EdgeVisionManager {
    private const val TAG = "EdgeVisionManager"

    private val isNativeLoaded = try {
        System.loadLibrary("invenai_vision")
        true
    } catch (error: LinkageError) {
        Log.e(TAG, "Failed to load the OpenCV preprocessor", error)
        false
    }

    external fun getOpenCVVersion(): String
    private external fun processFrameNative(photoBytes: ByteArray): ByteArray?

    fun processFrame(photoBytes: ByteArray, rotationDegrees: Int): ByteArray? {
        // A rejection from OpenCV must reach the camera screen.
        val processed = if (isNativeLoaded) processFrameNative(photoBytes) ?: return null else photoBytes
        if (rotationDegrees == 0) return processed

        val original = BitmapFactory.decodeByteArray(processed, 0, processed.size) ?: return null
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        return try {
            ByteArrayOutputStream().use { stream ->
                if (rotated.compress(Bitmap.CompressFormat.JPEG, 95, stream)) stream.toByteArray() else null
            }
        } finally {
            if (rotated !== original) rotated.recycle()
            original.recycle()
        }
    }
}
