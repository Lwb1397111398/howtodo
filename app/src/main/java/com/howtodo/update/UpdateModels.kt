package com.howtodo.update

import java.io.File

/** 云端最新版信息（解析 Release 元数据所得） */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSize: Long,
    val notes: String?,
    /** 查询成功所走的通道前缀（"" = 直连），下载优先复用同一条通道 */
    val sourcePrefix: String
)

sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    data class Available(val info: UpdateInfo) : UpdateUiState()
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateUiState()
    data class Downloaded(val info: UpdateInfo, val file: File) : UpdateUiState()
    data class Failed(val info: UpdateInfo?, val message: String) : UpdateUiState()
    data class UpToDate(val message: String) : UpdateUiState()
}
