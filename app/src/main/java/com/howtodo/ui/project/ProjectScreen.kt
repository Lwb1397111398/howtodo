package com.howtodo.ui.project

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.core.content.FileProvider
import com.howtodo.data.db.ProposalState
import com.howtodo.data.db.SourceKind
import com.howtodo.data.db.SourceOrigin
import com.howtodo.data.db.SourceStatus
import com.howtodo.data.db.SourceWithProject
import com.howtodo.data.db.TaskStatus
import com.howtodo.data.db.TaskWithProject
import com.howtodo.ui.common.ConfirmDialog
import com.howtodo.ui.common.InputDialog
import com.howtodo.ui.common.container
import com.howtodo.ui.components.DueChip
import com.howtodo.ui.components.EmptyState
import com.howtodo.ui.components.SectionHeader
import com.howtodo.ui.components.SourceActionsMenu
import com.howtodo.ui.components.TagChip
import com.howtodo.ui.components.TaskRow
import com.howtodo.ui.theme.Semantic
import com.howtodo.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenProposal: (String) -> Unit,
    onOpenPreview: (String) -> Unit,
    onOpenChat: (String) -> Unit
) {
    val ctx = LocalContext.current
    val c = ctx.container()
    val scope = rememberCoroutineScope()

    val projects by c.projectRepo.observeAll().collectAsState(initial = emptyList())
    val project = projects.firstOrNull { it.id == projectId }
    val tasks by remember(projectId) { c.taskRepo.observeByProject(projectId) }
        .collectAsState(initial = emptyList())
    val sources by remember(projectId) { c.sourceRepo.observeByProject(projectId) }
        .collectAsState(initial = emptyList())
    val proposals by remember(projectId) { c.proposalRepo.observeByProject(projectId) }
        .collectAsState(initial = emptyList())
    val analysis by c.analysisState.collectAsState()

    var tab by remember { mutableStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showAddTask by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaskWithProject?>(null) }

    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pasteText by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SourceWithProject?>(null) }

    fun reparseSource(id: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { c.importManager.reparse(id) }
        }
    }

    fun deleteSource(id: String) {
        selected = selected - id
        scope.launch(Dispatchers.IO) {
            runCatching { c.sourceRepo.delete(id) }
        }
    }

    // 默认选中"还没分析过"的材料
    LaunchedEffect(sources) {
        val pending = sources.filter {
            it.source.status == SourceStatus.PARSED && it.source.batchId == null
        }.map { it.source.id }.toSet()
        if (pending.isNotEmpty()) selected = selected + pending
    }

    val importingRef = remember { arrayOfNulls<Boolean>(1) }
    fun doImport(uris: List<Uri>, origin: String) {
        if (uris.isEmpty()) return
        scope.launch {
            busy = "正在导入 ${uris.size} 个文件…"
            val r = withContext(Dispatchers.IO) {
                c.importManager.importUris(uris, projectId, origin, autoParse = true)
            }
            busy = null
            if (r.imported == 0 && r.duplicated > 0) error = "这些材料之前已经导入过了"
            else if (r.errors.isNotEmpty()) error = r.errors.first()
            else if (r.imported > 0) {
                // 导入后自动勾选新材料
                selected = selected + r.sourceIds
                tab = 1
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        doImport(uris, SourceOrigin.PICKER)
    }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        doImport(uris, SourceOrigin.PICKER)
    }

    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) doImport(listOf(u), SourceOrigin.CAMERA)
    }

    fun takePhoto() {
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        cameraUri = uri
        cameraPicker.launch(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = project?.title ?: "工作",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val done = tasks.count { it.task.status == TaskStatus.DONE }
                        Text(
                            text = "${done}/${tasks.count { it.task.status != TaskStatus.CANCELLED }} 已完成 · ${sources.size} 份材料",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenChat(projectId) }) {
                        Icon(Icons.Outlined.Chat, contentDescription = "问一问")
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("重命名") }, onClick = {
                            showMenu = false; showRename = true
                        })
                        DropdownMenuItem(text = { Text("新增任务") }, onClick = {
                            showMenu = false; showAddTask = true
                        })
                        DropdownMenuItem(text = { Text("归档工作") }, onClick = {
                            showMenu = false; showArchive = true
                        })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showImport = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Outlined.Upload, contentDescription = "导入材料")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                listOf("任务", "材料", "变更").forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(label, style = MaterialTheme.typography.labelLarge) }
                    )
                }
            }

            when (tab) {
                0 -> TasksTab(
                    tasks = tasks,
                    onToggle = { id -> scope.launch { c.taskRepo.toggleDone(id) } },
                    onEdit = { editing = it },
                    onAdd = { showAddTask = true }
                )
                1 -> SourcesTab(
                    sources = sources,
                    selected = selected,
                    onToggleSelect = { id ->
                        selected = if (id in selected) selected - id else selected + id
                    },
                    onOpenPreview = onOpenPreview,
                    onAnalyze = { ids ->
                        if (ids.isEmpty()) {
                            error = "先勾选要分析的材料"
                        } else {
                            // 应用级作用域后台跑：中途离开页面分析不会断，进度经 c.analysisState 订阅回来
                            c.startAnalysis(projectId, ids.toList())
                        }
                    },
                    onImport = { showImport = true },
                    onReparse = { reparseSource(it) },
                    onDelete = { deleteTarget = it },
                    busy = busy != null
                )
                else -> ChangesTab(proposals = proposals, onOpen = onOpenProposal)
            }
        }
    }

    // ---------------------------------------------------------------- 弹窗

    if (showImport) {
        ImportSheet(
            onDismiss = { showImport = false },
            onFiles = { showImport = false; filePicker.launch(arrayOf("*/*")) },
            onImages = { showImport = false; imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onCamera = { showImport = false; takePhoto() },
            onText = { showImport = false; editing = null; pasteText = true }
        )
    }

    if (pasteText) {
        InputDialog(
            title = "粘贴文字",
            label = "把通知内容粘在这里",
            singleLine = false,
            hint = "适合从微信复制一段文字，不必截图",
            confirmText = "保存",
            onDismiss = { pasteText = false },
            onConfirm = { text ->
                scope.launch {
                    busy = "保存文字…"
                    val id = c.importManager.importText(text, "粘贴的通知", projectId)
                    // 解析涉及读文件/Zip/正则，全程留在 IO 线程，别卡主线程
                    withContext(Dispatchers.IO) {
                        c.importManager.parseOne(listOf(id)) { m -> busy = m }
                    }
                    busy = null
                    selected = selected + id
                    pasteText = false
                    tab = 1
                }
            }
        )
    }

    if (busy != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("请稍候") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(busy ?: "", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {}
        )
    }

    // 后台分析进度：跑在应用级作用域，可「后台运行」收起，离开页面也不会断
    val activeAnalysis = analysis?.takeIf {
        it.projectId == projectId && it.proposalId == null && it.error == null
    }
    if (activeAnalysis != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在分析") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(activeAnalysis.message, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { c.dismissAnalysis() }) { Text("后台运行") }
            }
        )
    }

    // 分析结束的两种收尾：成功跳提案页，失败进错误弹窗
    LaunchedEffect(analysis?.proposalId) {
        val pid = analysis?.proposalId ?: return@LaunchedEffect
        c.dismissAnalysis()
        selected = emptySet()
        onOpenProposal(pid)
    }
    LaunchedEffect(analysis?.error) {
        val e = analysis?.error ?: return@LaunchedEffect
        c.dismissAnalysis()
        error = e
    }

    if (error != null) {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("出错了") },
            text = { Text(error ?: "", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }
        )
    }

    if (showAddTask) {
        InputDialog(
            title = "新增任务",
            label = "要做什么",
            hint = "自己加的任务不会被动被 AI 改动",
            confirmText = "添加",
            onDismiss = { showAddTask = false },
            onConfirm = { title ->
                scope.launch {
                    val maxOrd = c.taskRepo.maxOrd(projectId)
                    c.taskRepo.insert(
                        com.howtodo.data.db.TaskEntity(
                            id = java.util.UUID.randomUUID().toString().replace("-", ""),
                            projectId = projectId,
                            extKey = "manual_" + System.currentTimeMillis(),
                            title = title,
                            status = TaskStatus.TODO,
                            origin = com.howtodo.data.db.TaskOrigin.MANUAL,
                            ord = maxOrd + 1
                        )
                    )
                    showAddTask = false
                }
            }
        )
    }

    if (showRename) {
        InputDialog(
            title = "重命名工作",
            label = "名称",
            initial = project?.title.orEmpty(),
            onDismiss = { showRename = false },
            onConfirm = { t ->
                scope.launch {
                    c.projectRepo.rename(projectId, t)
                    showRename = false
                }
            }
        )
    }

    if (showArchive) {
        ConfirmDialog(
            title = "归档这项工作？",
            message = "归档后不再出现在工作列表里，也不会再有提醒，但数据都还在。",
            confirmText = "归档",
            onDismiss = { showArchive = false },
            onConfirm = {
                scope.launch {
                    c.projectRepo.archive(projectId)
                    showArchive = false
                    onBack()
                }
            }
        )
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
                deleteSource(toDelete.source.id)
            }
        )
    }

    editing?.let { t ->
        TaskEditorDialog(
            task = t.task,
            onDismiss = { editing = null },
            onSave = { title, detail, dueAt, hasTime ->
                scope.launch {
                    c.taskRepo.update(
                        t.task.copy(
                            title = title,
                            detail = detail,
                            dueAt = dueAt,
                            dueHasTime = hasTime,
                            status = if (t.task.status == TaskStatus.NEEDS_CONFIRM && dueAt != null)
                                TaskStatus.TODO else t.task.status,
                            updatedAt = System.currentTimeMillis(),
                            rev = t.task.rev + 1
                        )
                    )
                    c.reminderRepo.rescheduleTask(
                        t.task.copy(dueAt = dueAt, dueHasTime = hasTime, status = TaskStatus.TODO)
                    )
                    c.alarmScheduler.rescheduleAll()
                    editing = null
                }
            },
            onDelete = {
                scope.launch {
                    c.taskRepo.setStatus(t.task.id, TaskStatus.CANCELLED)
                    editing = null
                }
            }
        )
    }
}

@Composable
private fun TasksTab(
    tasks: List<TaskWithProject>,
    onToggle: (String) -> Unit,
    onEdit: (TaskWithProject) -> Unit,
    onAdd: () -> Unit
) {
    val open = tasks.filter { it.task.status != TaskStatus.DONE && it.task.status != TaskStatus.CANCELLED }
    val done = tasks.filter { it.task.status == TaskStatus.DONE }
    val cancelled = tasks.filter { it.task.status == TaskStatus.CANCELLED }

    val confirm = open.filter { it.task.status == TaskStatus.NEEDS_CONFIRM }
    val overdue = open.filter {
        it.task.status == TaskStatus.TODO && it.task.dueAt != null && it.task.dueAt < System.currentTimeMillis()
    }
    val later = open.filter {
        it.task.status == TaskStatus.TODO && (it.task.dueAt == null || it.task.dueAt >= System.currentTimeMillis())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 100.dp)
    ) {
        if (open.isEmpty() && done.isEmpty()) {
            item {
                EmptyState(
                    title = "还没有任务",
                    hint = "到「材料」里导入通知，再用 AI 分析出任务"
                )
            }
        }
        if (confirm.isNotEmpty()) {
            item { SectionHeader("时间待确认", confirm.size) }
            items(confirm, key = { it.task.id }) { t -> taskRow(t, onToggle, onEdit) }
        }
        if (overdue.isNotEmpty()) {
            item { SectionHeader("已逾期", overdue.size) }
            items(overdue, key = { it.task.id }) { t -> taskRow(t, onToggle, onEdit) }
        }
        if (later.isNotEmpty()) {
            item { SectionHeader("待办", later.size) }
            items(later, key = { it.task.id }) { t -> taskRow(t, onToggle, onEdit) }
        }
        if (done.isNotEmpty()) {
            item { SectionHeader("已完成", done.size) }
            items(done, key = { it.task.id }) { t -> taskRow(t, onToggle, onEdit) }
        }
        if (cancelled.isNotEmpty()) {
            item { SectionHeader("已取消", cancelled.size) }
            items(cancelled, key = { it.task.id }) { t -> taskRow(t, onToggle, onEdit) }
        }
    }
}

@Composable
private fun taskRow(t: TaskWithProject, onToggle: (String) -> Unit, onEdit: (TaskWithProject) -> Unit) {
    TaskRow(
        title = t.task.title,
        dueAt = t.task.dueAt,
        hasTime = t.task.dueHasTime,
        status = t.task.status,
        detail = t.task.detail,
        assumption = t.task.assumption,
        onClick = { onEdit(t) },
        onToggle = { onToggle(t.task.id) }
    )
}

@Composable
private fun SourcesTab(
    sources: List<SourceWithProject>,
    selected: Set<String>,
    onToggleSelect: (String) -> Unit,
    onOpenPreview: (String) -> Unit,
    onAnalyze: (Set<String>) -> Unit,
    onImport: () -> Unit,
    onReparse: (String) -> Unit,
    onDelete: (SourceWithProject) -> Unit,
    busy: Boolean
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (selected.isEmpty()) "勾选要分析的材料" else "已选 ${selected.size} 份",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "首次分析生成清单；之后的材料只做增量更新",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = { onAnalyze(selected) },
                    enabled = !busy
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("AI 分析")
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 100.dp)
        ) {
            if (sources.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有材料",
                        hint = "点右下角导入，或在微信里长按文件选「分享」到本应用"
                    )
                }
            }
            items(sources, key = { it.source.id }) { s ->
                SourceRow(
                    s = s,
                    checked = s.source.id in selected,
                    onToggle = { onToggleSelect(s.source.id) },
                    onOpen = { onOpenPreview(s.source.id) },
                    onReparse = { onReparse(s.source.id) },
                    onDelete = { onDelete(s) }
                )
            }
        }
    }
}

@Composable
private fun SourceRow(
    s: SourceWithProject,
    checked: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onReparse: () -> Unit,
    onDelete: () -> Unit
) {
    val st = s.source.status
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.howtodo.ui.components.CheckCircle(checked = checked, onClick = onToggle)
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = when (s.source.kind) {
                    SourceKind.IMAGE -> Icons.Outlined.Description
                    else -> Icons.Outlined.Description
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.source.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickableRow(onOpen)
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusTag(st)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = buildString {
                            append(kindLabel(s.source.displayName, s.source.kind))
                            if (s.source.textChars > 0) append(" · ${s.source.textChars} 字")
                            if (s.source.pageCount > 1) append(" · ${s.source.pageCount} 页")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    if (s.source.batchId != null) {
                        Spacer(Modifier.width(6.dp))
                        TagChip("已分析")
                    }
                }
                if (st == SourceStatus.FAILED && !s.source.error.isNullOrBlank()) {
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
            IconButton(onClick = onOpen) {
                Icon(Icons.Outlined.Description, contentDescription = "预览", modifier = Modifier.size(18.dp))
            }
            SourceActionsMenu(
                parsing = st == SourceStatus.PARSING,
                onReparse = onReparse,
                onDelete = onDelete
            )
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

/**
 * 材料类型标签。kind 只是「文档族」（老 .doc 和 .docx 都记作 DOCX），
 * 展示给用户时按扩展名说人话，避免一行 "DOCX" 让人以为只支持新格式。
 */
private fun kindLabel(name: String, kind: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "doc", "docx", "docm", "dot", "dotx", "rtf", "wps" -> "Word"
        "odt" -> "ODT"
        "xls", "xlsx", "xlsm", "xlt", "xltx", "et", "csv" -> "表格"
        "ods" -> "ODS"
        "ppt", "pptx", "pptm", "pot", "potx", "dps" -> "PPT"
        "odp" -> "ODP"
        "pdf" -> "PDF"
        "htm", "html" -> "网页"
        "txt", "md", "json", "xml", "log" -> "文本"
        else -> when (kind) {
            SourceKind.IMAGE -> "图片"
            SourceKind.PDF -> "PDF"
            SourceKind.DOCX -> "Word"
            SourceKind.XLSX -> "表格"
            SourceKind.PPTX -> "PPT"
            SourceKind.TXT -> "文本"
            else -> kind
        }
    }
}

@Composable
private fun StatusTag(status: String) {
    val (text, color) = when (status) {
        SourceStatus.PARSED -> "已解析" to MaterialTheme.colorScheme.primary
        SourceStatus.PARSING -> "解析中" to MaterialTheme.colorScheme.secondary
        SourceStatus.FAILED -> "解析失败" to MaterialTheme.colorScheme.error
        SourceStatus.NEEDS_VISION -> "待识别" to MaterialTheme.colorScheme.secondary
        else -> "待解析" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    TagChip(text, color)
}

@Composable
private fun ChangesTab(proposals: List<com.howtodo.data.db.ProposalEntity>, onOpen: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 100.dp)
    ) {
        if (proposals.isEmpty()) {
            item { EmptyState(title = "还没有变更记录", hint = "每次 AI 分析都会在这里留下痕迹，可回看、可撤销") }
        }
        items(proposals, key = { it.id }) { p ->
            Surface(
                onClick = { onOpen(p.id) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
            ) {
                Row(
                    modifier = Modifier.padding(13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (p.state) {
                            ProposalState.PENDING -> Icons.Outlined.HourglassEmpty
                            ProposalState.APPLIED -> Icons.Outlined.CheckCircle
                            else -> Icons.Outlined.ErrorOutline
                        },
                        contentDescription = null,
                        tint = when (p.state) {
                            ProposalState.PENDING -> Semantic.confirm
                            ProposalState.APPLIED -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = p.summary ?: "分析结果",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stateText(p.state) + " · " + Format.dateTime(p.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun stateText(s: String): String = when (s) {
    ProposalState.PENDING -> "待确认"
    ProposalState.APPLIED -> "已应用"
    ProposalState.REJECTED -> "已拒绝"
    ProposalState.SUPERSEDED -> "已过期"
    else -> "部分应用"
}

@Composable
private fun ImportSheet(
    onDismiss: () -> Unit,
    onFiles: () -> Unit,
    onImages: () -> Unit,
    onCamera: () -> Unit,
    onText: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入材料") },
        text = {
            Column {
                Text(
                    "微信里的文件：长按 → 分享 → 选「工作如何做」即可，不必先存相册。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                ImportItem("从相册选图片（可多选）", onImages)
                ImportItem("从文件管理器选（PDF / Word / Excel / PPT，含旧版 .doc/.xls/.ppt）", onFiles)
                ImportItem("拍照", onCamera)
                ImportItem("粘贴文字", onText)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ImportItem(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp)
    )
}

@Composable
private fun TaskEditorDialog(
    task: com.howtodo.data.db.TaskEntity,
    onDismiss: () -> Unit,
    onSave: (title: String, detail: String?, dueAt: Long?, hasTime: Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(task.title) }
    var detail by remember { mutableStateOf(task.detail.orEmpty()) }
    var dueAt by remember { mutableStateOf(task.dueAt) }
    var hasTime by remember { mutableStateOf(task.dueHasTime) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑任务") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("任务") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = detail,
                    onValueChange = { detail = it },
                    label = { Text("说明（交给谁、有什么要求）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                Text("截止时间", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickDate("今天") { dueAt = atDate(LocalDate.now(), hasTime, dueAt); hasTime = false }
                    QuickDate("明天") { dueAt = atDate(LocalDate.now().plusDays(1), hasTime, dueAt); hasTime = false }
                    QuickDate("下周") { dueAt = atDate(LocalDate.now().plusDays(7), hasTime, dueAt); hasTime = false }
                    QuickDate("清除") { dueAt = null }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val base = dueAt?.let { millisToDate(it) } ?: LocalDate.now()
                        DatePickerDialog(
                            ctx,
                            { _, y, m, d ->
                                dueAt = LocalDate.of(y, m + 1, d)
                                    .atTime(if (hasTime) millisToTime(dueAt!!) else LocalTime.of(23, 59))
                                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            },
                            base.year, base.monthValue - 1, base.dayOfMonth
                        ).show()
                    }) { Text("选日期") }
                    OutlinedButton(onClick = {
                        val base = dueAt?.let { millisToTime(it) } ?: LocalTime.of(17, 0)
                        TimePickerDialog(
                            ctx,
                            { _, h, min ->
                                val d = dueAt?.let { millisToDate(it) } ?: LocalDate.now()
                                dueAt = d.atTime(h, min)
                                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                hasTime = true
                            },
                            base.hour, base.minute, true
                        ).show()
                    }) { Text("选时刻") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (dueAt == null) "未设置时间（会进「待确认」）"
                    else "截止：" + Format.dateTime(dueAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isNotBlank()) {
                    onSave(title.trim(), detail.trim().ifBlank { null }, dueAt, hasTime)
                }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun QuickDate(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun atDate(date: LocalDate, hasTime: Boolean, current: Long?): Long {
    val time = if (hasTime && current != null) millisToTime(current) else LocalTime.of(23, 59)
    return date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

private fun millisToDate(ms: Long): LocalDate =
    java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

private fun millisToTime(ms: Long): LocalTime =
    java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
