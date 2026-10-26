package com.howtodo.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParser
import com.howtodo.data.db.ConflictType
import com.howtodo.data.db.ItemDecision
import com.howtodo.data.db.ProposalItemEntity
import com.howtodo.data.db.ProposalState
import com.howtodo.data.db.TaskOp
import com.howtodo.ui.common.container
import com.howtodo.ui.components.EmptyState
import com.howtodo.ui.components.TagChip
import com.howtodo.ui.theme.Semantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposalScreen(
    proposalId: String,
    onBack: () -> Unit,
    onOpenPreview: (String, Int) -> Unit
) {
    val ctx = LocalContext.current
    val c = ctx.container()
    val scope = rememberCoroutineScope()

    var proposal by remember { mutableStateOf<com.howtodo.data.db.ProposalEntity?>(null) }
    var projectTitle by remember { mutableStateOf("") }
    var chunkInfo by remember { mutableStateOf<Map<Long, Triple<String, String, Int>>>(emptyMap()) }
    val items by c.proposalRepo.observeItems(proposalId).collectAsState(initial = emptyList())

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var appliedTitles by remember { mutableStateOf(false) }

    // 引文 chunkId 随 items 一起解析；items 是 Flow 异步到达的，不能在 LaunchedEffect(proposalId)
    // 里直接读 items——那时大概率还是空列表，引文「查看原文」会全部点不动
    val evidenceChunkIds = remember(items) {
        items.flatMap { item ->
            runCatching {
                JsonParser.parseString(item.evidenceJson ?: "[]").asJsonArray
            }.getOrNull()?.mapNotNull {
                runCatching { it.asJsonObject.get("chunkId")?.asLong }.getOrNull()
            }.orEmpty()
        }.distinct()
    }

    LaunchedEffect(proposalId) {
        val p = withContext(Dispatchers.IO) { c.proposalRepo.byId(proposalId) }
        proposal = p
        if (p != null) {
            projectTitle = withContext(Dispatchers.IO) {
                c.projectRepo.byId(p.projectId)?.title ?: ""
            }
        }
    }

    // chunkId 列表不变就不重查（决策切换会让 items 重发，但 id 列表相等，LaunchedEffect 不会重启）
    LaunchedEffect(evidenceChunkIds) {
        if (evidenceChunkIds.isEmpty()) return@LaunchedEffect
        chunkInfo = withContext(Dispatchers.IO) {
            c.db.chunkDao().byIds(evidenceChunkIds).associate { ch ->
                val src = c.sourceRepo.byId(ch.sourceId)
                ch.id to Triple(ch.sourceId, src?.displayName ?: "材料", ch.pageNo ?: 0)
            }
        }
    }

    val pendingCount = items.count { it.decision == ItemDecision.PENDING }
    val acceptCount = items.count { it.decision == ItemDecision.ACCEPT && it.op != TaskOp.NOOP }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("确认变更", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = projectTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            val state = proposal?.state
            if (state == ProposalState.PENDING || state == ProposalState.PARTIALLY_APPLIED) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        c.proposalRepo.decideAll(proposalId, ItemDecision.ACCEPT)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("全部接受") }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        c.proposalRepo.decideAll(proposalId, ItemDecision.REJECT)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("全部拒绝") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                busy = true
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { c.proposalRepo.apply(proposalId) }
                                    busy = false
                                    r.fold(
                                        onSuccess = { res ->
                                            message = "已应用：新增 ${res.created}、修改 ${res.updated}、取消 ${res.cancelled}" +
                                                if (res.evidenceWarnings > 0) "（${res.evidenceWarnings} 项被跳过）" else ""
                                            appliedTitles = true
                                            withContext(Dispatchers.IO) {
                                                c.reminderRepo.rescheduleAll()
                                                c.alarmScheduler.rescheduleAll()
                                            }
                                            proposal = withContext(Dispatchers.IO) { c.proposalRepo.byId(proposalId) }
                                        },
                                        onFailure = { e -> message = e.message }
                                    )
                                }
                            },
                            enabled = !busy && acceptCount > 0,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (busy) "正在应用…" else "应用选中的 $acceptCount 项")
                        }
                        if (acceptCount == 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "没有选中任何条目。点击条目可以切换接受/拒绝。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (state == ProposalState.APPLIED) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "已应用到清单",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { c.proposalRepo.undo(proposalId) }
                                busy = false
                                r.fold(
                                    onSuccess = { n -> message = "已撤销 $n 处改动" },
                                    onFailure = { e -> message = e.message }
                                )
                                withContext(Dispatchers.IO) {
                                    c.reminderRepo.rescheduleAll()
                                    c.alarmScheduler.rescheduleAll()
                                }
                                proposal = withContext(Dispatchers.IO) { c.proposalRepo.byId(proposalId) }
                            }
                        }) { Text("撤销本次") }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(14.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = proposal?.summary ?: "AI 分析结果",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = "AI 不会直接改你的清单。下面每一条都可以单独接受或拒绝，" +
                                "带「需确认」的条目请重点看。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (items.isEmpty()) {
                item { EmptyState(title = "没有条目") }
            }

            items(items, key = { it.id }) { item ->
                ItemCard(
                    item = item,
                    chunkInfo = chunkInfo,
                    onToggle = {
                        val next = if (item.decision == ItemDecision.ACCEPT) ItemDecision.REJECT else ItemDecision.ACCEPT
                        scope.launch { c.proposalRepo.decide(item.id, next) }
                    },
                    onOpenPreview = onOpenPreview
                )
            }

            item {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "看不清依据？点条目里的「查看原文」会跳到材料原文位置。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }

    if (message != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text(if (appliedTitles) "完成" else "提示") },
            text = { Text(message ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    message = null
                    if (appliedTitles) onBack()
                }) { Text("好") }
            }
        )
    }
}

@Composable
private fun ItemCard(
    item: ProposalItemEntity,
    chunkInfo: Map<Long, Triple<String, String, Int>>,
    onToggle: () -> Unit,
    onOpenPreview: (String, Int) -> Unit
) {
    val accepted = item.decision == ItemDecision.ACCEPT
    val rejected = item.decision == ItemDecision.REJECT
    val (label, color) = when (item.op) {
        TaskOp.ADD -> "新增" to MaterialTheme.colorScheme.primary
        TaskOp.UPDATE -> "修改" to MaterialTheme.colorScheme.secondary
        TaskOp.CANCEL -> "取消" to MaterialTheme.colorScheme.error
        else -> "提醒" to Semantic.confirm
    }

    val evidences = remember(item.evidenceJson) {
        runCatching {
            JsonParser.parseString(item.evidenceJson ?: "[]").asJsonArray.map { el ->
                val o = el.asJsonObject
                (o.get("chunkId")?.takeIf { !it.isJsonNull }?.asLong) to
                    (o.get("quote")?.takeIf { !it.isJsonNull }?.asString ?: "")
            }
        }.getOrDefault(emptyList())
    }

    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = when {
            rejected -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            accepted -> MaterialTheme.colorScheme.surface
            else -> Semantic.confirm.copy(alpha = 0.05f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TagChip(label, color)
                if (item.conflict != null) {
                    Spacer(Modifier.width(6.dp))
                    TagChip("需确认", Semantic.confirm)
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (accepted) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = if (accepted) "已接受" else "已拒绝",
                    tint = if (accepted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = item.diffText ?: item.payload,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (rejected) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
            if (!item.reason.isNullOrBlank() && item.reason != item.diffText) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.conflict != null) {
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = Semantic.confirm,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = item.conflictNote ?: conflictText(item.conflict),
                        style = MaterialTheme.typography.labelSmall,
                        color = Semantic.confirm
                    )
                }
            }

            if (evidences.isNotEmpty()) {
                Spacer(Modifier.height(9.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Spacer(Modifier.height(8.dp))
                evidences.take(2).forEach { (chunkId, quote) ->
                    val info = chunkId?.let { chunkInfo[it] }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = info != null) {
                                if (info != null) onOpenPreview(info.first, 0)
                            }
                            .padding(vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Description,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = buildString {
                                    append(info?.second ?: "材料")
                                    val page = info?.third ?: 0
                                    if (page > 0) append(" 第${page}页")
                                    append(" · 查看原文")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "「$quote」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun conflictText(type: String): String = when (type) {
    ConflictType.DONE_CONFLICT -> "该任务已完成，AI 不会改动"
    ConflictType.MISSING_TARGET -> "找不到对应任务"
    ConflictType.DUP_SUSPECT -> "可能与已有任务重复"
    ConflictType.NO_EVIDENCE_DATE -> "原文没有明确日期，需要你确认"
    else -> "材料中存在不一致"
}
