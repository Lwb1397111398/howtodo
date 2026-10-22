package com.howtodo.update

import android.content.Context
import com.howtodo.BuildConfig
import com.howtodo.util.Crypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自更新总调度：状态机 + 节流 + 缓存。
 *
 * autoCheck：24小时节流，静默后台查，发现新版弹弹窗；
 * checkNow：用户主动点「检查更新」，不受节流限制，结果全量告知。
 */
class UpdateManager(private val context: Context) {

    companion object {
        // 仓库名由 git remote 自动探测，避免与 CI 脚本重复维护
        // GitHub API 默认限流：匿名 60 次/时；带令牌 5000 次/时。
        private const val PREFS = "update_prefs"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_SKIP_CODE = "skip_code"
        private const val KEY_TOKEN_ENC = "token_enc"
        private const val THROTTLE_MILLIS = 24L * 60 * 60 * 1000
        private val TAG = UpdateManager::class.java.simpleName
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val checker = UpdateChecker(UpdateConstants.REPO_OWNER, UpdateConstants.REPO_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state

    private var autoAvailable: Boolean = false

    // ---------------- 令牌（AES-GCM 加密落盘，格式 iv:ct 皆是Base64） ----------------

    private fun loadToken(): String? {
        val raw = prefs.getString(KEY_TOKEN_ENC, null) ?: return null
        val parts = raw.split(":")
        if (parts.size != 2) return null
        return try {
            val iv = android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP)
            val ct = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
            Crypto.decrypt(ct, iv)
        } catch (_: Throwable) {
            null
        }
    }

    fun saveToken(token: String) {
        if (token.isBlank()) {
            prefs.edit().remove(KEY_TOKEN_ENC).apply()
            return
        }
        val (ct, iv) = Crypto.encrypt(token)
        val ivB64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
        val ctB64 = android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP)
        prefs.edit().putString(KEY_TOKEN_ENC, "$ivB64:$ctB64").apply()
    }

    fun loadTokenPlain(): String? = loadToken()

    // ---------------- 检查 ----------------

    /** 启动静默检查：有版本被用户「稍后」过则不再弹 */
    fun autoCheck() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < THROTTLE_MILLIS) return
        scope.launch {
            val r = checker.check(BuildConfig.VERSION_CODE, loadToken())
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            when (r) {
                is UpdateChecker.Result.Available -> {
                    if (r.info.versionCode != prefs.getInt(KEY_SKIP_CODE, -1)) {
                        autoAvailable = true
                        _state.value = UpdateUiState.Available(r.info)
                    }
                }
                else -> {
                    // 静默检查不打扰用户，失败/无更新都不改状态
                }
            }
        }
    }

    /** 手动检查：不受节流，清掉skip，结果无论好坏都上屏 */
    fun checkNow() {
        scope.launch {
            _state.value = UpdateUiState.Checking
            autoAvailable = false
            prefs.edit().remove(KEY_SKIP_CODE).apply()
            val r = checker.check(BuildConfig.VERSION_CODE, loadToken())
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            _state.value = when (r) {
                is UpdateChecker.Result.Available -> UpdateUiState.Available(r.info)
                is UpdateChecker.Result.NoUpdate ->
                    UpdateUiState.UpToDate("已是最新版本 v" + BuildConfig.VERSION_NAME)
                is UpdateChecker.Result.Unpublished ->
                    UpdateUiState.UpToDate(
                        "云端暂无发布：若仓库私有请在下方填只读令牌，或等CI发新版"
                    )
                is UpdateChecker.Result.NetworkError ->
                    UpdateUiState.Failed(null, r.message + "（可稍后重试，或开浏览器进仓库Release页手动下载）")
            }
        }
    }

    // ---------------- 下载与安装 ----------------

    fun download(info: UpdateInfo) {
        scope.launch {
            _state.value = UpdateUiState.Downloading(info, 0)
            val destDir = File(context.cacheDir, "updates")
            val result = withContext(Dispatchers.IO) {
                UpdateDownloader.download(info, destDir) { pct ->
                    _state.value = UpdateUiState.Downloading(info, pct)
                }
            }
            _state.value = when (result) {
                is UpdateDownloader.Result.Ok -> UpdateUiState.Downloaded(info, result.file)
                is UpdateDownloader.Result.Error -> UpdateUiState.Failed(info, result.message + "（可在设置里重试）")
            }
        }
    }

    fun installNow() {
        val s = _state.value
        if (s is UpdateUiState.Downloaded) {
            if (!UpdateInstaller.canRequestInstall(context)) {
                UpdateInstaller.requestInstallPermission(context)
            } else {
                UpdateInstaller.install(context, s.file)
            }
        }
    }

    fun toIdle() {
        if (_state.value is UpdateUiState.Downloaded) return
        if (_state.value is UpdateUiState.Downloading) return
        _state.value = UpdateUiState.Idle
    }
}
