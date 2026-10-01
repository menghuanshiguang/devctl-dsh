package com.minis.dshconsole

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minis.dshconsole.ui.chat.ChatFragment
import com.minis.dshconsole.ui.chat.ChatMessage
import com.minis.dshconsole.ui.chat.ChatScreen
import com.minis.dshconsole.ui.chat.ToolState
import com.minis.dshconsole.ui.components.AppIconButton
import com.minis.dshconsole.ui.components.SettingItem
import com.minis.dshconsole.ui.theme.AppTypography
import com.minis.dshconsole.ui.theme.DshTheme
import com.minis.dshconsole.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DSHConsole v2 —— UI 用 DeepSeek 客户端同款实现方式重做。
 *
 * 对应原包 com.deepseek.chat.MainActivity（Compose 单 Activity + 抽屉导航）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DshTheme { DshConsoleRoot() } }
    }
}

@Composable
private fun DshConsoleRoot() {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var sessionTitle by remember { mutableStateOf("新的会话") }

    // 首次进入放一条欢迎消息，便于观察排版
    var seeded by remember { mutableStateOf(false) }
    if (!seeded) {
        seeded = true
        messages.add(
            ChatMessage(
                id = "welcome",
                fromUser = false,
                fragments = listOf(
                    ChatFragment.TextFragment(
                        "DSH 客户端 UI 已切换到 **DeepSeek 同款实现方式**（Jetpack Compose）。\n\n" +
                            "```kotlin\n" +
                            "DshTheme {\n    ChatScreen(...)\n}\n" +
                            "```\n" +
                            "发一条消息试试流式渲染效果。"
                    )
                ),
            )
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { DshDrawer() },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            DshTopBar(
                title = sessionTitle,
                onMenu = { scope.launch { drawerState.open() } },
            )
            ChatScreen(
                title = sessionTitle,
                subtitle = null,
                messages = messages,
                onSend = { text ->
                    messages.add(
                        ChatMessage(
                            id = "u${messages.size}",
                            fromUser = true,
                            fragments = listOf(ChatFragment.TextFragment(text)),
                        )
                    )
                    if (sessionTitle == "新的会话") sessionTitle = text.take(12)
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
                        delay(600)
                        val idx = messages.lastIndex
                        val old = messages[idx]
                        messages[idx] = old.copy(
                            streaming = false,
                            fragments = listOf(
                                ChatFragment.ReasoningFragment("正在分析请求…"),
                                ChatFragment.ToolFragment("bash", "读取会话上下文", ToolState.Ok),
                                ChatFragment.TextFragment("收到：$text"),
                            ),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun DshTopBar(title: String, onMenu: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(scheme.surface)
            .height(56.dp)
            .padding(horizontal = Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconButton(Icons.Filled.Menu, "菜单", onMenu)
        Text(
            title,
            style = AppTypography.topBarTitle,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.s2),
        )
        AppIconButton(Icons.Filled.Add, "新会话", {})
        AppIconButton(Icons.Filled.MoreVert, "更多", {})
    }
    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
}

/** 对应原包 ChatNavigationDrawerContent.kt */
@Composable
private fun DshDrawer() {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(top = Spacing.s6)) {
            Text(
                "DSHConsole",
                style = AppTypography.topBarTitle,
                modifier = Modifier.padding(horizontal = Spacing.screenH, vertical = Spacing.s2),
            )
            Text(
                "devctl-dsh · 远端控制",
                style = AppTypography.markdownBodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.screenH),
            )
            Spacer(Modifier.height(Spacing.s4))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingItem(title = "会话", subtitle = "浏览与切换 DSH 会话")
            SettingItem(title = "设备", subtitle = "devctl 连接状态")
            SettingItem(title = "设置", subtitle = "主题 · 连接 · 关于")
            Spacer(Modifier.height(Spacing.s4))
        }
    }
}
