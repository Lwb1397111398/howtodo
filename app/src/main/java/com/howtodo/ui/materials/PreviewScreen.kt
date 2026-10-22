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
import com.howtodo.ingest.PdfIngest
import com.howtodo.ui.common.container
import kotlinx.coroutines.Dispatchers
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

    LaunchedEffect(sourceId) {
        loading = true
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                if (pdfPage > 0) {
                                    pdfPage--
                                    val f = c.storage.materialFile(source!!.id)
                                    bitmap = PdfIngest.renderOne(f, pdfPage)
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
                                    val f = c.storage.materialFile(source!!.id)
                                    bitmap = PdfIngest.renderOne(f, pdfPage)
                                }
                            }, enabled = pdfPage < pdfCount - 1) {
                                Icon(Icons.Outlined.ChevronRight, contentDescription = "下一页")
                            }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
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
