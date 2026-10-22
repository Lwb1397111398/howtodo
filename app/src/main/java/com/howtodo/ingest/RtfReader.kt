package com.howtodo.ingest

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * RTF（.rtf，以及很多「另存为 .doc 其实是 RTF」的文件）正文提取。
 *
 * RTF 是纯文本标记语言：`\par` 是段落，`\'hh` 是单字节转义，`\uN` 是 Unicode 码位。
 * 中文 RTF 通常声明 `\ansicpg936`，`\'hh` 成对出现才是完整汉字，所以必须把连续的
 * 十六进制转义先攒成字节再按代码页解码，逐字节转字符会得到乱码。
 */
object RtfReader {

    private val DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "objdata",
        "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf",
        "footnote", "annotation", "listtable", "listoverridetable", "listtext", "revtbl",
        "themedata", "colorschememapping", "datastore", "latentstyles", "xmlopen",
        "generator", "fldinst", "filetbl", "rsidtbl", "mmathpr", "wgrffmtfilter",
        "pgptbl", "pnseclvl", "blipuid", "shppict", "nonshppict", "template", "author"
    )

    fun read(file: File): String {
        val raw = try {
            file.readBytes().toString(Charsets.ISO_8859_1)
        } catch (_: Throwable) {
            throw IllegalStateException("RTF 文件读取失败")
        }
        val text = parse(raw)
        if (text.isBlank()) throw IllegalStateException("没有从 RTF 文档里读到文字")
        return text
    }

    fun parse(src: String): String {
        val out = StringBuilder()
        val bytes = ByteArrayOutputStream()
        val groups = ArrayList<Boolean>()
        var skipping = false
        var codepage = 1252
        var ucSkip = 1
        var skipChars = 0
        var i = 0

        fun flush() {
            if (bytes.size() > 0) {
                out.append(AnsiText.decode(bytes.toByteArray(), codepage))
                bytes.reset()
            }
        }

        fun emit(text: String) {
            if (skipping || text.isEmpty()) return
            flush()
            out.append(text)
        }

        while (i < src.length && out.length < MAX_OUT) {
            val c = src[i]
            when (c) {
                '{' -> {
                    groups.add(skipping)
                    i++
                }

                '}' -> {
                    if (bytes.size() > 0) flush()
                    if (groups.isNotEmpty()) skipping = groups.removeAt(groups.size - 1)
                    i++
                }

                '\\' -> {
                    i++
                    if (i >= src.length) break
                    val n = src[i]
                    when {
                        n == '\\' || n == '{' || n == '}' -> {
                            if (skipChars > 0) skipChars-- else emit(n.toString())
                            i++
                        }

                        n == '\'' -> {
                            val hex = src.substring(i + 1, minOf(i + 3, src.length))
                            val b = hex.toIntOrNull(16)
                            i += 3
                            if (skipChars > 0) {
                                skipChars--
                            } else if (b != null && !skipping) {
                                bytes.write(b)
                            }
                        }

                        n == '*' -> {
                            skipping = true
                            i++
                        }

                        n == '\n' || n == '\r' -> {
                            emit("\n")
                            i++
                        }

                        n.isLetter() -> {
                            val start = i
                            while (i < src.length && src[i].isLetter()) i++
                            val word = src.substring(start, i).lowercase()
                            var num: Int? = null
                            if (i < src.length && (src[i] == '-' || src[i].isDigit())) {
                                val ns = i
                                if (src[i] == '-') i++
                                while (i < src.length && src[i].isDigit()) i++
                                num = src.substring(ns, i).toIntOrNull()
                            }
                            if (i < src.length && src[i] == ' ') i++

                            when (word) {
                                "ansicpg" -> {
                                    num?.let { codepage = it }
                                }

                                "uc" -> num?.let { ucSkip = it.coerceIn(0, 8) }
                                "u" -> {
                                    val code = num ?: 0
                                    val fixed = if (code < 0) code + 65536 else code
                                    if (!skipping && fixed in 1..0xFFFF) emit(fixed.toChar().toString())
                                    skipChars = ucSkip
                                }

                                "par", "line", "sect", "page", "row" -> emit("\n")
                                "tab", "cell" -> emit("\t")
                                "emdash" -> emit("—")
                                "endash" -> emit("–")
                                "lquote" -> emit("‘")
                                "rquote" -> emit("’")
                                "ldblquote" -> emit("“")
                                "rdblquote" -> emit("”")
                                "bullet" -> emit("·")
                                else -> if (word in DESTINATIONS) skipping = true
                            }
                        }

                        else -> i++
                    }
                }

                else -> {
                    if (skipChars > 0) {
                        skipChars--
                    } else {
                        emit(c.toString())
                    }
                    i++
                }
            }
        }
        if (bytes.size() > 0) flush()
        return out.toString()
            .replace("\r\n", "\n")
            .replace(Regex("[ \\t\\u3000]{2,}"), "  ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    @Suppress("unused")
    private fun charsetFor(cp: Int): Charset? = AnsiText.charsetOf(cp)

    private const val MAX_OUT = 8_000_000
}
