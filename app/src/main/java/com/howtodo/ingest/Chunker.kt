package com.howtodo.ingest

/**
 * 切块器。
 *
 * 目标：块大小适中（便于检索命中且不浪费上下文），保留段落语义与页码，
 * 并记录在全文中的字符偏移，供「引用跳转 + 高亮」使用。
 */
object Chunker {

    data class Piece(
        val text: String,
        val pageNo: Int?,
        val section: String?,
        val charStart: Int,
        val charEnd: Int,
        val kind: String = "PARA"
    )

    private const val MAX_CHARS = 700
    private const val MIN_CHARS = 40

    /**
     * @param text 全文
     * @param pageMarkers 可选的页边界（PDF 抽取时按 "【第N页】" 之类标记），为空则整体按段落切
     */
    fun split(text: String, pageMarkers: List<Int>? = null): List<Piece> {
        val clean = text.replace("\r\n", "\n").replace('\u0000', ' ')
        if (clean.isBlank()) return emptyList()

        val out = ArrayList<Piece>()
        val paragraphs = splitParagraphs(clean)

        val buf = StringBuilder()
        var bufStart = 0
        var bufEnd = 0

        fun flush() {
            val body = buf.toString().trim()
            if (body.isNotEmpty()) {
                out.add(
                    Piece(
                        text = body,
                        pageNo = pageOf(bufStart, pageMarkers),
                        section = null,
                        charStart = bufStart,
                        charEnd = bufEnd,
                        kind = "PARA"
                    )
                )
            }
            buf.setLength(0)
        }

        for ((start, end, para) in paragraphs) {
            val p = para.trim()
            if (p.isEmpty()) continue
            if (buf.isEmpty()) {
                bufStart = start
            }
            if (buf.length + p.length > MAX_CHARS && buf.length >= MIN_CHARS) {
                // 此处不能动 bufEnd：它必须停在上一段的末尾，否则 charEnd 会把
                // 还没进块的这一段也算进去，引用跳转的高亮范围随之变大。
                flush()
                bufStart = start
            }
            if (p.length > MAX_CHARS * 2) {
                // 超长段落按句末切
                flush()
                var s = 0
                while (s < p.length) {
                    val e = minOf(s + MAX_CHARS, p.length)
                    out.add(
                        Piece(
                            text = p.substring(s, e),
                            pageNo = pageOf(start + s, pageMarkers),
                            section = null,
                            charStart = start + s,
                            charEnd = start + e,
                            kind = "PARA"
                        )
                    )
                    s = e
                }
                bufStart = end
                continue
            }
            buf.append(p).append('\n')
            bufEnd = end
        }
        flush()
        return out
    }

    /** 返回 (起始偏移, 结束偏移, 段落文本) */
    private fun splitParagraphs(text: String): List<Triple<Int, Int, String>> {
        val result = ArrayList<Triple<Int, Int, String>>()
        var i = 0
        var start = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                var j = i
                while (j < text.length && text[j] == '\n') j++
                val para = text.substring(start, i)
                if (para.isNotBlank()) result.add(Triple(start, i, para))
                start = j
                i = j
            } else {
                i++
            }
        }
        if (start < text.length) {
            val para = text.substring(start)
            if (para.isNotBlank()) result.add(Triple(start, text.length, para))
        }
        return result
    }

    private fun pageOf(charOffset: Int, markers: List<Int>?): Int? {
        if (markers.isNullOrEmpty()) return null
        var page = 1
        for ((i, m) in markers.withIndex()) {
            if (charOffset >= m) page = i + 1 else break
        }
        return page
    }
}
