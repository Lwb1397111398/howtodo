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
