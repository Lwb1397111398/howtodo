package com.howtodo.ui.common

import android.content.Context
import com.howtodo.App
import com.howtodo.di.AppContainer

fun Context.container(): AppContainer = (applicationContext as App).container
