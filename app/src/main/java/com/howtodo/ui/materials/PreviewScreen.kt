package com.howtodo.ui.materials

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.howtodo.data.db.ChunkEntity
import com.howtodo.data.db.SourceEntity
import com.howtodo.data.db.SourceKind
import com.howtodo.data.db.SourceStatus
import com.howtodo.ingest.PdfIngest
import com.howtodo.ui.common.ConfirmDialog
import com.howtodo.ui.common.container
import com.howtodo.ui.components.SourceActionsMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(sourceId: String, highlightStart: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container()

    var source by remember { mutableStateOf<SourceEntity?>(null) }
    var chunks by remember { mutableStateOf<List<ChunkEntity>>(emptyList()) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pdfPage by remember { mutableStateOf(0) }
    var pdfCount by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reparse() {
        scope.launch {
            busy = "准备重新解析…"
            withContext(Dispatchers.IO) {
                runCatching { c.importManager.reparse(sourceId) { msg -> busy = msg } }
            }
            busy = null
            reload++
        }
    }

    LaunchedEffect(sourceId, reload) {
        loading = true
        chunks = emptyList()
        bitmap = null
        pdfPage = 0
        val s = withContext(Dispatchers.IO) { c.sourceRepo.byId(sourceId) }
        source = s
        if (s != null) {
            when (s.kind) {
                SourceKind.IMAGE -> {
                    bitmap = withContext(Dispatchers.IO) { decodeScaled(c.storage.materialFile(s.id), 1600) }
                }
                SourceKind.PDF -> {
                    val file = withContext(Dispatchers.IO) { c.storage.materialFile(s.id) }
                    pdfCount = withContext(Dispatchers.IO) { PdfIngest.pageCount(file) }.coerceAtLeast(1)
                    bitmap = withContext(Dispatchers.IO) { PdfIngest.renderOne(file, 0) }
                }
                else -> {
                    chunks = withContext(Dispatchers.IO) { c.db.chunkDao().bySource(s.id) }
                }
            }
        }
        loading = false
    }

    val listState = rememberLazyListState()
    LaunchedEffect(chunks, highlightStart) {
        if (chunks.isNotEmpty() && highlightStart > 0) {
            val idx = chunks.indexOfFirst { highlightStart in it.charStart..it.charEnd }
            if (idx >= 0) listState.scrollToItem(idx)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = source?.displayName ?: "材料",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (source?.kind == SourceKind.PDF) {
                        fun flipTo(page: Int) {
                            val s = source ?: return
                            scope.launch {
                                val f = c.storage.materialFile(s.id)
                                bitmap = withContext(Dispatchers.IO) { PdfIngest.renderOne(f, page) }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                if (pdfPage > 0) {
                                    pdfPage--
                                    flipTo(pdfPage)
                                }
                            }, enabled = pdfPage > 0) {
                                Icon(Icons.Outlined.ChevronLeft, contentDescription = "上一页")
                            }
                            Text(
                                "${pdfPage + 1}/$pdfCount",
                                style = MaterialTheme.typography.labelSmall
                            )
                            IconButton(onClick = {
                                if (pdfPage < pdfCount - 1) {
                                    pdfPage++
                                    flipTo(pdfPage)
                                }
                            }, enabled = pdfPage < pdfCount - 1) {
                                Icon(Icons.Outlined.ChevronRight, contentDescription = "下一页")
                            }
                        }
                    }
                    val s = source
                    if (s != null) {
                        SourceActionsMenu(
                            parsing = s.status == SourceStatus.PARSING,
                            onReparse = { reparse() },
                            onDelete = { showDelete = true }
                        )
                    }
                },
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
            val failed = source?.takeIf { it.status == SourceStatus.FAILED }
            if (!loading && failed != null) {
                FailedBanner(
                    error = failed.error,
                    retrying = busy != null,
                    onRetry = { reparse() }
                )
            }
            // 解析成功但有部分失败明细（某几页识别失败/超页截断）：如实告知，别让用户以为全文都在索引里
            val partial = source?.takeIf { it.status == SourceStatus.PARSED && !it.error.isNullOrBlank() }
            if (!loading && partial != null) {
                PartialBanner(note = partial.error.orEmpty())
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    source?.kind == SourceKind.IMAGE || source?.kind == SourceKind.PDF -> {
                        val bmp = bitmap
                        if (bmp == null) {
                            Text(
                                "无法显示这个文件",
                                modifier = Modifier.align(Alignment.Center),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp)
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(14.dp)
                        ) {
                            if (chunks.isEmpty()) {
                                item { Text("没有抽取到文字内容", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            items(chunks, key = { it.id }) { ch ->
                                val highlighted = highlightStart > 0 && highlightStart in ch.charStart..ch.charEnd
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        if (ch.pageNo != null) {
                                            Text(
                                                "第${ch.pageNo}页",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                            Spacer(Modifier.height(3.dp))
                                        }
                                        Text(
                                            text = ch.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (busy != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            title = { Text("正在重新解析") },
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

    if (showDelete) {
        ConfirmDialog(
            title = "删除这份材料？",
            message = "「${source?.displayName ?: "这份材料"}」和它的解析结果会一起删掉，删除后不能恢复。",
            confirmText = "删除",
            danger = true,
            onDismiss = { showDelete = false },
            onConfirm = {
                showDelete = false
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { c.sourceRepo.delete(sourceId) } }
                    onBack()
                }
            }
        )
    }
}

/** 部分识别失败横幅：材料已可用，但缺了某些页/某些部分 */
@Composable
private fun PartialBanner(note: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "⚠ 部分内容未识别",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 解析失败横幅：把失败原因摆在正文上方，并直接给一个重试按钮 */
@Composable
private fun FailedBanner(error: String?, retrying: Boolean, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = "这份材料没能解析成文字",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onRetry, enabled = !retrying) {
                    Text(if (retrying) "解析中…" else "重新解析")
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "反复失败时，多半是扫描件太糊或文件加密，可重拍或另存为 PDF 再导入。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun decodeScaled(file: java.io.File, maxDim: Int): Bitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxDim * 2 && bounds.outHeight / sample > maxDim * 2) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return try {
        BitmapFactory.decodeFile(file.absolutePath, opts)
    } catch (_: Throwable) {
        null
    }
}
