package com.howtodo.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.howtodo.data.db.TaskStatus
import com.howtodo.data.db.TaskWithProject
import com.howtodo.data.db.ProposalWithProject
import com.howtodo.ui.common.InputDialog
import com.howtodo.ui.common.container
import com.howtodo.ui.components.EmptyState
import com.howtodo.ui.components.SectionHeader
import com.howtodo.ui.components.TaskRow
import com.howtodo.ui.theme.Semantic
import com.howtodo.util.Format
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenProject: (String) -> Unit,
    onOpenProposal: (String) -> Unit,
    onOpenMaterials: () -> Unit,
    onOpenChat: (String?) -> Unit,
    onOpenSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val c = ctx.container()
    val scope = rememberCoroutineScope()

    val openTasks by c.projectRepo.observeOpenTasks().collectAsState(initial = emptyList())
    val proposals by c.projectRepo.observePendingProposals().collectAsState(initial = emptyList())

    var showNew by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        configured = runCatching { c.router.isConfigured() }.getOrDefault(false)
    }

    val todayStart = Format.startOfDay(LocalDate.now())
    val todayEnd = Format.endOfDay(LocalDate.now())
    val soonEnd = todayEnd + 3 * 24 * 3600_000L

    val overdue = openTasks.filter {
        it.task.status == TaskStatus.TODO && it.task.dueAt != null && it.task.dueAt < todayStart
    }
    val today = openTasks.filter {
        val d = it.task.dueAt
        it.task.status == TaskStatus.TODO && d != null && d in todayStart..todayEnd
    }
    val soon = openTasks.filter {
        val d = it.task.dueAt
        it.task.status == TaskStatus.TODO && d != null && d > todayEnd && d <= soonEnd
    }
    val undated = openTasks.filter { it.task.dueAt == null && it.task.status != TaskStatus.NEEDS_CONFIRM }
    val confirm = openTasks.filter { it.task.status == TaskStatus.NEEDS_CONFIRM }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("今日", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = formatTodayHeader(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenMaterials() }) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "材料库")
                    }
                    IconButton(onClick = { onOpenChat(null) }) {
                        Icon(Icons.Outlined.Chat, contentDescription = "问一问")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新建工作") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp, end = 14.dp, top = 4.dp, bottom = 90.dp
            )
        ) {
            // 汇总卡
            item {
                SummaryCard(overdue = overdue.size, today = today.size, soon = soon.size, confirm = confirm.size)
            }

            if (configured == false) {
                item { SetupBanner(onOpenSettings) }
            }

            if (proposals.isNotEmpty()) {
                item {
                    SectionHeader("待确认的分析结果", proposals.size)
                }
                items(proposals, key = { it.proposal.id }) { p ->
                    ProposalCard(p, onClick = { onOpenProposal(p.proposal.id) })
                }
            }

            if (confirm.isNotEmpty()) {
                item { SectionHeader("需要你确认时间", confirm.size) }
                items(confirm, key = { it.task.id }) { t ->
                    TaskRow(
                        title = t.task.title,
                        dueAt = t.task.dueAt,
                        hasTime = t.task.dueHasTime,
                        status = t.task.status,
                        projectTitle = t.projectTitle,
                        detail = t.task.detail,
                        assumption = t.task.assumption,
                        onClick = { onOpenProject(t.task.projectId) },
                        onToggle = {
                            scope.launch { c.taskRepo.toggleDone(t.task.id) }
                        }
                    )
                }
            }

            if (overdue.isNotEmpty()) {
                item { SectionHeader("已逾期", overdue.size) }
                items(overdue, key = { it.task.id }) { t -> todayRow(t, c, scope, onOpenProject) }
            }

            if (today.isNotEmpty()) {
                item { SectionHeader("今天", today.size) }
                items(today, key = { it.task.id }) { t -> todayRow(t, c, scope, onOpenProject) }
            }

            if (soon.isNotEmpty()) {
                item { SectionHeader("3 天内", soon.size) }
                items(soon, key = { it.task.id }) { t -> todayRow(t, c, scope, onOpenProject) }
            }

            if (undated.isNotEmpty()) {
                item { SectionHeader("还没有确定时间", undated.size) }
                items(undated, key = { it.task.id }) { t -> todayRow(t, c, scope, onOpenProject) }
            }

            if (openTasks.isEmpty() && proposals.isEmpty()) {
                item {
                    EmptyState(
                        title = if (configured == false) "先配置一个模型" else "现在没有待办",
                        hint = if (configured == false) {
                            "填上你的 API 地址和 Key，就能自动把通知变成任务了"
                        } else {
                            "点右下角新建一个工作，把微信里的通知和文件丢进来"
                        }
                    )
                }
            }
        }
    }

    if (showNew) {
        InputDialog(
            title = "新建工作",
            label = "这项工作叫什么",
            hint = "例如「2026年秋季运动会」「期中教学检查」。留空也可以，分析材料后会自动命名。",
            confirmText = "创建",
            onDismiss = { showNew = false },
            onConfirm = { title ->
                scope.launch {
                    val p = c.projectRepo.create(title)
                    showNew = false
                    onOpenProject(p.id)
                }
            }
        )
    }
}

@Composable
private fun todayRow(
    t: TaskWithProject,
    c: com.howtodo.di.AppContainer,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenProject: (String) -> Unit
) {
    TaskRow(
        title = t.task.title,
        dueAt = t.task.dueAt,
        hasTime = t.task.dueHasTime,
        status = t.task.status,
        projectTitle = t.projectTitle,
        detail = t.task.detail,
        assumption = t.task.assumption,
        onClick = { onOpenProject(t.task.projectId) },
        onToggle = { scope.launch { c.taskRepo.toggleDone(t.task.id) } }
    )
}

@Composable
private fun SummaryCard(overdue: Int, today: Int, soon: Int, confirm: Int) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = if (overdue + today == 0) "今天暂时没有到期的事" else "今天有 $today 项要到期",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Metric("逾期", overdue, if (overdue > 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f))
                Metric("今天", today, MaterialTheme.colorScheme.onPrimary)
                Metric("3天内", soon, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f))
                if (confirm > 0) {
                    Metric("待确认", confirm, MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: Int, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun ProposalCard(p: ProposalWithProject, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Semantic.confirm.copy(alpha = 0.08f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = Semantic.confirm,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = p.projectTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = p.proposal.summary ?: "有新的分析结果待确认",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("去确认", style = MaterialTheme.typography.labelMedium, color = Semantic.confirm)
        }
    }
}

@Composable
private fun SetupBanner(onOpenSettings: () -> Unit) {
    Surface(
        onClick = onOpenSettings,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "还没有配置模型",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "填上 API 地址和 Key（支持任意 OpenAI 兼容接口），才能自动分析材料。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
            )
        }
    }
}

private fun formatTodayHeader(): String {
    val d = LocalDate.now()
    val week = when (d.dayOfWeek.value) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
        5 -> "周五"; 6 -> "周六"; else -> "周日"
    }
    return "${d.monthValue}月${d.dayOfMonth}日 $week"
}
