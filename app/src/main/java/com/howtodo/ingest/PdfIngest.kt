package com.howtodo.ingest

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * PDF 处理。
 *
 * Android 没有内置的 PDF 文字层抽取，引入 pdfbox 又要多 10MB 且缓存里没有，
 * 因此统一走「渲染成图 -> 视觉模型识别」。这对扫描件/截图转的 PDF 反而更准。
 * 页数有上限（默认 15 页），避免一份 80 页的册子把费用拉爆。
 */
object PdfIngest {

    fun pageCount(file: File): Int = try {
        open(file)?.use { it.pageCount } ?: 0
    } catch (_: Throwable) {
        0
    }

    private fun open(file: File): PdfRenderer? = try {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd)
    } catch (_: Throwable) {
        null
    }

    /**
     * 渲染前 N 页，交给回调逐页处理。
     * @return 实际渲染的页数
     */
    fun renderPages(
        file: File,
        maxPages: Int,
        maxDim: Int = ImageIngest.PDF_MAX_DIM,
        onPage: (index: Int, prepared: ImageIngest.Prepared) -> Unit
    ): Int {
        val renderer = open(file) ?: return 0
        var rendered = 0
        try {
            val count = minOf(renderer.pageCount, maxPages)
            for (i in 0 until count) {
                val page = try {
                    renderer.openPage(i)
                } catch (_: Throwable) {
                    continue
                }
                page.use { p ->
                    val scale = minOf(
                        maxDim.toFloat() / p.width,
                        maxDim.toFloat() / p.height,
                        2.0f
                    ).coerceAtLeast(0.5f)
                    val w = (p.width * scale).toInt().coerceAtLeast(1)
                    val h = (p.height * scale).toInt().coerceAtLeast(1)
                    val bmp = try {
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    } catch (_: OutOfMemoryError) {
                        null
                    } ?: return@use
                    bmp.eraseColor(Color.WHITE)
                    try {
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ImageIngest.prepareBitmap(bmp, maxDim)?.let { prepared ->
                            onPage(i, prepared)
                            rendered++
                        }
                    } catch (_: Throwable) {
                    } finally {
                        bmp.recycle()
                    }
                }
            }
        } finally {
            try {
                renderer.close()
            } catch (_: Throwable) {
            }
        }
        return rendered
    }

    /** 仅渲染指定页，用于预览 */
    fun renderOne(file: File, index: Int, maxDim: Int = 1400): Bitmap? {
        val renderer = open(file) ?: return null
        return try {
            if (index < 0 || index >= renderer.pageCount) return null
            renderer.openPage(index).use { p ->
                val scale = minOf(maxDim.toFloat() / p.width, maxDim.toFloat() / p.height, 2.0f)
                val w = (p.width * scale).toInt().coerceAtLeast(1)
                val h = (p.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                renderer.close()
            } catch (_: Throwable) {
            }
        }
    }
}
