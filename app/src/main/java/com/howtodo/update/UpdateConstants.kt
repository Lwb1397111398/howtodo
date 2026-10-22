package com.howtodo.update

/**
 * 自更新系统对外暴露的仓库常量。
 * 仓库定位方式与 CI 侧的 git remote 自动探测保持一致，避免两处维护。
 */
object UpdateConstants {
    const val REPO_OWNER = "Lwb1397111398"
    const val REPO_NAME = "howtodo"
}
