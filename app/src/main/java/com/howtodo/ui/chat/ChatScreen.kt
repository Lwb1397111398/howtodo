package com.howtodo.ui.chat

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParser
import com.howtodo.data.db.MessageEntity
import com.howtodo.ui.common.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    projectId: String?,
    onBack: () -> Unit,
    onOpenPreview: (String, Int) -> Unit
) {
    val ctx = LocalContext.current
    val c = ctx.container()
    val scope = rememberCoroutineScope()

    var conversationId by remember { mutableStateOf<String?>(null) }
    var projectTitle by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var crossProject by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    LaunchedEffect(projectId) {
        if (projectId != null) {
            projectTitle = withContext(Dispatchers.IO) { c.projectRepo.byId(projectId)?.title }
        }
        // 续聊该范围最近的一段会话（历史不再每次进页面就被丢掉）；一段都没有才新建
        conversationId = withContext(Dispatchers.IO) {
            c.chatRepo.latestConversation(projectId)?.id
                ?: c.chatRepo.createConversation(
                    projectId,
                    projectTitle?.let { "关于「$it」的提问" } ?: "全局提问"
                ).id
        }
    }

    val messages by (conversationId?.let { id ->
        c.chatRepo.observeMessages(id)
    } ?: kotlinx.coroutines.flow.flowOf(emptyList<MessageEntity>()))
        .collectAsState(initial = emptyList())

    val conversations by c.chatRepo.observeConversations(projectId)
        .collectAsState(initial = emptyList())

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send() {
        val q = input.trim()
        val cid = conversationId ?: return
        if (q.isEmpty() || thinking) return
        input = ""
        thinking = true
        // 此刻的列表长度才是「是不是第一条」；等回答落库后再读 messages 已经刷新，会误判
        val isFirst = messages.size <= 1
        scope.launch {
            withContext(Dispatchers.IO) {
                c.chatRepo.append(cid, "user", q)
            }
            val history = withContext(Dispatchers.IO) { c.chatRepo.recentMessages(cid, 8) }
            val answer = withContext(Dispatchers.IO) {
                runCatching {
                    c.answerer.answer(q, projectId, crossProject, history.dropLast(1))
                }.getOrElse { e ->
                    com.howtodo.rag.Answerer.Answer(
                        text = "出错了：${e.message}",
                        citationsJson = "[]",
                        hitCount = 0,
                        model = null,
                        promptTokens = 0,
                        completionTokens = 0,
                        grounded = false
                    )
                }
            }
            withContext(Dispatchers.IO) {
                c.chatRepo.append(
                    cid, "assistant", answer.text, answer.citationsJson, answer.model,
                    answer.promptTokens, answer.completionTokens
                )
                if (isFirst) {
                    c.chatRepo.rename(cid, q.take(30))
                }
            }
            thinking = false
        }
    }

    fun openConversation(id: String) {
        showHistory = false
        conversationId = id
    }

    fun newConversation() {
        showHistory = false
        scope.launch {
            conversationId = withContext(Dispatchers.IO) {
                c.chatRepo.createConversation(
                    projectId,
                    projectTitle?.let { "关于「$it」的提问" } ?: "全局提问"
                ).id
            }
        }
    }

    fun deleteConversation(cv: com.howtodo.data.db.ConversationEntity) {
        scope.launch {
            withContext(Dispatchers.IO) { c.chatRepo.deleteConversation(cv.id) }
            if (cv.id == conversationId) {
                // 删的是正在聊的：切到剩下的最近一段，一段都没有就开新的
                val next = withContext(Dispatchers.IO) { c.chatRepo.latestConversation(projectId) }
                conversationId = next?.id ?: withContext(Dispatchers.IO) {
                    c.chatRepo.createConversation(
                        projectId,
                        projectTitle?.let { "关于「$it」的提问" } ?: "全局提问"
                    ).id
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("问一问", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = projectTitle?.let { "范围：$it" } ?: "范围：全部材料",
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
                actions = {
                    TextButton(onClick = { crossProject = !crossProject }) {
                        Text(
                            if (crossProject) "跨工作" else "本工作",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (crossProject) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Outlined.History, contentDescription = "聊天历史")
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
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(14.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    "可以直接问",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(6.dp))
                                listOf(
                                    "截止时间能不能延后？材料里怎么写的",
                                    "现场答辩需要准备什么",
                                    "签到表要交给谁",
                                    "遇到设备坏了怎么办"
                                ).forEach {
                                    Text(
                                        "· $it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "回答只依据你导入的材料，会标出来源；找不到依据会直说。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                }
                items(messages, key = { it.id }) { m ->
                    MessageBubble(m, onOpenPreview)
                }
                if (thinking) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "正在检索材料…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("问点什么…") },
                        maxLines = 4,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { send() },
                        enabled = input.isNotBlank() && !thinking
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (input.isNotBlank() && !thinking) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }

    if (showHistory) {
        HistoryDialog(
            conversations = conversations,
            currentId = conversationId,
            onOpen = ::openConversation,
            onDelete = ::deleteConversation,
            onNew = ::newConversation,
            onDismiss = { showHistory = false }
        )
    }
}

@Composable
private fun MessageBubble(m: MessageEntity, onOpenPreview: (String, Int) -> Unit) {
    val isUser = m.role == "user"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 14.dp, topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 14.dp
            ),
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = m.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface
                )
                if (!isUser) {
                    val citations = remember(m.citations) { parseCitations(m.citations) }
                    if (citations.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        citations.take(5).forEach { cit ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenPreview(cit.sourceId, cit.charStart) }
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "[${cit.label}]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = buildString {
                                        append(cit.sourceName)
                                        if (cit.pageNo != null) append(" 第${cit.pageNo}页")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
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

private data class Cit(
    val label: String,
    val sourceId: String,
    val sourceName: String,
    val pageNo: Int?,
    val charStart: Int
)

private fun parseCitations(json: String?): List<Cit> = try {
    if (json.isNullOrBlank()) emptyList()
    else JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
        val o = el.asJsonObject
        val sid = o.get("sourceId")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
        Cit(
            label = o.get("label")?.takeIf { !it.isJsonNull }?.asString ?: "S",
            sourceId = sid,
            sourceName = o.get("sourceName")?.takeIf { !it.isJsonNull }?.asString ?: "材料",
            pageNo = o.get("pageNo")?.takeIf { !it.isJsonNull }?.asInt,
            charStart = o.get("charStart")?.takeIf { !it.isJsonNull }?.asInt ?: -1
        )
    }
} catch (_: Throwable) {
    emptyList()
}

@Composable
private fun HistoryDialog(
    conversations: List<com.howtodo.data.db.ConversationEntity>,
    currentId: String?,
    onOpen: (String) -> Unit,
    onDelete: (com.howtodo.data.db.ConversationEntity) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("聊天历史") },
        text = {
            LazyColumn(modifier = Modifier.height(340.dp)) {
                if (conversations.isEmpty()) {
                    item {
                        Text(
                            "还没有历史会话",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(conversations, key = { it.id }) { cv ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(cv.id) }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = cv.title + if (cv.id == currentId) "（当前）" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = com.howtodo.util.Format.dateTime(cv.updatedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onDelete(cv) }, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "删除会话",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNew) { Text("新对话") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
