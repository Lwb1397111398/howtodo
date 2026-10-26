package com.howtodo.ui.knowledge

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.howtodo.ai.JsonExtract
import com.howtodo.ai.Prompts
import com.howtodo.data.db.AiRole
import com.howtodo.data.db.EntityScope
import com.howtodo.data.db.EntityType
import com.howtodo.data.db.KnowledgeEntity
import com.howtodo.ingest.ImageIngest
import com.howtodo.ui.common.ConfirmDialog
import com.howtodo.ui.common.container
import com.howtodo.ui.components.EmptyState
import com.howtodo.ui.components.TagChip
import com.howtodo.ui.theme.Semantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen() {
    val ctx = LocalContext.current
    val c = ctx.container()
    val scope = rememberCoroutineScope()

    val all by c.knowledgeRepo.observeAll().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<KnowledgeEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<KnowledgeEntity?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(3)
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                busy = "正在识别截图里的信息…"
                val result = withContext(Dispatchers.IO) { extractFromImages(uris, c) }
                busy = null
                result.fold(
                    onSuccess = { n -> if (n == 0) error = "没能从截图里提取到长期信息" },
                    onFailure = { e -> error = e.message ?: "识别失败" }
                )
            }
        }
    }

    val grouped = all.groupBy { it.type }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("全局知识", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "老师、办公室、上下班时间这类长期信息，所有工作共用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = "从截图识别")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAdd = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) { Icon(Icons.Filled.Add, contentDescription = "手动添加") }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 90.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(13.dp)) {
                        Text(
                            "这些信息会被自动用在材料分析里",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "比如记下「张老师 17:00 下班」，分析时 AI 就能推断出「材料要在 17:00 前送到办公室」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (all.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有全局知识",
                        hint = "点右上角的星星图标，把微信里的通讯录截图发进来试试"
                    )
                }
            }

            EntityType.ALL.forEach { type ->
                val list = grouped[type].orEmpty()
                if (list.isEmpty()) return@forEach
                item {
                    Text(
                        text = EntityType.label(type),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp)
                    )
                }
                items(list, key = { it.id }) { e ->
                    EntityCard(
                        entity = e,
                        onClick = { editing = e },
                        onDelete = { deleting = e }
                    )
                }
            }
        }
    }

    if (busy != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("请稍候") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(busy ?: "")
                }
            },
            confirmButton = {}
        )
    }

    if (error != null) {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("提示") },
            text = { Text(error ?: "") },
            confirmButton = { TextButton(onClick = { error = null }) { Text("好") } }
        )
    }

    if (showAdd || editing != null) {
        EntityEditor(
            entity = editing,
            onDismiss = { showAdd = false; editing = null },
            onSave = { e ->
                scope.launch {
                    c.knowledgeRepo.save(e.copy(verified = true, updatedAt = System.currentTimeMillis()))
                    showAdd = false
                    editing = null
                }
            }
        )
    }

    deleting?.let { e ->
        ConfirmDialog(
            title = "删除这条知识？",
            message = "「${e.name}」将从全局知识中移除，之后的分析不会再使用它。",
            confirmText = "删除",
            danger = true,
            onDismiss = { deleting = null },
            onConfirm = {
                scope.launch { c.knowledgeRepo.delete(e.id) }
                deleting = null
            }
        )
    }
}

@Composable
private fun EntityCard(
    entity: KnowledgeEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val attrs = remember(entity.attributes) { parseAttrs(entity.attributes) }
    val aliases = remember(entity.aliases) { parseList(entity.aliases) }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entity.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (!entity.verified) {
                        Spacer(Modifier.width(6.dp))
                        TagChip("待确认", Semantic.confirm)
                    }
                }
                if (aliases.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "别名：" + aliases.joinToString("、"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (attrs.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = attrs.entries.joinToString("　") { "${it.key}：${it.value}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (!entity.quote.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "依据：「${entity.quote}」",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = { menu = true }) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "更多",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onClick() })
                DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun EntityEditor(
    entity: KnowledgeEntity?,
    onDismiss: () -> Unit,
    onSave: (KnowledgeEntity) -> Unit
) {
    var name by remember { mutableStateOf(entity?.name.orEmpty()) }
    var type by remember { mutableStateOf(entity?.type ?: EntityType.PERSON) }
    var aliases by remember {
        mutableStateOf(entity?.let { parseList(it.aliases).joinToString("、") }.orEmpty())
    }
    var attrsText by remember {
        mutableStateOf(
            entity?.let { parseAttrs(it.attributes).entries.joinToString("\n") { e -> "${e.key}：${e.value}" } }
                .orEmpty()
        )
    }
    var summary by remember { mutableStateOf(entity?.summary.orEmpty()) }
    var typeMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entity == null) "添加知识" else "编辑知识") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称（如 张老师）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { typeMenu = true }) {
                        Text(EntityType.label(type), style = MaterialTheme.typography.labelSmall)
                    }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        EntityType.ALL.forEach { t ->
                            DropdownMenuItem(text = { Text(EntityType.label(t)) }, onClick = {
                                type = t; typeMenu = false
                            })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = aliases,
                    onValueChange = { aliases = it },
                    label = { Text("别名（用、分隔）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = attrsText,
                    onValueChange = { attrsText = it },
                    label = { Text("属性（每行一条，如 办公室：综合楼302）") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("一句话备注（可空）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val attrs = attrsText.lines()
                        .mapNotNull { line ->
                            val idx = line.indexOfAny(charArrayOf('：', ':'))
                            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                        }
                        .filter { it.first.isNotBlank() && it.second.isNotBlank() }
                        .toMap()
                    val aliasList = aliases.split('、', ',', '，', ' ')
                        .map { it.trim() }.filter { it.isNotBlank() }

                    onSave(
                        (entity ?: KnowledgeEntity(
                            id = UUID.randomUUID().toString().replace("-", ""),
                            name = name,
                            scope = EntityScope.GLOBAL
                        )).copy(
                            name = name.trim(),
                            type = type,
                            aliases = com.google.gson.Gson().toJson(aliasList),
                            attributes = com.google.gson.Gson().toJson(attrs),
                            summary = summary.trim().ifBlank { null }
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private suspend fun extractFromImages(
    uris: List<Uri>,
    c: com.howtodo.di.AppContainer
): Result<Int> {
    val images = ArrayList<com.howtodo.ai.LlmClient.Part.Image>()
    uris.forEach { uri ->
        val id = c.storage.newId()
        val copied = try {
            c.storage.copyFromUri(uri, id)
        } catch (_: com.howtodo.util.Storage.FileTooLargeException) {
            return@forEach
        } ?: return@forEach
        // 准备失败（读不出图）也要清掉已拷贝的临时副本，否则留下无记录的孤儿文件
        val prepared = ImageIngest.prepare(copied.file)
        if (prepared == null) {
            copied.file.delete()
            return@forEach
        }
        images.add(com.howtodo.ai.LlmClient.Part.Image(prepared.mime, prepared.base64))
        copied.file.delete()
    }
    if (images.isEmpty()) return Result.success(0)

    val res = c.router.vision(
        Prompts.ENTITY_SYSTEM,
        "请从这些截图里抽取长期有效的人物/地点/时间规则信息。",
        images,
        maxTokens = 2048
    )
    val text = when (res) {
        is com.howtodo.ai.LlmClient.Result.Ok -> res.value.text
        is com.howtodo.ai.LlmClient.Result.Err -> return Result.failure(IllegalStateException(res.error.message))
    }
    val obj = JsonExtract.objectFrom(text).json
        ?: return Result.failure(IllegalStateException("模型返回格式不对，请重试"))
    val arr = obj.getAsJsonArray("entities") ?: return Result.success(0)

    var n = 0
    arr.forEach { el ->
        val o = el.asJsonObject
        val name = o.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
        val type = o.get("type")?.takeIf { !it.isJsonNull }?.asString?.uppercase() ?: EntityType.OTHER
        val attrs = LinkedHashMap<String, String>()
        o.getAsJsonObject("attributes")?.entrySet()?.forEach { (k, v) ->
            if (!v.isJsonNull && v.asString.isNotBlank()) attrs[k] = v.asString
        }
        val aliasList = o.getAsJsonArray("aliases")?.mapNotNull { if (it.isJsonNull) null else it.asString }
            ?: emptyList()
        val summary = o.get("summary")?.takeIf { !it.isJsonNull }?.asString
        val quote = o.get("quote")?.takeIf { !it.isJsonNull }?.asString
        c.knowledgeRepo.merge(
            c.knowledgeRepo.newEntity(
                type = type,
                name = name,
                aliases = aliasList,
                attributes = attrs,
                summary = summary,
                scope = EntityScope.GLOBAL,
                confidence = 0.8,
                quote = quote
            )
        )
        n++
    }
    return Result.success(n)
}

private fun parseAttrs(json: String?): Map<String, String> = try {
    if (json.isNullOrBlank()) emptyMap()
    else com.google.gson.JsonParser.parseString(json).asJsonObject.entrySet()
        .associate { it.key to it.value.asString }
} catch (_: Throwable) {
    emptyMap()
}

private fun parseList(json: String?): List<String> = try {
    if (json.isNullOrBlank()) emptyList()
    else com.google.gson.JsonParser.parseString(json).asJsonArray
        .mapNotNull { if (it.isJsonNull) null else it.asString }
} catch (_: Throwable) {
    emptyList()
}
