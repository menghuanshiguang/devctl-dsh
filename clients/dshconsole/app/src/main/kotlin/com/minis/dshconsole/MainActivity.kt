package com.minis.dshconsole

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.minis.dshconsole.ui.sessions.ChatNavigationDrawerContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.minis.dshconsole.protocol.DshController
import com.minis.dshconsole.ui.chat.ChatFragment
import com.minis.dshconsole.ui.chat.ChatMessage
import com.minis.dshconsole.ui.chat.ChatScreen
import com.minis.dshconsole.ui.chat.ToolState
import com.minis.dshconsole.ui.components.DsCircleButton
import com.minis.dshconsole.ui.components.DsDrawerLayout
import com.minis.dshconsole.ui.components.DsRow
import com.minis.dshconsole.ui.components.DsRowDivider
import com.minis.dshconsole.ui.components.DsTopBar
import com.minis.dshconsole.ui.sessions.SessionListScreen
import com.minis.dshconsole.ui.settings.DeviceSetupScreen
import com.minis.dshconsole.ui.settings.SettingsScreen
import com.minis.dshconsole.ui.theme.DsSpacing
import com.minis.dshconsole.ui.theme.DsType
import com.minis.dshconsole.ui.theme.DshTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DSHConsole v2 —— UI 用 DeepSeek 客户端同款实现方式重做。
 *
 * 对应原包 com.deepseek.chat.MainActivity：Compose 单 Activity + 抽屉导航。
 * 三个页面（聊天 / 会话列表 / 设置）的配色与尺寸均按真机截图实测还原。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DshTheme { Root() } }
    }
}

private const val NEW_SESSION = "新的会话"

private enum class Screen { Chat, Sessions, Settings, Setup }

@Composable
private fun Root() {
    
    val scope = rememberCoroutineScope()
    var drawerOpen by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(Screen.Chat) }
    var sessionTitle by remember { mutableStateOf(NEW_SESSION) }
    var deepThink by remember { mutableStateOf(false) }
    var webSearch by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    val controller = remember { DshController(ctx.applicationContext) }
    val messages = controller.messages

    // 进来就尝试连上已保存的第一台设备；没配过就直接进配置页
    var needSetup by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val dev = controller.firstDevice()
        if (dev == null) {
            needSetup = true
            screen = Screen.Setup
        } else {
            controller.connect(dev)
        }
    }

    val sessionTitles = controller.sessionTitles
    val sessionGroups = remember(sessionTitles.toList()) {
        if (sessionTitles.isEmpty()) emptyList()
        else listOf("会话" to sessionTitles.toList())
    }

    BackHandler(enabled = screen != Screen.Chat) { screen = Screen.Chat }

    DsDrawerLayout(
        open = drawerOpen,
        onOpen = { drawerOpen = true },
        onClose = { drawerOpen = false },
        drawerContent = {
            ChatNavigationDrawerContent(
                groups = sessionGroups,
                accountName = "faerydewiee",
                selectedTitle = sessionTitle,
                selectedGroup = "今天",
                onOpenSession = { t ->
                    sessionTitle = t
                    controller.openSession(t)
                    screen = Screen.Chat
                    drawerOpen = false
                },
                onOpenAccountMenu = {
                    screen = Screen.Settings
                    drawerOpen = false
                },
            )
        },
    ) {
        AnimatedContent(
            targetState = screen,
            // 对应原包 ui/components/menu/ParallaxContent.kt:126 ParallaxAnimatedContent
            // 参数取自 smali zz7.smali 的常量表：spring(dampingRatio = 1.0f, stiffness = 1400f)
            // 行为：新页面从右滑入整屏，旧页面只左移 1/4 屏 —— 这就是「视差」
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val spec = spring<IntOffset>(dampingRatio = 1.0f, stiffness = 1400f)
                val enter = slideInHorizontally(spec) { w -> if (forward) w else -w / 4 } + fadeIn(
                    animationSpec = tween(180)
                )
                val exit = slideOutHorizontally(spec) { w -> if (forward) -w / 4 else w } + fadeOut(
                    animationSpec = tween(120)
                )
                enter togetherWith exit
            },
            label = "screen",
            modifier = Modifier
                .fillMaxSize()
                .background(DshTheme.p.bg)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) { s ->
            when (s) {
                Screen.Setup -> DeviceSetupScreen(
                    initial = controller.firstDevice(),
                    status = controller.status,
                    connected = controller.connected,
                    onBack = if (needSetup) null else ({ screen = Screen.Chat }),
                    onSaveAndConnect = { dev ->
                        needSetup = false
                        controller.saveAndConnect(dev)
                        screen = Screen.Chat
                    },
                )

                Screen.Settings -> SettingsScreen(
                    onBack = { screen = Screen.Chat },
                    onOpenDeviceSetup = { screen = Screen.Setup },
                    connectionStatus = controller.status,
                )

                Screen.Sessions -> SessionListScreen(
                    groups = sessionGroups,
                    accountName = "faerydewiee",
                    onOpenSession = { t ->
                        sessionTitle = t
                        screen = Screen.Chat
                    },
                    onOpenSettings = { screen = Screen.Settings },
                )

                Screen.Chat -> Column(Modifier.fillMaxSize()) {
                    DsTopBar(
                        // 原版在“新会话”状态下顶栏不显示标题（截图 1000052233 印证）
                        title = if (sessionTitle == NEW_SESSION) "" else sessionTitle,
                        left = {
                            DsCircleButton(Icons.Filled.Menu, "菜单", {
                                drawerOpen = true
                            })
                        },
                        right = {
                            DsCircleButton(Icons.Filled.GraphicEq, "朗读", {})
                            DsCircleButton(Icons.Filled.Add, "新会话", {
                                messages.clear()
                                sessionTitle = NEW_SESSION
                            })
                        },
                    )
                    ChatScreen(
                        messages = messages,
                        deepThink = deepThink,
                        webSearch = webSearch,
                        onToggleThink = { deepThink = !deepThink },
                        onToggleSearch = { webSearch = !webSearch },
                        onSend = { text -> controller.send(text) },
                    )
                }
            }
        }
    }
}

