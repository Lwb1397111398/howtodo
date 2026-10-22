package com.howtodo.ui.settings

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.howtodo.data.db.ProviderEntity
import com.howtodo.data.db.ProviderModelEntity
import com.howtodo.ui.common.ConfirmDialog
import com.howtodo.ui.common.InputDialog
import com.howtodo.ui.common.container
import com.howtodo.ui.components.TagChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderScreen(providerId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container()
    val scope = rememberCoroutineScope()

    var id by remember { mutableStateOf<String?>(if (providerId == "new") null else providerId) }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("https://") }
    var apiKey by remember { mutableStateOf("") }
    var existingKeyMasked by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<ProviderModelEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showManual by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val pid = id ?: return@LaunchedEffect
        val p = withContext(Dispatchers.IO) { c.providerRepo.byId(pid) }
        if (p != null) {
            name = p.name
            baseUrl = p.baseUrl
            existingKeyMasked = com.howtodo.util.Crypto.mask(c.providerRepo.apiKeyOf(p))
            models = withContext(Dispatchers.IO) { c.providerRepo.models(pid) }
        }
    }

    fun save(thenFetch: Boolean) {
        scope.launch {
            loading = true
            val p = withContext(Dispatchers.IO) {
                c.providerRepo.save(id, name, baseUrl, apiKey.ifBlank { null })
            }
            id = p.id
            apiKey = ""
            existingKeyMasked = com.howtodo.util.Crypto.mask(c.providerRepo.apiKeyOf(p))
            if (thenFetch) {
                val key = c.providerRepo.apiKeyOf(p).orEmpty()
                val list = withContext(Dispatchers.IO) {
                    c.llmClient.listModels(p.baseUrl, key, c.providerRepo.extraHeadersOf(p))
                }
                withContext(Dispatchers.IO) { c.providerRepo.saveModels(p.id, list) }
                models = withContext(Dispatchers.IO) { c.providerRepo.models(p.id) }
                message = if (list.isEmpty()) {
                    "没有拉到模型列表（有些供应商不支持 /models），请手动添加模型名。"
                } else {
                    "已获取 ${list.size} 个模型"
                }
            } else {
                message = "已保存"
            }
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == null) "添加供应商" else "编辑供应商", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (id != null) {
                        TextButton(onClick = { showDelete = true }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
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
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（如 通义千问）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "填到 /v1 为止，例如 https://dashscope.aliyuncs.com/compatible-mode/v1",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = {
                        Text(existingKeyMasked ?: "sk-…")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Key 用系统密钥库加密后存在本机，不会上传。留空表示不修改已保存的 Key。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { save(true) },
                        enabled = !loading && name.isNotBlank() && baseUrl.length > 8,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (loading) "连接中…" else "保存并获取模型")
                    }
                    OutlinedButton(
                        onClick = { save(false) },
                        enabled = !loading && name.isNotBlank() && baseUrl.length > 8
                    ) { Text("仅保存") }
                }

                if (loading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "模型（${models.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (id != null) {
                        TextButton(onClick = { showManual = true }) { Text("手动添加") }
                    }
                }
            }

            items(models, key = { it.modelId }) { m ->
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            m.modelId,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (m.supportsVision) TagChip("视觉")
                    }
                }
            }

            if (models.isEmpty()) {
                item {
                    Text(
                        "还没有模型。点「保存并获取模型」自动拉取，或手动添加模型名。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                    )
                }
            }

            item { Spacer(Modifier.height(30.dp)) }
        }
    }

    if (showManual && id != null) {
        InputDialog(
            title = "手动添加模型",
            label = "模型 ID",
            hint = "例如 qwen-vl-max、deepseek-chat、doubao-1.5-vision-pro",
            onDismiss = { showManual = false },
            onConfirm = { modelId ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        c.providerRepo.addManualModel(id!!, modelId, 128000, 8192)
                    }
                    models = withContext(Dispatchers.IO) { c.providerRepo.models(id!!) }
                    showManual = false
                }
            }
        )
    }

    if (showDelete) {
        ConfirmDialog(
            title = "删除这个供应商？",
            message = "使用它的角色绑定会被清空，需要重新指定模型。",
            confirmText = "删除",
            danger = true,
            onDismiss = { showDelete = false },
            onConfirm = {
                scope.launch {
                    id?.let { withContext(Dispatchers.IO) { c.providerRepo.delete(it) } }
                    showDelete = false
                    onBack()
                }
            }
        )
    }

    if (message != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("提示") },
            text = { Text(message ?: "") },
            confirmButton = { TextButton(onClick = { message = null }) { Text("好") } }
        )
    }
}
