package com.howtodo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.howtodo.data.db.ProjectEntity
import com.howtodo.data.db.SourceOrigin
import com.howtodo.di.AppContainer
import com.howtodo.ui.common.InputDialog
import com.howtodo.ui.theme.HowTodoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 从微信 / QQ / 文件管理器「分享」或「用其他应用打开」进来的落地页。
 *
 * 关键点：**先把字节复制到私有目录，再让用户选工作**。
 * 分享进来的是 content:// Uri，读权限随宿主 Activity 结束而失效，
 * 所以复制必须在 Activity 还活着的时候完成，不能丢给后台任务就 finish。
 * 复制完成后才展示「归到哪项工作」，此时文件已经在磁盘上，选多久都不会丢。
 */
class ShareActivity : ComponentActivity() {

    private val container: AppContainer by lazy { (application as App).container }

    private var state by mutableStateOf<ShareUiState>(ShareUiState.Copying)
    private var projects by mutableStateOf<List<ProjectEntity>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = collectUris(intent)
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()

        setContent {
            HowTodoTheme {
                ShareSheet(
                    state = state,
                    projects = projects,
                    onPickProject = { pid, title -> finishWithImport(pid, title) },
                    onKeepInLibrary = { finishWithImport(null, null) },
                    onCreateProject = { title -> createAndImport(title) },
                    onCancel = { finish() }
                )
            }
        }

        reloadProjects()

        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) { runCatching { stage(uris, sharedText) } }
            staged.onSuccess { s ->
                state = when {
                    s.ids.isNotEmpty() -> ShareUiState.Picking(s.ids, s.names, s.duplicated, s.failed)
                    s.failed > 0 -> ShareUiState.Error("文件读取失败。可以试试从相册或文件管理器再导入一次。")
                    else -> ShareUiState.Error("没有收到可导入的内容。")
                }
            }.onFailure { e ->
                state = ShareUiState.Error(e.message?.take(140) ?: "导入失败")
            }
        }
    }

    // ---------- 数据 ----------

    private data class Staged(
        val ids: List<String>,
        val names: List<String>,
        val duplicated: Int,
        val failed: Int
    )

    /** 只落盘 + 建档，不做解析：解析要调视觉模型，不该卡住这个透明页 */
    private suspend fun stage(uris: List<Uri>, text: String): Staged {
        if (uris.isEmpty()) {
            if (text.isBlank()) return Staged(emptyList(), emptyList(), 0, 0)
            val id = container.importManager.importText(text, "分享的文字", null)
            return Staged(listOf(id), listOf("分享的文字"), 0, 0)
        }
        val out = container.importManager.importUris(
            uris = uris,
            projectId = null,
            origin = SourceOrigin.SHARE,
            autoParse = false
        )
        val names = container.sourceRepo.byIds(out.sourceIds).map { it.displayName }
        return Staged(out.sourceIds, names, out.duplicated, out.failed)
    }

    private fun reloadProjects() {
        lifecycleScope.launch {
            projects = withContext(Dispatchers.IO) {
                runCatching { container.projectRepo.active() }.getOrDefault(emptyList())
            }
        }
    }

    private fun createAndImport(title: String) {
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) {
                runCatching { container.projectRepo.create(title) }.getOrNull()
            }
            if (p == null) {
                Toast.makeText(this@ShareActivity, "创建工作失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            finishWithImport(p.id, p.title)
        }
    }

    /**
     * 归位 + 交给后台解析。此处文件已经在本机，可以安全地 fire-and-forget。
     * 解析必须在 setProject 之后，否则 chunk 上的 projectId 会是空的。
     */
    private fun finishWithImport(projectId: String?, projectTitle: String?) {
        val ids = (state as? ShareUiState.Picking)?.ids.orEmpty()
        val msg = if (projectId == null) {
            "已存入材料库"
        } else {
            "已导入「${projectTitle.orEmpty()}」，正在识别内容"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

        if (ids.isNotEmpty()) {
            container.appScope.launch {
                if (projectId != null) {
                    ids.forEach { runCatching { container.sourceRepo.setProject(it, projectId) } }
                    runCatching { container.projectRepo.touch(projectId) }
                }
                runCatching { container.importManager.parseOne(ids) {} }
            }
        }
        finish()
    }

    private fun collectUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamOf(intent))
            Intent.ACTION_SEND_MULTIPLE -> streamsOf(intent)
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
    }

    private fun streamOf(intent: Intent): Uri? =
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)

    private fun streamsOf(intent: Intent): List<Uri> =
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            .orEmpty()
            .filterNotNull()
}

private sealed interface ShareUiState {
    data object Copying : ShareUiState
    data class Picking(
        val ids: List<String>,
        val names: List<String>,
        val duplicated: Int,
        val failed: Int
    ) : ShareUiState

    data class Error(val message: String) : ShareUiState
}

// ---------- UI ----------

@Composable
private fun ShareSheet(
    state: ShareUiState,
    projects: List<ProjectEntity>,
    onPickProject: (String, String) -> Unit,
    onKeepInLibrary: () -> Unit,
    onCreateProject: (String) -> Unit,
    onCancel: () -> Unit
) {
    var askingNewProject by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                when (state) {
                    ShareUiState.Copying -> CopyingBlock()
                    is ShareUiState.Error -> ErrorBlock(state.message, onCancel)
                    is ShareUiState.Picking -> PickingBlock(
                        state = state,
                        projects = projects,
                        onPickProject = onPickProject,
                        onKeepInLibrary = onKeepInLibrary,
                        onCreateProject = { askingNewProject = true }
                    )
                }
            }
        }
    }

    if (askingNewProject) {
        InputDialog(
            title = "新建工作",
            label = "这项工作是什么",
            hint = "例如：秋季运动会组织、教研组公开课",
            confirmText = "创建并导入",
            onDismiss = { askingNewProject = false },
            onConfirm = onCreateProject
        )
    }
}

@Composable
private fun CopyingBlock() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text("正在保存文件…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorBlock(message: String, onCancel: () -> Unit) {
    Text("没能导入", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text("关闭") }
    }
}

@Composable
private fun PickingBlock(
    state: ShareUiState.Picking,
    projects: List<ProjectEntity>,
    onPickProject: (String, String) -> Unit,
    onKeepInLibrary: () -> Unit,
    onCreateProject: () -> Unit
) {
    val count = state.ids.size
    Text("已收到 $count 个文件", style = MaterialTheme.typography.titleMedium)

    val names = state.names.joinToString("、")
    if (names.isNotBlank()) {
        Spacer(Modifier.height(3.dp))
        Text(
            names,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (state.failed > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            "${state.failed} 个文件没能读取，其余已保存",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (state.duplicated > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            "${state.duplicated} 个文件此前已导入过，直接复用",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    Spacer(Modifier.height(10.dp))
    Text(
        "归到哪项工作？",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )

    LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
        items(projects, key = { it.id }) { p ->
            PickerRow(
                icon = Icons.Outlined.Workspaces,
                title = p.title,
                subtitle = null
            ) { onPickProject(p.id, p.title) }
        }
        item {
            PickerRow(
                icon = Icons.Outlined.Add,
                title = "新建工作…",
                subtitle = null,
                onClick = onCreateProject
            )
        }
        item {
            PickerRow(
                icon = Icons.Outlined.Inventory2,
                title = "先放进材料库",
                subtitle = "稍后在工作里归类",
                onClick = onKeepInLibrary
            )
        }
    }
}

@Composable
private fun PickerRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
