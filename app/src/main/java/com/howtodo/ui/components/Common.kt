package com.howtodo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.howtodo.data.db.TaskStatus
import com.howtodo.ui.theme.Semantic
import com.howtodo.util.Format
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 根据截止时间给出紧急度颜色：逾期红 / 今天橙 / 3天内橙 / 以后灰 */
@Composable
fun dueColor(dueAt: Long?, status: String): Color {
    if (status == TaskStatus.DONE) return MaterialTheme.colorScheme.onSurfaceVariant
    if (dueAt == null) return MaterialTheme.colorScheme.onSurfaceVariant
    val today = LocalDate.now()
    val d = Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val days = ChronoUnit.DAYS.between(today, d)
    return when {
        dueAt < System.currentTimeMillis() && days <= 0 -> Semantic.overdue
        days < 0 -> Semantic.overdue
        days <= 1 -> Semantic.soon
        days <= 3 -> Semantic.soon
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
fun DueChip(
    dueAt: Long?,
    hasTime: Boolean,
    status: String,
    modifier: Modifier = Modifier
) {
    val color = dueColor(dueAt, status)
    val text = when {
        status == TaskStatus.DONE -> "已完成"
        status == TaskStatus.CANCELLED -> "已取消"
        dueAt == null -> "未定时间"
        else -> Format.relative(dueAt, hasTime)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun TagChip(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
fun SectionHeader(
    title: String,
    count: Int? = null,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(
    title: String,
    hint: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun LoadingRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CheckCircle(checked: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .then(
                if (checked) Modifier.background(MaterialTheme.colorScheme.primary)
                else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已完成",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 任务行：勾选圈 + 标题 + 截止 + 所属工作。
 * 已完成的任务标题加删除线并整体降透明，减少视觉噪音。
 */
@Composable
fun TaskRow(
    title: String,
    dueAt: Long?,
    hasTime: Boolean,
    status: String,
    projectTitle: String? = null,
    detail: String? = null,
    assumption: String? = null,
    onClick: () -> Unit,
    onToggle: () -> Unit
) {
    val done = status == TaskStatus.DONE
    val needsConfirm = status == TaskStatus.NEEDS_CONFIRM
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Top
        ) {
            CheckCircle(checked = done, onClick = onToggle)
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DueChip(dueAt, hasTime, status)
                    if (needsConfirm) {
                        Spacer(Modifier.width(6.dp))
                        TagChip("待确认", Semantic.confirm)
                    }
                    if (!projectTitle.isNullOrBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = projectTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                if (!assumption.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "推断依据：$assumption",
                        style = MaterialTheme.typography.labelSmall,
                        color = Semantic.confirm.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 进度环：已完成/总数 */
@Composable
fun ProgressRing(done: Int, total: Int, size: Int = 44) {
    val ratio = if (total <= 0) 0f else done.toFloat() / total
    Box(
        modifier = Modifier.size(size.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { ratio },
            modifier = Modifier.size(size.dp),
            strokeWidth = 4.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        )
        Text(
            text = if (total > 0) "$done/$total" else "0",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
