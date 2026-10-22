package com.howtodo.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.FolderOpen
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
import com.howtodo.data.db.ProjectStatus
import com.howtodo.ui.common.InputDialog
import com.howtodo.ui.common.container
import com.howtodo.ui.components.EmptyState
import com.howtodo.ui.components.ProgressRing
import com.howtodo.ui.components.SectionHeader
import com.howtodo.util.Format
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onOpenProject: (String) -> Unit,
    onOpenMaterials: () -> Unit
) {
    val ctx = LocalContext.current
    val c = ctx.container()
    val scope = rememberCoroutineScope()

    val projects by c.projectRepo.observeAll().collectAsState(initial = emptyList())
    val counts by c.db.taskViewDao().observeProjectCounts().collectAsState(initial = emptyList())
    val openTasks by c.projectRepo.observeOpenTasks().collectAsState(initial = emptyList())

    var showNew by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }

    val active = projects.filter { it.status == ProjectStatus.ACTIVE }
    val archived = projects.filter { it.status == ProjectStatus.ARCHIVED }
    val countMap = counts.associateBy { it.projectId }
    val overdueMap = openTasks.filter {
        it.task.dueAt != null && it.task.dueAt < System.currentTimeMillis()
    }.groupBy { it.task.projectId }.mapValues { it.value.size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工作", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onOpenMaterials) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "材料库")
                    }
                    if (archived.isNotEmpty()) {
                        IconButton(onClick = { showArchived = !showArchived }) {
                            Icon(Icons.Outlined.Archive, contentDescription = "已归档")
                        }
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
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 90.dp)
        ) {
            if (active.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有工作",
                        hint = "把一次活动、一次检查、一份通知建成一个工作，材料都丢进去"
                    )
                }
            }

            items(active, key = { it.id }) { p ->
                ProjectCard(
                    project = p,
                    done = countMap[p.id]?.done ?: 0,
                    total = countMap[p.id]?.total ?: 0,
                    overdue = overdueMap[p.id] ?: 0,
                    onClick = { onOpenProject(p.id) }
                )
            }

            if (showArchived && archived.isNotEmpty()) {
                item { SectionHeader("已归档", archived.size) }
                items(archived, key = { it.id }) { p ->
                    Surface(
                        onClick = { scope.launch { c.projectRepo.unarchive(p.id) } },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = p.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "恢复",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNew) {
        InputDialog(
            title = "新建工作",
            label = "这项工作叫什么",
            hint = "留空也可以，分析材料后会自动命名",
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
private fun ProjectCard(
    project: ProjectEntity,
    done: Int,
    total: Int,
    overdue: Int,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (project.pinned) {
                        Text(
                            "★ ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Text(
                        text = project.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (total == 0) "还没有任务" else "$done/$total 已完成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (overdue > 0) {
                        Text(
                            text = "$overdue 项逾期",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "更新于 " + Format.date(project.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            Spacer(Modifier.width(10.dp))
            ProgressRing(done = done, total = total, size = 46)
        }
    }
}
