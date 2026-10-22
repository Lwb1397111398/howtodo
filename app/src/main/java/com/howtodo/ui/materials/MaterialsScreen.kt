package com.howtodo.ui.materials

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.howtodo.data.db.ProjectEntity
import com.howtodo.data.db.SourceStatus
import com.howtodo.data.db.SourceWithProject
import com.howtodo.ui.common.ConfirmDialog
import com.howtodo.ui.common.container
import com.howtodo.ui.components.EmptyState
import com.howtodo.ui.components.LoadingRow
import com.howtodo.ui.components.SourceActionsMenu
import com.howtodo.ui.components.TagChip
import com.howtodo.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialsScreen(onOpenPreview: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container()
    val sources by c.sourceRepo.observeAll().collectAsState(initial = emptyList())

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<com.howtodo.rag.Retriever.Hit>>(emptyList()) }

    // 分享进来的材料默认落在材料库，需要能归到某项工作，否则工作内检索和 AI 分析都用不上
    var assignTarget by remember { mutableStateOf<SourceWithProject?>(null) }
    var deleteTarget by remember { mutableStateOf<SourceWithProject?>(null) }
    var allProjects by remember { mutableStateOf<List<ProjectEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun reparse(id: String) {
        scope.launch(Dispatchers.IO) {
            // 失败原因会写回 source.error，状态经 observeAll 自动刷新，这里不额外弹窗
            runCatching { c.importManager.reparse(id) }
        }
    }

    fun delete(id: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { c.sourceRepo.delete(id) }
        }
    }

    val toDelete = deleteTarget
    if (toDelete != null) {
        ConfirmDialog(
            title = "删除这份材料？",
            message = "「${toDelete.source.displayName}」和它的解析结果会一起删掉，删除后不能恢复。原件如果还在手机里，可以重新导入。",
            confirmText = "删除",
            danger = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                delete(toDelete.source.id)
            }
        )
    }

    LaunchedEffect(assignTarget?.source?.id) {
        if (assignTarget != null) {
            allProjects = withContext(Dispatchers.IO) {
                runCatching { c.projectRepo.active() }.getOrDefault(emptyList())
            }
        }
    }

    val target = assignTarget
    if (target != null) {
        AssignDialog(
            fileName = target.source.displayName,
            projects = allProjects,
            onDismiss = { assignTarget = null },
            onPick = { pid ->
                assignTarget = null
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        c.sourceRepo.setProject(target.source.id, pid)
                        c.projectRepo.touch(pid)
                    }
                }
            }
        )
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            hits = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(350)
        val r = withContext(Dispatchers.IO) {
            runCatching { c.retriever.search(q, projectId = null, crossProject = true, limit = 20) }
                .getOrDefault(emptyList())
        }
        hits = r
        searching = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("材料库", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜材料内容，比如「报名表」「报送截止」") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
            )

            if (query.trim().length < 2) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 30.dp)
                ) {
                    if (sources.isEmpty()) {
                        item {
                            EmptyState(
                                title = "还没有材料",
                                hint = "在微信里长按文件 → 分享 → 选本应用"
                            )
                        }
                    }
                    items(sources, key = { it.source.id }) { s ->
                        SourceRow(
                            s = s,
                            onClick = { onOpenPreview(s.source.id) },
                            onAssign = { assignTarget = s },
                            onReparse = { reparse(s.source.id) },
                            onDelete = { deleteTarget = s }
                        )
                    }
                }
            } else {
                if (searching) LoadingRow("正在检索…")
                else if (hits.isEmpty()) {
                    EmptyState(title = "没有找到", hint = "换个词试试，或者先在材料里导入相关通知")
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 30.dp)
                ) {
                    items(hits, key = { it.chunk.id }) { h ->
                        Surface(
                            onClick = { onOpenPreview(h.sourceId) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(13.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = buildString {
                                            append(h.sourceName)
                                            val p = h.chunk.pageNo
                                            if (p != null) append(" 第${p}页")
                                            append(" · 相关度 ")
                                            append((h.score * 100).toInt())
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    text = h.chunk.text.take(160),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    s: SourceWithProject,
    onClick: () -> Unit,
    onAssign: () -> Unit,
    onReparse: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.source.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!s.projectTitle.isNullOrBlank()) {
                        TagChip(s.projectTitle, MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.clickable(onClick = onAssign)
                        ) {
                            Text(
                                text = "未归类 · 归入工作",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = buildString {
                            append(when (s.source.status) {
                                SourceStatus.PARSED -> "已解析"
                                SourceStatus.FAILED -> "解析失败"
                                SourceStatus.PARSING -> "解析中"
                                else -> "待解析"
                            })
                            if (s.source.textChars > 0) append(" · ${s.source.textChars} 字")
                            append(" · ")
                            append(Format.date(s.source.importedAt))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
                // 失败原因：不说清楚「卡在哪一步」，用户没法决定是重试还是换个格式
                if (s.source.status == SourceStatus.FAILED && !s.source.error.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = s.source.error.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(
                        onClick = onReparse,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                    ) {
                        Text("重试解析", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            SourceActionsMenu(
                parsing = s.source.status == SourceStatus.PARSING,
                onReparse = onReparse,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun AssignDialog(
    fileName: String,
    projects: List<ProjectEntity>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("归入工作", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                if (projects.isEmpty()) {
                    Text(
                        "还没有工作。先到「工作」页新建一项，再回来归类。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(projects, key = { it.id }) { p ->
                            Text(
                                text = p.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(p.id) }
                                    .padding(vertical = 11.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
