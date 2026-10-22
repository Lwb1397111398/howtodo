package com.howtodo.ingest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 图片预处理：降采样 + 纠正方向 + JPEG 压缩 + base64。
 *
 * 降采样很重要：原始手机照片 4000px 直接送视觉模型，token 费用和延迟都会翻好几倍，
 * 而识别通知截图所需的细节在 1600px 上完全够用。
 */
object ImageIngest {

    data class Prepared(val base64: String, val mime: String, val width: Int, val height: Int)

    const val MAX_DIM = 1600
    const val PDF_MAX_DIM = 1400
    const val JPEG_QUALITY = 82

    fun prepare(file: File, maxDim: Int = MAX_DIM): Prepared? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = try {
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null

        bmp = scaleDown(bmp, maxDim)
        bmp = rotateByExif(bmp, file)

        val bytes = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)
        val w = bmp.width
        val h = bmp.height
        bmp.recycle()
        val data = bytes.toByteArray()
        return Prepared(Base64.encodeToString(data, Base64.NO_WRAP), "image/jpeg", w, h)
    }

    fun prepareBitmap(bmp: Bitmap, maxDim: Int = PDF_MAX_DIM): Prepared? {
        val scaled = scaleDown(bmp, maxDim)
        val bytes = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)
        val w = scaled.width
        val h = scaled.height
        if (scaled !== bmp) scaled.recycle()
        return Prepared(Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP), "image/jpeg", w, h)
    }

    private fun sampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        var cw = w
        var ch = h
        while (cw / 2 >= maxDim && ch / 2 >= maxDim) {
            cw /= 2
            ch /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(bmp: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(bmp.width, bmp.height)
        if (maxSide <= maxDim) return bmp
        val ratio = maxDim.toFloat() / maxSide
        val w = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val h = (bmp.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    private fun rotateByExif(bmp: Bitmap, file: File): Bitmap = try {
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) bmp
        else {
            val m = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            rotated
        }
    } catch (_: Throwable) {
        bmp
    }
}
