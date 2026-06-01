package cn.leeyuanxia.sportcamera

import android.app.Application
import cn.leeyuanxia.sportcamera.di.AppContainer

/**
 * Application 入口 — 初始化 DI 容器
 */
class SportCameraApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContainer.initialize(this)
    }
}