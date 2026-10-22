package com.howtodo.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 日期/时间格式化与解析工具。全部使用系统默认时区。 */
object Format {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val dateFmt = DateTimeFormatter.ofPattern("M月d日")
    private val dateYearFmt = DateTimeFormatter.ofPattern("yyyy年M月d日")
    private val dateTimeFmt = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val isoFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun date(epochMillis: Long?): String {
        if (epochMillis == null) return ""
        val d = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return if (d.year == LocalDate.now().year) dateFmt.format(d) else dateYearFmt.format(d)
    }

    fun dateTime(epochMillis: Long?): String {
        if (epochMillis == null) return ""
        val dt = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return dateTimeFmt.format(dt)
    }

    fun iso(epochMillis: Long?): String {
        if (epochMillis == null) return ""
        return isoFmt.format(Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate())
    }

    fun time(epochMillis: Long?): String {
        if (epochMillis == null) return ""
        return timeFmt.format(Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime())
    }

    /** 相对描述：今天 / 明天 / 3天后 / 已逾期5天 */
    fun relative(epochMillis: Long?, hasTime: Boolean = false): String {
        if (epochMillis == null) return "未定"
        val target = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        val today = LocalDate.now()
        val days = ChronoUnit.DAYS.between(today, target)
        val hm = if (hasTime) " " + time(epochMillis) else ""
        return when {
            days == 0L -> "今天$hm"
            days == 1L -> "明天$hm"
            days == 2L -> "后天$hm"
            days > 1 -> "${days}天后$hm"
            days == -1L -> "昨天"
            else -> "逾期${-days}天"
        }
    }

    fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDay(date: LocalDate): Long =
        date.atTime(LocalTime.MAX).atZone(zone).toInstant().toEpochMilli()

    fun toMillis(date: LocalDate, time: LocalTime?): Long =
        LocalDateTime.of(date, time ?: LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli()

    fun parseIsoDate(s: String?): LocalDate? = try {
        if (s.isNullOrBlank()) null else LocalDate.parse(s, isoFmt)
    } catch (_: Throwable) {
        null
    }

    /** 当天剩余小时数（用于"还有几个小时"提示） */
    fun hoursLeft(epochMillis: Long): Long =
        ChronoUnit.HOURS.between(LocalDateTime.now(), Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime())
}
