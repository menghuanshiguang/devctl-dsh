package com.minis.dshconsole

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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
import com.minis.dshconsole.ui.chat.ChatFragment
import com.minis.dshconsole.ui.chat.ChatMessage
import com.minis.dshconsole.ui.chat.ChatScreen
import com.minis.dshconsole.ui.chat.ToolState
import com.minis.dshconsole.ui.components.DsCircleButton
import com.minis.dshconsole.ui.components.DsRow
import com.minis.dshconsole.ui.components.DsRowDivider
import com.minis.dshconsole.ui.components.DsTopBar
import com.minis.dshconsole.ui.sessions.SessionListScreen
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

private enum class Screen { Chat, Sessions, Settings }

@Composable
private fun Root() {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.Chat) }
    var sessionTitle by remember { mutableStateOf(NEW_SESSION) }
    var deepThink by remember { mutableStateOf(false) }
    var webSearch by remember { mutableStateOf(false) }

    val messages = remember { mutableStateListOf<ChatMessage>() }

    val sessionGroups = remember {
        listOf(
            "7 天内" to listOf("工具调用配对报错", "ProcessGovernor补丁验证"),
            "30 天内" to listOf("雷霆战机游戏代码", "你好", "你好", "你好", "申请理由范文", "LSA阻止加载DLL"),
            "2026年8月" to listOf("海阔天空1992原稿", "打招呼问候"),
        )
    }

    BackHandler(enabled = screen != Screen.Chat) { screen = Screen.Chat }

    ModalNavigationDrawer(
        drawerState = drawer,
        // 抽屉形状：只圆「末端」两角（对应 M3 DrawerDefaults.shape / 原包常量 16dp）
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RoundedCornerShape(
                    topEnd = 16.dp,
                    bottomEnd = 16.dp,
                ),
                drawerContainerColor = DshTheme.p.surface,
                modifier = Modifier.fillMaxWidth(0.82f),
            ) {
                ChatNavigationDrawerContent(
                    groups = sessionGroups,
                    accountName = "faerydewiee",
                    onOpenSession = { t ->
                        sessionTitle = t
                        screen = Screen.Chat
                        scope.launch { drawer.close() }
                    },
                    onOpenAccountMenu = {
                        screen = Screen.Settings
                        scope.launch { drawer.close() }
                    },
                )
            }
        },
    ) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
            modifier = Modifier
                .fillMaxSize()
                .background(DshTheme.p.bg)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) { s ->
            when (s) {
                Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Chat })

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
                                scope.launch { drawer.open() }
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
                        onSend = { text ->
                            messages.add(
                                ChatMessage(
                                    id = "u${messages.size}",
                                    fromUser = true,
                                    fragments = listOf(ChatFragment.TextFragment(text)),
                                )
                            )
                            if (sessionTitle == NEW_SESSION) sessionTitle = text.take(12)
                            messages.add(
                                ChatMessage(
                                    id = "a${messages.size}",
                                    fromUser = false,
                                    streaming = true,
                                    fragments = listOf(
                                        ChatFragment.ReasoningFragment("正在分析请求…"),
                                        ChatFragment.ToolFragment("bash", "读取会话上下文", ToolState.Running),
                                    ),
                                )
                            )
                            scope.launch {
                                delay(800)
                                val i = messages.lastIndex
                                if (i >= 0 && !messages[i].fromUser) {
                                    messages[i] = messages[i].copy(
                                        streaming = false,
                                        fragments = listOf(
                                            ChatFragment.ReasoningFragment("正在分析请求…"),
                                            ChatFragment.ToolFragment("bash", "读取会话上下文", ToolState.Ok),
                                            ChatFragment.TextFragment("收到：$text"),
                                        ),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

