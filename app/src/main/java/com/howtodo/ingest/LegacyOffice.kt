package com.howtodo.ingest

import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer

/**
 * Office 97-2003 二进制格式（.doc / .xls / .ppt）正文提取。
 *
 * 全部基于 [CfbReader] 读出来的流自己解析，不依赖 Apache POI：
 *  - .doc：FIB + 片段表（CLX），拿到的才是正文，而不是格式化垃圾
 *  - .xls：BIFF8 记录流，共享字符串表（含 CONTINUE 续记录）还原中文
 *  - .ppt：记录树里递归找 TextCharsAtom / TextBytesAtom
 *
 * 设计原则：宁可少提取，也不吐出乱码。任何一步解析失败都抛异常，
 * 由上层把「这个文件读不出文字」明确告诉用户，而不是入库一堆垃圾片段。
 */
object LegacyOffice {

    // ------------------------------------------------------------------ Word

    fun doc(file: File): String {
        val cfb = CfbReader.open(file)
            ?: throw IllegalStateException("这个 Word 文件不是有效的 97-2003 格式，可能已损坏")
        return cfb.use { doc(it) }
    }

    fun doc(c: CfbReader): String {
        if (c.hasStream("EncryptedPackage")) {
            throw IllegalStateException("这个 Word 文档设了密码，请先取消密码再导入")
        }
        val wd = c.stream("WordDocument")
            ?: throw IllegalStateException("这个文件不是 Word 文档（缺少正文流）")
        val nFib = CfbReader.u16(wd, 2)
        val flags = CfbReader.u16(wd, 0x0A)
        val table = if (flags and 0x0200 != 0) c.stream("1Table") else c.stream("0Table")
        val cp = c.codepage()

        val sb = StringBuilder()
        // nFib >= 193 才是 Word 97 及以后（有标准片段表）；更老的版本走整块读取
        if (table != null && nFib >= 193) docPieces(wd, table, cp, sb)
        if (sb.isBlank()) docRawFallback(wd, cp, sb)
        val text = WordText.clean(sb.toString())
        if (text.isBlank()) throw IllegalStateException("没有从 Word 文档里读到文字")
        return text
    }

    /** 片段表：Word 97+ 的正文由若干片段拼成，每段可能是 1 字节（ANSI）或 2 字节（UTF-16） */
    private fun docPieces(wd: ByteArray, tbl: ByteArray, cp: Int, sb: StringBuilder) {
        val fcClx = CfbReader.u32(wd, 0x01A2)
        val lcbClx = CfbReader.u32(wd, 0x01A6)
        if (lcbClx <= 0 || fcClx <= 0 || fcClx + lcbClx > tbl.size) return

        var i = fcClx.toInt()
        val end = (fcClx + lcbClx).toInt()
        while (i + 5 < end) {
            when (tbl[i].toInt() and 0xFF) {
                0x01 -> { // Prc：属性修改，跳过
                    val cb = CfbReader.u16(tbl, i + 1)
                    i += 3 + cb
                }

                0x02 -> { // Pcdt：真正的片段表
                    val len = CfbReader.u32(tbl, i + 1).toInt()
                    val base = i + 5
                    if (len < 4 || base + len > end) return
                    val n = (len - 4) / 12
                    if (n <= 0) return
                    val cpBase = base
                    val pcdBase = base + (n + 1) * 4
                    for (k in 0 until n) {
                        val from = CfbReader.u32(tbl, cpBase + k * 4)
                        val to = CfbReader.u32(tbl, cpBase + (k + 1) * 4)
                        val count = to - from
                        if (count <= 0 || count > MAX_TEXT) continue
                        val fcRaw = CfbReader.u32(tbl, pcdBase + k * 8 + 2)
                        val compressed = (fcRaw and 0x40000000L) != 0L
                        val off = if (compressed) (fcRaw and 0x3FFFFFFFL) / 2 else fcRaw and 0x3FFFFFFFL
                        val bytes = if (compressed) count else count * 2
                        if (off < 0 || off + bytes > wd.size) continue
                        if (compressed) {
                            sb.append(AnsiText.decode(wd.copyOfRange(off.toInt(), (off + bytes).toInt()), cp))
                        } else {
                            sb.append(String(wd, off.toInt(), bytes.toInt(), Charsets.UTF_16LE))
                        }
                    }
                    return
                }

                else -> return
            }
        }
    }

    /** Word 6/95 或片段表缺失时，退化为整块读取（fcMin..fcMac） */
    private fun docRawFallback(wd: ByteArray, cp: Int, sb: StringBuilder) {
        val fcMin = CfbReader.u32(wd, 0x18)
        val fcMac = CfbReader.u32(wd, 0x1C)
        if (fcMin <= 0 || fcMac <= fcMin || fcMac > wd.size) return
        val raw = wd.copyOfRange(fcMin.toInt(), fcMac.toInt())
        if (raw.size < 4) return
        // 老文档没有片段表，只能靠字节分布判断是不是 UTF-16
        var zeroHigh = 0
        var checked = 0
        var i = 1
        while (i < raw.size && checked < 4000) {
            if (raw[i].toInt() == 0) zeroHigh++
            checked++
            i += 2
        }
        val unicode = zeroHigh * 2 > checked
        sb.append(
            if (unicode) String(raw, 0, raw.size - raw.size % 2, Charsets.UTF_16LE)
            else AnsiText.decode(raw, cp)
        )
    }

    // ------------------------------------------------------------------ Excel

    fun xls(file: File): String {
        val cfb = CfbReader.open(file)
            ?: throw IllegalStateException("这个 Excel 文件不是有效的 97-2003 格式，可能已损坏")
        return cfb.use { xls(it) }
    }

    fun xls(c: CfbReader): String {
        if (c.hasStream("EncryptedPackage")) {
            throw IllegalStateException("这个 Excel 设了密码，请先取消密码再导入")
        }
        val stream = c.stream("Workbook") ?: c.stream("Book")
            ?: throw IllegalStateException("这个文件不是 Excel 表格（缺少工作簿流）")
        val text = XlsReader(c.codepage()).read(stream)
        if (text.isBlank()) throw IllegalStateException("没有从 Excel 表格里读到文字")
        return text
    }

    private class XlsReader(private val codepage: Int) {

        private data class Rec(val id: Int, val off: Int, val body: ByteArray)

        private var biff8 = true

        fun read(stream: ByteArray): String {
            val recs = ArrayList<Rec>(512)
            var i = 0
            while (i + 4 <= stream.size) {
                val id = CfbReader.u16(stream, i)
                var len = CfbReader.u16(stream, i + 2)
                if (i + 4 + len > stream.size) len = stream.size - i - 4
                recs.add(Rec(id, i, stream.copyOfRange(i + 4, i + 4 + len)))
                i += 4 + len
            }
            if (recs.isEmpty()) return ""

            // 版本：0x0600 为 BIFF8，字符串结构不同
            recs.firstOrNull { it.id == 0x0809 }?.let {
                if (it.body.size >= 2) biff8 = CfbReader.u16(it.body, 0) >= 0x0600
            }

            // 工作表名与它在流里的起始偏移
            val sheetNames = LinkedHashMap<Int, String>()
            recs.filter { it.id == 0x0085 }.forEach { r ->
                val lb = CfbReader.u32(r.body, 0).toInt()
                val name = boundsheetName(r.body)
                if (name.isNotBlank()) sheetNames[lb] = name
            }

            val shared = ArrayList<String>()
            val sstIndex = recs.indexOfFirst { it.id == 0x00FC }
            if (sstIndex >= 0) {
                val segs = ArrayList<ByteArray>()
                segs.add(recs[sstIndex].body)
                var j = sstIndex + 1
                while (j < recs.size && recs[j].id == 0x003C) {
                    segs.add(recs[j].body)
                    j++
                }
                shared.addAll(parseSst(segs))
            }

            // 按工作表归集单元格：row -> (col -> text)
            val sheets = LinkedHashMap<String, HashMap<Int, HashMap<Int, String>>>()
            var current: HashMap<Int, HashMap<Int, String>>? = null
            var autoIndex = 0
            var pendingFormula: IntArray? = null

            recs.forEach { r ->
                if (r.id == 0x0809) {
                    val dt = if (r.body.size >= 4) CfbReader.u16(r.body, 2) else 0
                    if (dt == 0x0010 || dt == 0x0020) {
                        val name = sheetNames[r.off]
                            ?: if (dt == 0x0010) "Sheet${++autoIndex}" else null
                        current = name?.let { sheets.getOrPut(it) { HashMap() } }
                    }
                    return@forEach
                }
                val sheet = current ?: return@forEach
                when (r.id) {
                    0x00FD -> {
                        val row = CfbReader.u16(r.body, 0)
                        val col = CfbReader.u16(r.body, 2)
                        val isst = CfbReader.u32(r.body, 6).toInt()
                        val v = shared.getOrNull(isst)
                        if (!v.isNullOrBlank()) put(sheet, row, col, v)
                    }

                    0x0203 -> put(
                        sheet, CfbReader.u16(r.body, 0), CfbReader.u16(r.body, 2),
                        num(f64(r.body, 6))
                    )

                    0x027E -> put(
                        sheet, CfbReader.u16(r.body, 0), CfbReader.u16(r.body, 2),
                        num(rk(CfbReader.u32(r.body, 6)))
                    )

                    0x00BD -> {
                        val row = CfbReader.u16(r.body, 0)
                        val colFirst = CfbReader.u16(r.body, 2)
                        val n = (r.body.size - 6) / 6
                        for (k in 0 until n) {
                            val rkAt = 4 + k * 6 + 2
                            if (rkAt + 4 > r.body.size) break
                            put(sheet, row, colFirst + k, num(rk(CfbReader.u32(r.body, rkAt))))
                        }
                    }

                    0x0006 -> {
                        val row = CfbReader.u16(r.body, 0)
                        val col = CfbReader.u16(r.body, 2)
                        val special = r.body.size >= 14 &&
                            (r.body[12].toInt() and 0xFF) == 0xFF &&
                            (r.body[13].toInt() and 0xFF) == 0xFF
                        if (!special) {
                            put(sheet, row, col, num(f64(r.body, 6)))
                        } else if ((r.body[6].toInt() and 0xFF) == 0) {
                            pendingFormula = intArrayOf(row, col)
                        } else if ((r.body[6].toInt() and 0xFF) == 1) {
                            put(sheet, row, col, if ((r.body[8].toInt() and 0xFF) != 0) "TRUE" else "FALSE")
                        }
                    }

                    0x0207 -> {
                        val p = pendingFormula
                        if (p != null) {
                            put(sheet, p[0], p[1], readString(r.body, 0))
                            pendingFormula = null
                        }
                    }

                    0x0204 -> put(
                        sheet, CfbReader.u16(r.body, 0), CfbReader.u16(r.body, 2),
                        readString(r.body, 6)
                    )
                }
            }

            val sb = StringBuilder()
            for ((name, rows) in sheets) {
                if (rows.isEmpty()) continue
                sb.append("【").append(name).append("】\n")
                for (row in rows.keys.sorted()) {
                    val cells = rows[row] ?: continue
                    val line = ArrayList<String>(cells.size)
                    for (col in cells.keys.sorted()) {
                        val v = cells[col]?.trim().orEmpty()
                        if (v.isNotEmpty()) line.add(v)
                    }
                    if (line.isNotEmpty()) sb.append(line.joinToString(" | ")).append('\n')
                }
                sb.append('\n')
            }
            return sb.toString()
        }

        private fun put(sheet: HashMap<Int, HashMap<Int, String>>, row: Int, col: Int, v: String) {
            if (v.isBlank()) return
            sheet.getOrPut(row) { HashMap() }[col] = v
        }

        private fun boundsheetName(body: ByteArray): String {
            if (body.size < 8) return ""
            val cch = body[6].toInt() and 0xFF
            val grbit = body[7].toInt() and 0xFF
            return try {
                if (grbit and 0x01 != 0) {
                    String(body, 8, minOf(cch * 2, body.size - 8), Charsets.UTF_16LE)
                } else {
                    String(body, 8, minOf(cch, body.size - 8), Charsets.ISO_8859_1)
                }
            } catch (_: Throwable) {
                ""
            }
        }

        private fun readString(body: ByteArray, at: Int): String {
            return try {
                if (!biff8) {
                    val cch = body[at].toInt() and 0xFF
                    AnsiText.decode(body.copyOfRange(at + 1, minOf(at + 1 + cch, body.size)), codepage)
                } else {
                    val cch = CfbReader.u16(body, at)
                    val grbit = body[at + 2].toInt() and 0xFF
                    val charsAt = at + 3
                    if (grbit and 0x01 != 0) {
                        String(body, charsAt, minOf(cch * 2, body.size - charsAt), Charsets.UTF_16LE)
                    } else {
                        String(body, charsAt, minOf(cch, body.size - charsAt), Charsets.ISO_8859_1)
                    }
                }
            } catch (_: Throwable) {
                ""
            }
        }

        private fun f64(b: ByteArray, o: Int): Double {
            if (o + 8 > b.size) return 0.0
            var bits = 0L
            for (i in 7 downTo 0) bits = (bits shl 8) or (b[o + i].toLong() and 0xFF)
            return java.lang.Double.longBitsToDouble(bits)
        }

        private fun rk(v: Long): Double {
            val asInt = (v and 0x02L) != 0L
            var d = if (asInt) {
                (v shr 2).toDouble()
            } else {
                java.lang.Double.longBitsToDouble((v and 0xFFFFFFFCL) shl 32)
            }
            if ((v and 0x01L) != 0L) d /= 100.0
            return d
        }

        private fun num(v: Double): String {
            if (v.isNaN() || v.isInfinite()) return ""
            if (v == Math.floor(v) && Math.abs(v) < 1e15) return v.toLong().toString()
            return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
        }

        /**
         * 共享字符串表。字符串可以跨 CONTINUE 记录，续记录的**第一个字节**是新的编码标志，
         * 不处理这个字节，中文表格会从第一个长字符串开始整体错位成乱码。
         */
        private fun parseSst(segs: List<ByteArray>): List<String> {
            val cur = SegCursor(segs)
            cur.u32()
            val unique = cur.u32().toInt().coerceIn(0, 1 shl 20)
            val out = ArrayList<String>(minOf(unique, 8192))
            var i = 0
            while (i < unique) {
                if (!cur.hasMore()) break
                val cch = cur.u16()
                val grbit = cur.u8()
                val high = grbit and 0x01 != 0
                val ext = grbit and 0x04 != 0
                val rich = grbit and 0x08 != 0
                var runs = 0
                var cbExt = 0L
                if (rich) runs = cur.u16()
                if (ext) cbExt = cur.u32()
                val sb = StringBuilder(cch.coerceAtMost(4096))
                cur.chars(cch, high, sb)
                cur.skip(runs.toLong() * 4 + cbExt)
                out.add(sb.toString())
                i++
            }
            return out
        }

        private class SegCursor(private val segs: List<ByteArray>) {
            private var seg = 0
            private var pos = 0

            private fun settle() {
                while (seg < segs.size && pos >= segs[seg].size) {
                    seg++
                    pos = 0
                }
            }

            fun hasMore(): Boolean {
                settle()
                return seg < segs.size
            }

            fun u8(): Int {
                settle()
                if (seg >= segs.size) return 0
                return segs[seg][pos++].toInt() and 0xFF
            }

            fun u16(): Int = u8() or (u8() shl 8)

            fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)

            fun chars(count: Int, highByte: Boolean, out: StringBuilder) {
                var left = count
                var high = highByte
                while (left > 0) {
                    settle()
                    if (seg >= segs.size) return
                    val cur = segs[seg]
                    val unit = if (high) 2 else 1
                    val avail = (cur.size - pos) / unit
                    if (avail <= 0) {
                        // 换到续记录：首字节是编码标志
                        seg++
                        pos = 0
                        if (seg >= segs.size) return
                        if (segs[seg].isNotEmpty()) high = (segs[seg][pos++].toInt() and 0x01) != 0
                        continue
                    }
                    val take = minOf(left, avail)
                    if (high) {
                        out.append(String(cur, pos, take * 2, Charsets.UTF_16LE))
                    } else {
                        var k = 0
                        while (k < take) {
                            out.append((cur[pos + k].toInt() and 0xFF).toChar())
                            k++
                        }
                    }
                    pos += take * unit
                    left -= take
                }
            }

            fun skip(count: Long) {
                var left = count
                while (left > 0) {
                    settle()
                    if (seg >= segs.size) return
                    val avail = segs[seg].size - pos
                    if (avail <= 0) {
                        seg++
                        pos = 0
                        continue
                    }
                    val take = minOf(left, avail.toLong()).toInt()
                    pos += take
                    left -= take
                }
            }
        }
    }

    // ------------------------------------------------------------------ 演示文稿

    fun ppt(file: File): String {
        val cfb = CfbReader.open(file)
            ?: throw IllegalStateException("这个 PPT 文件不是有效的 97-2003 格式，可能已损坏")
        return cfb.use { ppt(it) }
    }

    fun ppt(c: CfbReader): String {
        if (c.hasStream("EncryptedPackage")) {
            throw IllegalStateException("这个 PPT 设了密码，请先取消密码再导入")
        }
        val stream = c.stream("PowerPoint Document")
            ?: throw IllegalStateException("这个文件不是 PPT（缺少演示文稿流）")
        val slides = pptSlides(stream, c.codepage())
        val sb = StringBuilder()
        slides.forEachIndexed { i, body ->
            val t = body.trim()
            if (t.isEmpty()) return@forEachIndexed
            sb.append("【第").append(i + 1).append("页】\n")
            sb.append(t.replace("\r", "\n")).append("\n\n")
        }
        val text = sb.toString().trim()
        if (text.isBlank()) throw IllegalStateException("没有从 PPT 里读到文字（可能整页都是图片）")
        return text
    }

    /** 按「页」返回正文，单独拆出来便于用构造的字节流做回归测试 */
    internal fun pptSlides(stream: ByteArray, codepage: Int): List<String> {
        val charset = AnsiText.charsetOf(codepage)
        val slides = ArrayList<String>()
        walkPpt(stream, 0, stream.size, slides, ArrayList(), charset, 0)
        if (slides.isEmpty()) collectPptAtoms(stream, charset, slides)
        return slides
    }

    private fun walkPpt(
        buf: ByteArray,
        start: Int,
        end: Int,
        slides: MutableList<String>,
        acc: MutableList<String>,
        charset: Charset?,
        depth: Int
    ) {
        if (depth > 40) return
        var i = start
        while (i + 8 <= end) {
            val ver = CfbReader.u16(buf, i) and 0x0F
            val type = CfbReader.u16(buf, i + 2)
            val len = CfbReader.u32(buf, i + 4)
            val body = i + 8
            if (len > (end - body).toLong()) return
            val next = body + len.toInt()
            when {
                ver == 0x0F && type == 0x03EE -> { // Slide 容器
                    val sub = ArrayList<String>()
                    walkPpt(buf, body, next, slides, sub, charset, depth + 1)
                    if (sub.isNotEmpty()) slides.add(sub.joinToString("\n"))
                }

                ver == 0x0F -> walkPpt(buf, body, next, slides, acc, charset, depth + 1)
                type == 0x0FA0 -> if (len >= 2) {
                    acc.add(String(buf, body, len.toInt() - len.toInt() % 2, Charsets.UTF_16LE))
                }

                type == 0x0FA8 -> acc.add(String(buf, body, len.toInt(), charset ?: Charsets.ISO_8859_1))
                type == 0x0FBA -> acc.add(String(buf, body, len.toInt(), charset ?: Charsets.ISO_8859_1))
            }
            i = next
        }
    }

    /** 结构不符合预期时的兜底：顺序扫所有文本原子 */
    private fun collectPptAtoms(buf: ByteArray, charset: Charset?, out: MutableList<String>) {
        var i = 0
        val sb = StringBuilder()
        while (i + 8 <= buf.size) {
            val type = CfbReader.u16(buf, i + 2)
            val len = CfbReader.u32(buf, i + 4).toInt()
            if (len < 0 || i + 8 + len > buf.size) break
            when (type) {
                0x0FA0 -> if (len >= 2) sb.append(
                    String(buf, i + 8, len - len % 2, Charsets.UTF_16LE)
                ).append('\n')

                0x0FA8 -> if (len > 0) sb.append(
                    String(buf, i + 8, len, charset ?: Charsets.ISO_8859_1)
                ).append('\n')
            }
            i += 8 + len
        }
        if (sb.isNotBlank()) out.add(sb.toString())
    }

    private const val MAX_TEXT = 8_000_000L
}

/** Word 正文清洗：段落标记、表格单元格、域代码（0x13/0x14/0x15） */
internal object WordText {

    fun clean(raw: String): String {
        val sb = StringBuilder(raw.length)
        var field = 0
        for (ch in raw) {
            val o = ch.code
            when {
                o == 0x13 -> field++
                o == 0x14 -> if (field > 0) field--
                o == 0x15 -> Unit
                field > 0 -> Unit
                o == 0x0D || o == 0x0B || o == 0x0C || o == 0x0E -> sb.append('\n')
                o == 0x07 || o == 0x09 -> sb.append('\t')
                o == 0x1E -> sb.append('-')
                o == 0xA0 -> sb.append(' ')
                o < 0x20 -> Unit
                o == 0xFFFC || o == 0xFFFD -> Unit
                else -> sb.append(ch)
            }
        }
        var t = sb.toString().replace("\r\n", "\n")
        t = t.replace(Regex("[ \\t\\u3000]{2,}"), "  ")
        t = t.replace(Regex("\\n{3,}"), "\n\n")
        return t.trim()
    }
}

/** 单字节/多字节正文解码：优先按文档代码页，读不到再按内容猜 */
internal object AnsiText {

    fun charsetOf(codepage: Int): Charset? = try {
        when (codepage) {
            936 -> Charset.forName("GBK")
            950 -> Charset.forName("Big5")
            932 -> Charset.forName("Shift_JIS")
            949 -> Charset.forName("EUC-KR")
            65001 -> Charsets.UTF_8
            0, 1200 -> null
            else -> if (codepage in 1250..1258) Charset.forName("windows-$codepage") else null
        }
    } catch (_: Throwable) {
        null
    }

    fun decode(bytes: ByteArray, codepage: Int): String {
        if (bytes.isEmpty()) return ""
        if (codepage == 65001) return String(bytes, Charsets.UTF_8)
        val cs = charsetOf(codepage)
        if (cs != null) {
            return try {
                String(bytes, cs)
            } catch (_: Throwable) {
                String(bytes, Charsets.ISO_8859_1)
            }
        }
        return guess(bytes)
    }

    private fun guess(bytes: ByteArray): String {
        var high = 0
        for (b in bytes) if (b.toInt() and 0xFF >= 0x80) high++
        if (high == 0) return String(bytes, Charsets.US_ASCII)

        val utf8 = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        } catch (_: Throwable) {
            null
        }
        if (utf8 != null) return utf8

        val gbk = try {
            String(bytes, Charset.forName("GBK"))
        } catch (_: Throwable) {
            null
        }
        if (gbk != null) {
            var readable = 0
            for (ch in gbk) {
                if (ch.isLetterOrDigit() || ch.isWhitespace() || ch.code in 0x4E00..0x9FFF ||
                    ch in "，。、；：（）“”‘’！？《》—…·【】"
                ) readable++
            }
            if (readable >= gbk.length * 9 / 10) return gbk
        }
        return String(bytes, Charsets.ISO_8859_1)
    }
}
