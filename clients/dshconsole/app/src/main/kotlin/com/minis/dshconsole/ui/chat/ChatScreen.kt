package com.minis.dshconsole.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import com.minis.dshconsole.ui.theme.DsMotion
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.minis.dshconsole.ui.DsStr
import com.minis.dshconsole.ui.components.DsChip
import com.minis.dshconsole.ui.components.DsCircleButton
import com.minis.dshconsole.ui.components.brush.ShimmerOverlay
import com.minis.dshconsole.ui.theme.DsRadius
import com.minis.dshconsole.ui.theme.DsSpacing
import com.minis.dshconsole.ui.theme.DsType
import com.minis.dshconsole.ui.theme.DshTheme

/*
 * 聊天页 —— 对应 DeepSeek 原包
 *   ui/pages/chat/ChatMessageFragment.kt
 *   ui/pages/chat/message/UserChatMessageCell.kt / UserChatMessageBubble.kt
 *   ui/pages/chat/message/AssistantFragmentGroup.kt
 *   ui/pages/chat/message/AssistantChatMessageFooter.kt
 *   ui/pages/chat/session/input/ChatInputActionBar.kt
 *   ui/markdown/MarkdownCodeBlockHeader.kt
 *
 * 空态按截图实测还原：白底 + 居中品牌蓝 logo + “嗨！今天想聊些什么？”22sp 粗体 +
 * 底部圆角输入卡（占位“发消息或按住说话” + 两个浅蓝胶囊 + ⊕ + 语音）。
 *
 * 渲染方式与原包一致：
 *   消息列表 = LazyColumn + Modifier.animateItem()
 *     —— 原包即 LazyLayoutAnimateItemElement(fadeInSpec, placementSpec, fadeOutSpec)
 *   流式期间正文按纯文本追加（字符只增不改 → 不触发整行重排），收尾再一次性解析 Markdown
 *     —— 与原包 _DSFMLexer 按增量区间解析同思路
 */

sealed interface ChatFragment {
    data class TextFragment(val text: String) : ChatFragment
    data class ReasoningFragment(val text: String) : ChatFragment
    data class ToolFragment(val name: String, val summary: String, val state: ToolState) : ChatFragment
}

enum class ToolState { Running, Ok, Error }

data class ChatMessage(
    val id: String,
    val fromUser: Boolean,
    val fragments: List<ChatFragment> = emptyList(),
    val streaming: Boolean = false,
)

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    deepThink: Boolean = false,
    webSearch: Boolean = false,
    onToggleThink: () -> Unit = {},
    onToggleSearch: () -> Unit = {},
) {
    var uploadOpen by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(DshTheme.p.bg).imePadding()) {
        if (messages.isEmpty()) {
            ChatWelcome(Modifier.weight(1f))
        } else {
            MessageList(messages, Modifier.weight(1f))
        }
        // 顺序与原版一致：输入卡在上、附件面板在下（截图对照修正）
        ChatInputCard(
            onSend = onSend,
            deepThink = deepThink,
            webSearch = webSearch,
            onToggleThink = onToggleThink,
            onToggleSearch = onToggleSearch,
            onToggleUpload = { uploadOpen = !uploadOpen },
        )
        AnimatedVisibility(
            visible = uploadOpen,
            enter = expandVertically(
                animationSpec = tween(DsMotion.normal),
                expandFrom = Alignment.Bottom,
            ) + fadeIn(tween(DsMotion.normal)),
            exit = shrinkVertically(
                animationSpec = tween(DsMotion.fast),
                shrinkTowards = Alignment.Bottom,
            ) + fadeOut(tween(DsMotion.fast)),
        ) {
            Column {
                Spacer(Modifier.height(DsSpacing.s3))
                UploadPanel(
                    attachedCount = 1,
                    onPickCamera = { uploadOpen = false },
                    onPickAlbum = { uploadOpen = false },
                    onPickFile = { uploadOpen = false },
                )
                Spacer(Modifier.height(DsSpacing.s2))
            }
        }
    }
}

// ---------------------------------------------------------------- 列表

@Composable
private fun MessageList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    var previousSize by remember { mutableStateOf(0) }

    // ★ 自动到底 ★
    // 之前用 messages.size / fragments.size 当 key —— 但 delta 只改【文本长度】，
    // 这两个数都不变，LaunchedEffect 不重跑，所以流式时永远不往下滚。
    // 改成盯「最后一条消息的总字符数」，每来一个 token 都会变。
    val lastLen = messages.lastOrNull()?.let { m ->
        m.fragments.sumOf { f ->
            when (f) {
                is ChatFragment.TextFragment -> f.text.length
                is ChatFragment.ReasoningFragment -> f.text.length
                is ChatFragment.ToolFragment -> f.summary.length
            }
        }
    } ?: 0

    // 用户往上翻了就别拽他回去（对应 OpenMinis 的 StreamFollowDeadband）
    val atBottom by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }

    // ★ 下标必须以 messages 为准，不能用 state.layoutInfo.totalItemsCount ★
    // totalItemsCount 是上一帧的旧值：tail 里先 clear() 再逐条 add，
    // 列表缩小的瞬间 scrollToItem(旧下标) 会踩空 →
    //   SnapshotStateList.get -> IndexOutOfBoundsException（崩溃栈就是这个）
    LaunchedEffect(messages.size, lastLen) {
        val last = messages.lastIndex
        if (last >= 0 && (atBottom || messages.size != previousSize)) {
            runCatching { state.scrollToItem(last) }
        }
        previousSize = messages.size
    }

    LazyColumn(
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = DsSpacing.screenH,
            vertical = DsSpacing.s4,
        ),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.s3),
    ) {
        items(messages, key = { it.id }) { msg ->
            Box(Modifier.fillMaxWidth().animateItem()) {
                if (msg.fromUser) UserCell(msg) else AssistantCell(msg)
            }
        }
    }
}

@Composable
private fun UserCell(msg: ChatMessage) {
    val text = msg.fragments.filterIsInstance<ChatFragment.TextFragment>().joinToString("") { it.text }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .fillMaxWidth(0.82f)
                .clip(
                    RoundedCornerShape(
                        topStart = DsRadius.bubble,
                        topEnd = DsRadius.bubble,
                        bottomStart = DsRadius.bubble,
                        bottomEnd = DsRadius.bubbleTail,
                    )
                )
                .background(DshTheme.p.userBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text, style = DsType.body, color = DshTheme.p.onUserBubble)
        }
    }
}

@Composable
private fun AssistantCell(msg: ChatMessage) {
    Column(Modifier.fillMaxWidth()) {
        val thinking = msg.fragments.filterIsInstance<ChatFragment.ReasoningFragment>()
        val tools = msg.fragments.filterIsInstance<ChatFragment.ToolFragment>()

        // ---- 思考块（独立容器，流式自动展开、收尾自动收起）----
        thinking.forEachIndexed { idx, f ->
            ThinkingBlock(
                key = msg.id + "#" + idx,
                content = f.text,
                isStreaming = msg.streaming && idx == thinking.lastIndex,
            )
            Spacer(Modifier.height(6.dp))
        }

        // ---- 工具行（每行一个 36dp 胶囊）----
        tools.forEach { t ->
            ToolCallPill(
                name = t.name,
                summary = t.summary,
                state = t.state,
                running = msg.streaming && t.state == ToolState.Running,
            )
            Spacer(Modifier.height(6.dp))
        }

        val body = msg.fragments.filterIsInstance<ChatFragment.TextFragment>().joinToString("") { it.text }
        if (body.isNotEmpty()) {
            MarkdownBody(body)
            if (!msg.streaming) {
                Spacer(Modifier.height(DsSpacing.s1))
                AssistantChatMessageFooter()
            }
        }
    }
}

/**
 * 思考块 —— 参考 OpenMinis 的 ChatAssistantMessageUI.kt ThinkingBlock：
 *   · 独立容器：#007AFF 6% 底 + 15% 描边 + 12dp 圆角，内边距 12/8
 *   · header：图标 + 「思考」+ 字符数 + chevron，只有 header 可点
 *   · 流式开始自动展开；流结束自动收起（用户手动收起过则不打扰）
 *   · 超长保护：只渲染尾部窗口，避免 Compose 每次测量整段
 */
@Composable
private fun ThinkingBlock(
    key: String,
    content: String,
    isStreaming: Boolean,
) {
    val thinkingBlue = Color(0xFF007AFF)
    var expanded by remember(key) { mutableStateOf(isStreaming) }
    var userCollapsed by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key, isStreaming) {
        if (isStreaming) {
            if (!userCollapsed) expanded = true
        } else {
            expanded = false
        }
    }

    val charCount = content.length
    val charLabel = if (charCount >= 1000) "${charCount / 1000}K" else "$charCount"

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(thinkingBlue.copy(alpha = 0.06f))
            .border(0.5.dp, thinkingBlue.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    expanded = !expanded
                    if (!expanded) userCollapsed = true else userCollapsed = false
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Psychology,
                null,
                tint = thinkingBlue,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("思考", style = DsType.trace, color = thinkingBlue)
            Spacer(Modifier.width(8.dp))
            Text(charLabel, style = DsType.trace, color = thinkingBlue.copy(alpha = 0.6f))
            Spacer(Modifier.weight(1f))
            if (isStreaming) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(thinkingBlue))
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                if (expanded) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                if (expanded) "收起" else "展开",
                tint = thinkingBlue.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            val shown = remember(charCount) {
                if (charCount > 3000) content.takeLast(3000) else content
            }
            Text(
                shown,
                style = DsType.trace,
                color = DshTheme.p.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 状态色：每种工具给一个强调色（对应 OpenMinis 的 toolAccentColor） */
private fun toolAccent(name: String): Color = when {
    name.contains("read", true) -> Color(0xFF34C759)
    name.contains("write", true) || name.contains("edit", true) -> Color(0xFFFF9F0A)
    name.contains("bash", true) || name.contains("exec", true) || name.contains("shell", true) ->
        Color(0xFFAF52DE)
    name.contains("search", true) || name.contains("grep", true) -> Color(0xFF007AFF)
    name.contains("web", true) -> Color(0xFF00C7BE)
    else -> Color(0xFF8E8E93)
}

/**
 * 工具调用胶囊 —— 参考 OpenMinis 的 ToolCallPill：
 * 单行、36dp 高、左侧状态点 + 工具名 + 摘要，运行中整行扫光。
 */
@Composable
private fun ToolCallPill(
    name: String,
    summary: String,
    state: ToolState,
    running: Boolean,
) {
    val accent = toolAccent(name)
    val failed = state == ToolState.Error
    val lineColor by animateColorAsState(
        when {
            failed -> DshTheme.p.danger
            running -> accent
            else -> DshTheme.p.textSecondary
        },
        label = "toolLine",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (failed) DshTheme.p.danger else accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            style = DsType.trace,
            color = if (failed) DshTheme.p.danger else accent,
            maxLines = 1,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            Text(
                summary,
                style = DsType.trace,
                color = lineColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (running) ShimmerOverlay()
        }
    }
}

// ---------------------------------------------------------------- 正文

@Composable
private fun MarkdownBody(text: String) {
    val p = DshTheme.p
    Column(Modifier.fillMaxWidth()) {
        remember(text) { splitFences(text) }.forEach { (lang, content) ->
            if (lang == null) {
                Text(inlineMarkdown(content), style = DsType.body, color = p.textPrimary)
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DsRadius.code))
                        .background(p.fill)
                        .border(1.dp, p.divider, RoundedCornerShape(DsRadius.code)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = DsSpacing.s3, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            lang.ifEmpty { "text" },
                            style = DsType.bodySmall,
                            color = p.textSecondary,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.weight(1f))
                        Text("复制", style = DsType.bodySmall, color = p.brand)
                    }
                    Text(
                        content.trimEnd('\n'),
                        style = DsType.code,
                        color = p.textPrimary,
                        modifier = Modifier.padding(DsSpacing.s3),
                    )
                }
            }
            Spacer(Modifier.height(DsSpacing.s2))
        }
    }
}

private fun splitFences(text: String): List<Pair<String?, String>> {
    val out = mutableListOf<Pair<String?, String>>()
    val lines = text.split('\n')
    var i = 0
    val plain = StringBuilder()
    while (i < lines.size) {
        val trimmed = lines[i].trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            if (plain.isNotEmpty()) {
                out += null to plain.toString()
                plain.clear()
            }
            val lang = trimmed.removePrefix("```").removePrefix("~~~").trim()
            val body = StringBuilder()
            i++
            while (i < lines.size &&
                !lines[i].trimStart().startsWith("```") &&
                !lines[i].trimStart().startsWith("~~~")
            ) {
                body.append(lines[i]).append('\n')
                i++
            }
            out += lang to body.toString()
        } else {
            plain.append(lines[i]).append('\n')
        }
        i++
    }
    if (plain.isNotEmpty()) out += null to plain.toString()
    return out
}

private fun inlineMarkdown(src: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val bold = SpanStyle(fontWeight = FontWeight.SemiBold)
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x14808080))
    while (i < src.length) {
        when {
            src.startsWith("**", i) -> {
                val end = src.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(bold) { append(src.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append(src[i]); i++
                }
            }
            src[i] == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(code) { append(src.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(src[i]); i++
                }
            }
            else -> {
                append(src[i]); i++
            }
        }
    }
}

// ---------------------------------------------------------------- 输入卡

@Composable
private fun ChatInputCard(
    onSend: (String) -> Unit,
    deepThink: Boolean,
    webSearch: Boolean,
    onToggleThink: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleUpload: () -> Unit,
) {
    val p = DshTheme.p
    var draft by remember { mutableStateOf("") }
    val canSend = draft.isNotBlank()
    // 原包两个占位：文字模式「发消息」/ 语音模式「发消息或按住说话」
    var focused by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.screenH, vertical = DsSpacing.s2)
            .shadow(10.dp, RoundedCornerShape(DsRadius.sheet), clip = false)
            .clip(RoundedCornerShape(DsRadius.sheet))
            .background(p.surface)
            .padding(horizontal = DsSpacing.s4, vertical = DsSpacing.s3),
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            if (draft.isEmpty()) {
                Text(
                    if (focused) DsStr.chatInputPlaceholderChat else DsStr.chatInputPlaceholderVoice,
                    style = DsType.body,
                    color = p.textPlaceholder,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = DsType.body.copy(color = p.textPrimary),
                cursorBrush = SolidColor(p.brand),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterStart)
                    .onFocusChanged { focused = it.isFocused },
            )
        }
        Spacer(Modifier.height(DsSpacing.s2))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧胶囊组：给 weight，Row 会先量右侧按钮，按钮尺寸不会被抢
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DsChip(
                    DsStr.messageR1Button,
                    icon = Icons.Filled.Psychology,
                    selected = deepThink,
                    onClick = onToggleThink,
                )
                Spacer(Modifier.width(DsSpacing.s2))
                DsChip(
                    DsStr.messageSearchButton,
                    icon = Icons.Filled.TravelExplore,
                    selected = webSearch,
                    onClick = onToggleSearch,
                )
            }
            Spacer(Modifier.width(DsSpacing.s2))
            // ⊕ 常驻；只有最右侧那个按钮在「语音 ↔ 发送」之间切换
            // （对照真机 1000052250 有文字时仍是 ⊕ + 蓝色↑，1000052251 无文字时是 ⊕ + 语音）
            DsCircleButton(Icons.Filled.Add, "更多", onToggleUpload)
            Spacer(Modifier.width(DsSpacing.s1))
            if (canSend) {
                DsCircleButton(
                    Icons.Filled.ArrowUpward, "发送",
                    { onSend(draft.trim()); draft = "" },
                    container = p.brand, tint = p.onBrand,
                )
            } else {
                DsCircleButton(Icons.Filled.GraphicEq, DsStr.voiceInputButton, {})
            }
        }
    }
}
