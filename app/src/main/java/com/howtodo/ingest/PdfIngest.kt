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
     * 流式分页渲染器：一次只渲染一页、用完即弃，解析流水线攒满一批就转写，
     * 内存峰值从「全部页驻留」降到「一批驻留」。
     */
    class Pager internal constructor(private val renderer: PdfRenderer) : java.io.Closeable {
        val pageCount: Int get() = renderer.pageCount
        private var next = 0

        /** 渲染下一页；返回 null 表示已到末尾（坏页内部跳过，不中断） */
        fun renderNext(maxDim: Int = ImageIngest.PDF_MAX_DIM): ImageIngest.Prepared? {
            while (next < renderer.pageCount) {
                val i = next++
                val page = try {
                    renderer.openPage(i)
                } catch (_: Throwable) {
                    continue
                }
                var result: ImageIngest.Prepared? = null
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
                        result = ImageIngest.prepareBitmap(bmp, maxDim)
                    } catch (_: Throwable) {
                    } finally {
                        bmp.recycle()
                    }
                }
                if (result != null) return result
            }
            return null
        }

        override fun close() {
            try {
                renderer.close()
            } catch (_: Throwable) {
            }
        }
    }

    fun pager(file: File): Pager? = try {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        Pager(PdfRenderer(pfd))
    } catch (_: Throwable) {
        null
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
