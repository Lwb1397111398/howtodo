package com.howtodo.ingest

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * 最小可用的 OLE2 / 复合文档（Compound File Binary）只读解析器。
 *
 * .doc / .xls / .ppt 这些 97-2003 格式本质上是「一个文件里的虚拟文件系统」：
 * 头部是扇区分配表（FAT + MiniFAT），目录区记录每个「流」的起始扇区与长度。
 * 只要能把流读出来，各格式的正文就只是流里的字节，不需要引入 Apache POI
 * （10MB+ 体积，且本机 Gradle 缓存里没有，会直接卡住构建）。
 *
 * 只读、按需 seek，不把整个文件读进内存；单个流上限 64MB 防止异常文件打爆堆。
 */
class CfbReader private constructor(private val raf: RandomAccessFile) : Closeable {

    private data class Entry(
        val name: String,
        val type: Int,
        val start: Long,
        val size: Long
    )

    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Long
    private val dirStart: Long
    private val miniFatStart: Long
    private val difatStart: Long
    private val fatCount: Long

    private var fat: LongArray = LongArray(0)
    private var miniFat: LongArray = LongArray(0)
    private var miniStream: ByteArray = ByteArray(0)
    private val entries = ArrayList<Entry>()

    init {
        val head = readAt(0, HEADER_SIZE)
        sectorSize = 1 shl u16(head, 0x1E)
        miniSectorSize = 1 shl u16(head, 0x20)
        fatCount = u32(head, 0x2C)
        dirStart = u32(head, 0x30)
        miniCutoff = u32(head, 0x38).let { if (it <= 0) 4096L else it }
        miniFatStart = u32(head, 0x3C)
        difatStart = u32(head, 0x44)

        loadFat(head)
        loadDirectory()
        loadMiniFat()
    }

    // ------------------------------------------------------------------ 公开 API

    /** 全部流名（不含 storage），用于嗅探文件到底是 Word/Excel/PPT */
    fun streamNames(): List<String> = entries.filter { it.type == STREAM }.map { it.name }

    fun hasStream(name: String): Boolean =
        entries.any { it.type == STREAM && it.name == name }

    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == STREAM && it.name == name } ?: return null
        if (e.size <= 0) return ByteArray(0)
        return if (e.size < miniCutoff) readMiniChain(e.start, e.size) else readChain(e.start, e.size)
    }

    /**
     * 读文档代码页（SummaryInformation 里的 PID_CODEPAGE）。
     * 老 Word 的「压缩片段」按这个代码页存字节，读错就是一堆乱码。
     * 读不到时返回 1200（Unicode），调用方按启发式猜编码。
     */
    fun codepage(): Int {
        val ps = stream("\u0005SummaryInformation") ?: return 1200
        if (ps.size < 48) return 1200
        return try {
            val sections = u32(ps, 0x18)
            if (sections <= 0) return 1200
            val secOff = u32(ps, 0x2C)
            if (secOff + 8 > ps.size) return 1200
            val base = secOff.toInt()
            val count = u32(ps, base + 4)
            if (count <= 0 || count > 512) return 1200
            for (i in 0 until count.toInt()) {
                val pid = u32(ps, base + 8 + i * 8)
                val off = u32(ps, base + 12 + i * 8)
                if (pid != 1L) continue
                val at = base + off.toInt()
                if (at + 6 > ps.size) continue
                val vt = u32(ps, at)
                if (vt == 2L) return u16(ps, at + 4).let { if (it == 0) 1200 else it }
            }
            1200
        } catch (_: Throwable) {
            1200
        }
    }

    override fun close() {
        try {
            raf.close()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ 初始化

    private fun loadFat(head: ByteArray) {
        val difat = ArrayList<Long>(140)
        for (i in 0 until 109) difat.add(u32(head, 0x4C + i * 4))

        var sect = difatStart
        val perSector = sectorSize / 4 - 1
        var guard = 0
        while (sect != ENDOFCHAIN && sect != FREESECT && guard++ < 200_000) {
            val b = readSector(sect)
            if (b.size < sectorSize) break
            for (i in 0 until perSector) difat.add(u32(b, i * 4))
            sect = u32(b, perSector * 4)
        }

        val perFat = sectorSize / 4
        val out = ArrayList<Long>(maxOf(difat.size, 1) * perFat)
        var seen = 0L
        for (fs in difat) {
            if (fs == ENDOFCHAIN || fs == FREESECT) continue
            if (fatCount > 0 && seen >= fatCount) break
            val b = readSector(fs)
            if (b.size < sectorSize) break
            for (i in 0 until perFat) out.add(u32(b, i * 4))
            seen++
        }
        fat = out.toLongArray()
    }

    private fun loadDirectory() {
        val raw = readChain(dirStart, -1) ?: return
        var i = 0
        while (i + ENTRY_SIZE <= raw.size) {
            val nameLen = u16(raw, i + 0x40)
            val type = raw[i + 0x42].toInt() and 0xFF
            if (nameLen in 2..64 && type != 0) {
                val name = String(raw, i, nameLen - 2, Charsets.UTF_16LE)
                val start = u32(raw, i + 0x74)
                val size = u32(raw, i + 0x78)
                entries.add(Entry(name, type, start, size))
            }
            i += ENTRY_SIZE
        }
    }

    private fun loadMiniFat() {
        if (miniFatStart == ENDOFCHAIN || miniFatStart == FREESECT) return
        val raw = readChain(miniFatStart, -1) ?: return
        val n = raw.size / 4
        val arr = LongArray(n)
        for (i in 0 until n) arr[i] = u32(raw, i * 4)
        miniFat = arr
    }

    private fun rootEntry(): Entry? = entries.firstOrNull { it.type == ROOT }

    private fun ensureMiniStream() {
        if (miniStream.isNotEmpty()) return
        val root = rootEntry() ?: return
        miniStream = readChain(root.start, root.size) ?: ByteArray(0)
    }

    // ------------------------------------------------------------------ 扇区与链

    private fun readSector(sect: Long): ByteArray {
        if (sect < 0) return ByteArray(0)
        return readAt((sect + 1) * sectorSize, sectorSize)
    }

    private fun nextSector(sect: Long, mini: Boolean): Long {
        val table = if (mini) miniFat else fat
        if (sect < 0 || sect >= table.size) return ENDOFCHAIN
        return table[sect.toInt()]
    }

    /** @param size 期望字节数，-1 表示读到链尾 */
    private fun readChain(start: Long, size: Long): ByteArray? {
        if (start == ENDOFCHAIN || start == FREESECT) return ByteArray(0)
        val limit = if (size in 0..MAX_STREAM) size else MAX_STREAM
        val out = java.io.ByteArrayOutputStream(minOf(limit, 1L shl 20).toInt().coerceAtLeast(16))
        var sect = start
        var guard = 0
        val buf = ByteArray(sectorSize)
        while (sect != ENDOFCHAIN && sect != FREESECT && guard++ < MAX_SECTORS) {
            raf.seek((sect + 1) * sectorSize)
            var read = 0
            while (read < sectorSize) {
                val n = raf.read(buf, read, sectorSize - read)
                if (n <= 0) break
                read += n
            }
            if (read <= 0) break
            val take = if (limit > 0) minOf(read.toLong(), limit - out.size()).toInt() else read
            if (take > 0) out.write(buf, 0, take)
            if (limit in 0..MAX_STREAM && out.size() >= limit) break
            sect = nextSector(sect, mini = false)
        }
        return out.toByteArray()
    }

    private fun readMiniChain(start: Long, size: Long): ByteArray? {
        ensureMiniStream()
        if (miniStream.isEmpty()) return ByteArray(0)
        val limit = minOf(size, MAX_STREAM)
        val out = java.io.ByteArrayOutputStream(minOf(limit, 1L shl 20).toInt().coerceAtLeast(16))
        var sect = start
        var guard = 0
        while (sect != ENDOFCHAIN && sect != FREESECT && guard++ < MAX_SECTORS) {
            val off = sect * miniSectorSize
            if (off < 0 || off >= miniStream.size) break
            val end = minOf(off + miniSectorSize, miniStream.size.toLong()).toInt()
            val take = minOf((end - off).toLong(), limit - out.size()).toInt()
            if (take <= 0) break
            out.write(miniStream, off.toInt(), take)
            if (out.size() >= limit) break
            sect = nextSector(sect, mini = true)
        }
        return out.toByteArray()
    }

    private fun readAt(offset: Long, size: Int): ByteArray {
        val buf = ByteArray(size)
        if (size <= 0) return buf
        raf.seek(offset)
        var read = 0
        while (read < size) {
            val n = raf.read(buf, read, size - read)
            if (n <= 0) break
            read += n
        }
        return if (read == size) buf else buf.copyOf(read)
    }

    companion object {
        private const val HEADER_SIZE = 512
        private const val ENTRY_SIZE = 128
        private const val ROOT = 5
        private const val STREAM = 2
        private const val ENDOFCHAIN = 0xFFFFFFFEL
        private const val FREESECT = 0xFFFFFFFFL
        private const val MAX_STREAM = 64L * 1024 * 1024
        private const val MAX_SECTORS = 4_000_000

        fun isCfb(head: ByteArray): Boolean =
            head.size >= 8 &&
                (head[0].toInt() and 0xFF) == 0xD0 &&
                (head[1].toInt() and 0xFF) == 0xCF &&
                (head[2].toInt() and 0xFF) == 0x11 &&
                (head[3].toInt() and 0xFF) == 0xE0

        fun open(file: File): CfbReader? {
            if (!file.exists() || file.length() < HEADER_SIZE) return null
            var raf: RandomAccessFile? = null
            return try {
                raf = RandomAccessFile(file, "r")
                val head = ByteArray(8)
                raf.readFully(head)
                if (!isCfb(head)) {
                    raf.close()
                    return null
                }
                CfbReader(raf)
            } catch (_: Throwable) {
                try {
                    raf?.close()
                } catch (_: Throwable) {
                }
                null
            }
        }

        fun u16(b: ByteArray, o: Int): Int {
            if (o < 0 || o + 2 > b.size) return 0
            return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        }

        fun u32(b: ByteArray, o: Int): Long {
            if (o < 0 || o + 4 > b.size) return 0
            var v = 0L
            for (i in 3 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            return v
        }
    }
}
