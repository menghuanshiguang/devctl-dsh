// DSHConsole —— 用 DeepSeek 客户端同款技术栈（Jetpack Compose）重做的 UI
//
// 全部依赖版本取自逆向出的 DeepSeek Chat v2.6.1（APK 内 META-INF/*.version）：
//   androidx.compose.ui / foundation / material        1.11.4
//   androidx.compose.material3                         1.4.0
//   androidx.compose.material:material-icons-*         1.7.8
//   androidx.activity:activity-compose                 1.13.0
//   androidx.lifecycle:*                               2.10.0
//   androidx.navigation:navigation-compose             2.8.5
//   kotlinx-coroutines                                 1.10.2
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
