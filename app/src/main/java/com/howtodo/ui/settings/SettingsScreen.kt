package com.howtodo.ui.settings

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.core.app.NotificationManagerCompat
import com.howtodo.BuildConfig
import com.howtodo.data.db.AiRole
import com.howtodo.data.db.ProviderEntity
import com.howtodo.data.db.RoleBindingEntity
import com.howtodo.data.repo.ReminderRepo
import com.howtodo.ui.common.ConfirmDialog
import com.howtodo.ui.common.container
import com.howtodo.ui.update.UpdateDialogHost
import com.howtodo.ui.components.TagChip
import com.howtodo.util.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenProvider: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container()
    val scope = rememberCoroutineScope()

    val providers by c.providerRepo.observeProviders().collectAsState(initial = emptyList())
    val bindings by c.providerRepo.observeBindings().collectAsState(initial = emptyList())

    var digestEnabled by remember { mutableStateOf(true) }
    var digestTime by remember { mutableStateOf(8 to 0) }
    var d3 by remember { mutableStateOf(true) }
    var d1 by remember { mutableStateOf(true) }
    var d0 by remember { mutableStateOf(true) }

    var editingRole by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var askRestart by remember { mutableStateOf(false) }
    var materialsKb by remember { mutableStateOf(0L) }
    var showTokenDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        digestEnabled = c.reminderRepo.digestEnabled()
        digestTime = c.reminderRepo.digestTime()
        d3 = c.reminderRepo.isOffsetEnabled(ReminderRepo.KEY_D3_ENABLED, true)
        d1 = c.reminderRepo.isOffsetEnabled(ReminderRepo.KEY_D1_ENABLED, true)
        d0 = c.reminderRepo.isOffsetEnabled(ReminderRepo.KEY_D0_ENABLED, true)
        materialsKb = withContext(Dispatchers.IO) { BackupManager(ctx).materialsSizeKb() }
    }

    var notificationsOn by remember { mutableStateOf(true) }
    var exactAlarmOn by remember { mutableStateOf(true) }
    var batteryOk by remember { mutableStateOf(true) }

    fun refreshPerms() {
        notificationsOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        exactAlarmOn = c.alarmScheduler.canScheduleExact()
        val pm = ctx.getSystemService(PowerManager::class.java)
        batteryOk = pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
    }
    LaunchedEffect(Unit) { refreshPerms() }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPerms() }

    val backup = remember { BackupManager(ctx) }
    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { u ->
        scope.launch {
            val r = withContext(Dispatchers.IO) { backup.exportTo(u) }
            message = r.message
        }
    } }
    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { u ->
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { c.db.close() }
                backup.importFrom(u)
            }
            message = r.message
            askRestart = r.ok
        }
    } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
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
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 40.dp)
        ) {
            // ---------------- 模型与角色
            item { SectionTitle("模型与角色") }
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        AiRole.ALL.forEachIndexed { i, role ->
                            val b = bindings.firstOrNull { it.role == role }
                            val provider = providers.firstOrNull { it.id == b?.providerId }
                            RoleRow(
                                label = AiRole.label(role),
                                value = if (b?.modelId != null) "${provider?.name ?: "?"} / ${b.modelId}" else "未配置",
                                configured = b?.modelId != null,
                                onClick = { editingRole = role }
                            )
                            if (i != AiRole.ALL.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 14.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "不同功能可以用不同模型：识图建议用视觉模型，推理用强模型，快速改写用便宜模型。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, top = 6.dp)
                )
            }

            item { SectionTitle("API 供应商") }
            items2(providers) { p ->
                ProviderRow(p, onClick = { onOpenProvider(p.id) })
            }
            item {
                AddRow("+ 添加供应商") { onOpenProvider("new") }
            }

            // ---------------- 提醒
            item { SectionTitle("提醒") }
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        SwitchRow(
                            title = "每日早报",
                            subtitle = "每天早上汇总今天到期、逾期、3 天内的事项",
                            checked = digestEnabled
                        ) { on ->
                            digestEnabled = on
                            scope.launch {
                                c.reminderRepo.setDigestEnabled(on)
                                withContext(Dispatchers.IO) {
                                    com.howtodo.notify.DailyDigestWorker.reschedule(ctx, ctx.applicationContext as com.howtodo.App)
                                }
                            }
                        }
                        if (digestEnabled) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        TimePickerDialog(
                                            ctx,
                                            { _, h, m ->
                                                digestTime = h to m
                                                scope.launch {
                                                    c.reminderRepo.setDigestTime(h, m)
                                                    withContext(Dispatchers.IO) {
                                                        com.howtodo.notify.DailyDigestWorker.reschedule(
                                                            ctx, ctx.applicationContext as com.howtodo.App
                                                        )
                                                    }
                                                }
                                            },
                                            digestTime.first, digestTime.second, true
                                        ).show()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("早报时间", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(
                                    "%02d:%02d".format(digestTime.first, digestTime.second),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SwitchRow("提前 3 天提醒", null, d3) { on ->
                            d3 = on
                            scope.launch {
                                c.reminderRepo.setOffsetEnabled(ReminderRepo.KEY_D3_ENABLED, on)
                                withContext(Dispatchers.IO) {
                                    c.reminderRepo.rescheduleAll()
                                    c.alarmScheduler.rescheduleAll()
                                }
                            }
                        }
                        SwitchRow("提前 1 天提醒", null, d1) { on ->
                            d1 = on
                            scope.launch {
                                c.reminderRepo.setOffsetEnabled(ReminderRepo.KEY_D1_ENABLED, on)
                                withContext(Dispatchers.IO) {
                                    c.reminderRepo.rescheduleAll()
                                    c.alarmScheduler.rescheduleAll()
                                }
                            }
                        }
                        SwitchRow("当天提醒", "有具体时刻的会提前 2 小时", d0) { on ->
                            d0 = on
                            scope.launch {
                                c.reminderRepo.setOffsetEnabled(ReminderRepo.KEY_D0_ENABLED, on)
                                withContext(Dispatchers.IO) {
                                    c.reminderRepo.rescheduleAll()
                                    c.alarmScheduler.rescheduleAll()
                                }
                            }
                        }
                    }
                }
            }

            // ---------------- 可靠性
            item { SectionTitle("通知可靠性") }
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        ActionRow(
                            title = "通知权限",
                            status = if (notificationsOn) "已允许" else "未允许（提醒不会弹出）",
                            ok = notificationsOn
                        ) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                )
                            }
                        }
                        ActionRow(
                            title = "精确闹钟",
                            status = if (exactAlarmOn) "已允许" else "未允许（提醒可能延后几分钟）",
                            ok = exactAlarmOn
                        ) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                            .setData(Uri.parse("package:${ctx.packageName}"))
                                    )
                                }
                            }
                        }
                        ActionRow(
                            title = "忽略电池优化",
                            status = if (batteryOk) "已加入白名单" else "未加入（国产 ROM 可能杀后台）",
                            ok = batteryOk
                        ) {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                        .setData(Uri.parse("package:${ctx.packageName}"))
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "小米/华为/OPPO 等系统还需在「设置 → 应用 → 自启动」里允许本应用自启动，否则重启后提醒会失效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, top = 6.dp)
                )
            }

            // ---------------- 数据
            item { SectionTitle("数据") }
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        ActionRow(
                            title = "导出备份",
                            status = "材料占用 ${materialsKb / 1024} MB",
                            ok = true
                        ) {
                            createDoc.launch("howtodo-backup-${System.currentTimeMillis()}.zip")
                        }
                        ActionRow(
                            title = "从备份恢复",
                            status = "会覆盖当前全部数据，请谨慎",
                            ok = false
                        ) {
                            openDoc.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        }
                    }
                }
            }

            item { SectionTitle("关于") }
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // 版本与自更新说明
                        Text("工作如何做  v" + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "材料只存在本机，API Key 用系统密钥库加密。所有 AI 变更都需要你确认才会生效。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        // 手动检查更新入口：联网查 GitHub Release，弹窗提示新版并引导下载安装
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { c.updateManager.checkNow() }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "检查更新",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

    editingRole?.let { role ->
        RoleBindDialog(
            role = role,
            providers = providers,
            current = bindings.firstOrNull { it.role == role },
            onDismiss = { editingRole = null },
            onPick = { providerId, modelId, budget ->
                scope.launch {
                    c.providerRepo.setBinding(role, providerId, modelId, contextBudget = budget)
                    editingRole = null
                }
            }
        )
    }

    if (showTokenDialog) {
        var tokenText by remember { mutableStateOf(c.updateManager.loadTokenPlain() ?: "") }
        AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            title = { Text("填写更新令牌") },
            text = {
                Column {
                    Text(
                        "仓库为私有仓库时，检查更新需要 GitHub 只读令牌（Fine-grained PAT，仅需 Contents 读取权限）。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tokenText,
                        onValueChange = { tokenText = it },
                        label = { Text("GitHub Personal Access Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    c.updateManager.saveToken(tokenText.trim())
                    showTokenDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showTokenDialog = false }) { Text("取消") } }
        )
    }

    UpdateDialogHost(c.updateManager)

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    message = null
                    if (askRestart) {
                        askRestart = false
                        (ctx as? android.app.Activity)?.finishAffinity()
                    }
                }) { Text("好") }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun RoleRow(label: String, value: String, configured: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                color = if (configured) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!configured) TagChip("必填", MaterialTheme.colorScheme.error)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ProviderRow(p: ProviderEntity, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    p.baseUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun AddRow(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionRow(title: String, status: String, ok: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
        }
        Text("去设置", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun RoleBindDialog(
    role: String,
    providers: List<ProviderEntity>,
    current: RoleBindingEntity?,
    onDismiss: () -> Unit,
    onPick: (providerId: String, modelId: String, budget: Int) -> Unit
) {
    val ctx = LocalContext.current
    val c = ctx.container()
    var selectedProvider by remember { mutableStateOf<ProviderEntity?>(null) }
    var models by remember { mutableStateOf<List<com.howtodo.data.db.ProviderModelEntity>>(emptyList()) }
    var budgetText by remember { mutableStateOf((current?.contextBudget ?: 32000).toString()) }

    LaunchedEffect(selectedProvider) {
        models = selectedProvider?.let {
            withContext(Dispatchers.IO) { c.providerRepo.models(it.id) }
        } ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("为「${AiRole.label(role)}」指定模型") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (providers.isEmpty()) {
                    Text(
                        "还没有供应商。请先在设置里添加一个 API 供应商。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text("供应商", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                providers.forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedProvider = p }
                            .background(
                                if (selectedProvider?.id == p.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (selectedProvider?.id == p.id) {
                            Text("已选", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                if (selectedProvider != null) {
                    Spacer(Modifier.height(12.dp))
                    Text("模型", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    if (models.isEmpty()) {
                        Text(
                            "这个供应商还没有拉取模型列表。进入供应商详情页点「获取模型列表」，或手填模型名。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    models.take(60).forEach { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(
                                        selectedProvider!!.id,
                                        m.modelId,
                                        budgetText.toIntOrNull() ?: 32000
                                    )
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
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
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("上下文预算", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = budgetText,
                            onValueChange = { budgetText = it.filter { ch -> ch.isDigit() }.take(7) },
                            singleLine = true,
                            modifier = Modifier.width(130.dp)
                        )
                    }
                    Text(
                        "可填到 200000。实际用量还受模型本身上限限制。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** LazyListScope 的小工具：避免在 item 块里写重复的 items 调用样板 */
private fun androidx.compose.foundation.lazy.LazyListScope.items2(
    providers: List<ProviderEntity>,
    row: @Composable (ProviderEntity) -> Unit
) {
    providers.forEach { p ->
        item(key = "provider_${p.id}") { row(p) }
    }
}
