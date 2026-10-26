package com.example.myapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory

// 안드로이드 Binder 1MB 용량 초과 (FAILED BINDER TRANSACTION) 방지용 싱글톤 이미지 홀더
object CapturedPhotoHolder {
    var photoBytes: ByteArray? = null

    fun getAndClear(): ByteArray? {
        val data = photoBytes
        photoBytes = null
        return data
    }

    fun getBitmap(): Bitmap? {
        val data = photoBytes ?: return null
        return try {
            BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (e: Exception) {
            null
        }
    }
}