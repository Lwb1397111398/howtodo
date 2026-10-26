package com.howtodo.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.howtodo.update.UpdateManager
import com.howtodo.update.UpdateUiState

/**
 * 自更新弹窗宿主：挂在设置页顶层，所有状态由 UpdateManager.state 驱动，
 * 静默检查发现新版也会在这里弹出。
 */
@Composable
fun UpdateDialogHost(updateManager: UpdateManager) {
    val state by updateManager.state.collectAsState()

    when (val s = state) {
        is UpdateUiState.Checking -> AlertDialog(
            onDismissRequest = { updateManager.toIdle() },
            title = { Text("检查更新") },
            text = { Text("正在联网检查新版本…") },
            confirmButton = {}
        )

        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = { updateManager.toIdle() },
            title = { Text("发现新版本 ${s.info.versionName}") },
            text = {
                Column {
                    s.info.notes?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "下载约 ${"%.1f".format(s.info.apkSize / 1024f / 1024f)} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { updateManager.download(s.info) }) { Text("下载") }
            },
            dismissButton = {
                TextButton(onClick = { updateManager.dismiss(s.info) }) { Text("稍后") }
            }
        )

        is UpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("下载更新 ${s.info.versionName}") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(
                        progress = { s.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${s.percent}%")
                }
            },
            confirmButton = {}
        )

        is UpdateUiState.Downloaded -> AlertDialog(
            onDismissRequest = { updateManager.toIdle() },
            title = { Text("更新已下载") },
            text = { Text("点「安装」调起系统安装器覆盖安装新版本。用户数据不会丢失。") },
            confirmButton = {
                TextButton(onClick = { updateManager.installNow() }) { Text("安装") }
            },
            dismissButton = {
                TextButton(onClick = { updateManager.toIdle() }) { Text("暂不安装") }
            }
        )

        is UpdateUiState.Failed -> AlertDialog(
            onDismissRequest = { updateManager.toIdle() },
            title = { Text("检查更新未成功") },
            text = { Text(s.message) },
            confirmButton = {
                TextButton(onClick = { updateManager.checkNow() }) { Text("重试") }
            },
            dismissButton = {
                TextButton(onClick = { updateManager.toIdle() }) { Text("关闭") }
            }
        )

        is UpdateUiState.UpToDate -> AlertDialog(
            onDismissRequest = { updateManager.toIdle() },
            title = { Text("检查更新") },
            text = { Text(s.message) },
            confirmButton = {
                TextButton(onClick = { updateManager.toIdle() }) { Text("好") }
            }
        )

        else -> {}
    }
}
