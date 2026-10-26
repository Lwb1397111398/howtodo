package com.howtodo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.howtodo.ui.nav.AppNav
import com.howtodo.ui.theme.HowTodoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** 点通知进来时，要自动打开的那项工作 */
    private var startProjectId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeAskNotificationPermission()
        setContent {
            HowTodoTheme {
                AppNav(
                    startProjectId = startProjectId,
                    onStartConsumed = { startProjectId = null }
                )
            }
        }
        consumeIntent(intent)
    }

    /**
     * 首启引导通知权限（Android 13+ 是运行时权限）：只问一次，拒绝不纠缠，
     * 拒绝后由设置页与今日页的常驻提示承接（到点提醒弹不出来要说清楚）。
     */
    private fun maybeAskNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("notif_perm_asked", false)) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        prefs.edit().putBoolean("notif_perm_asked", true).apply()
        if (!granted) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    /** 「今天截止：xxx」这类通知点进来，直接落到对应工作，省一次翻找 */
    private fun consumeIntent(intent: Intent?) {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        if (taskId.isNullOrBlank()) return
        intent.removeExtra(EXTRA_TASK_ID)
        val container = (application as App).container
        container.appScope.launch {
            val task = runCatching { container.db.taskDao().byId(taskId) }.getOrNull() ?: return@launch
            // Compose 状态只在主线程写：appScope 是 IO 调度器，直接赋值属于越线程写 UI
            withContext(Dispatchers.Main) {
                startProjectId = task.projectId
            }
        }
    }

    private companion object {
        const val EXTRA_TASK_ID = "taskId"
    }
}
