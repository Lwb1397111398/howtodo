package com.howtodo.ui.common

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.howtodo.App
import com.howtodo.di.AppContainer

fun Context.container(): AppContainer = (applicationContext as App).container

/** 极简 ViewModel 工厂，省掉 Hilt 的注解处理开销 */
class VmFactory<T : ViewModel>(private val creator: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = creator() as VM
}
