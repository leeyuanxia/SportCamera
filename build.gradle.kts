// 顶级构建文件 — 所有子项目/模块共享的配置
plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 已内置 Kotlin 支持，无需再单独应用 kotlin-android 插件
    alias(libs.plugins.kotlin.compose) apply false
}