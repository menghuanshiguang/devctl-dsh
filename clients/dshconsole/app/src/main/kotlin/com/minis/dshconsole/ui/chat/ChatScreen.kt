package com.minis.dshconsole.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
        if (uploadOpen) {
            UploadPanel(
                attachedCount = 1,
                onPickCamera = { uploadOpen = false },
                onPickAlbum = { uploadOpen = false },
                onPickFile = { uploadOpen = false },
            )
            Spacer(Modifier.height(DsSpacing.s2))
        }
        ChatInputCard(
            onSend = onSend,
            deepThink = deepThink,
            webSearch = webSearch,
            onToggleThink = onToggleThink,
            onToggleSearch = onToggleSearch,
            onToggleUpload = { uploadOpen = !uploadOpen },
        )
    }
}

// ---------------------------------------------------------------- 列表

@Composable
private fun MessageList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.fragments?.size) {
        val last = state.layoutInfo.totalItemsCount - 1
        if (last >= 0) state.scrollToItem(last)
    }
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = DsSpacing.screenH,
            vertical = DsSpacing.s4,
        ),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.s4),
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
        val traces = msg.fragments.filter {
            it is ChatFragment.ReasoningFragment || it is ChatFragment.ToolFragment
        }
        if (traces.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(DsRadius.card))
                    .background(DshTheme.p.fill)
                    .padding(horizontal = DsSpacing.s3, vertical = DsSpacing.s2),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                traces.forEach { f ->
                    when (f) {
                        is ChatFragment.ReasoningFragment -> TraceRow(
                            Icons.Filled.Psychology, "思考",
                            f.text.lineSequence().firstOrNull().orEmpty(), msg.streaming,
                        )
                        is ChatFragment.ToolFragment -> TraceRow(
                            Icons.Filled.Build, f.name, f.summary,
                            f.state == ToolState.Running, f.state == ToolState.Error,
                        )
                        else -> Unit
                    }
                }
            }
            Spacer(Modifier.height(DsSpacing.s3))
        }
        val body = msg.fragments.filterIsInstance<ChatFragment.TextFragment>().joinToString("") { it.text }
        if (body.isNotEmpty()) MarkdownBody(body)
    }
}

@Composable
private fun TraceRow(
    icon: ImageVector,
    label: String,
    summary: String,
    running: Boolean,
    failed: Boolean = false,
) {
    val p = DshTheme.p
    val color by animateColorAsState(
        when {
            failed -> p.danger
            running -> p.textSecondary
            else -> p.textSecondary.copy(alpha = 0.75f)
        },
        label = "trace",
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = p.textSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(DsSpacing.s2))
        Text(label, style = DsType.trace, color = p.textPrimary, maxLines = 1)
        Spacer(Modifier.width(DsSpacing.s2))
        Box(Modifier.weight(1f)) {
            Text(summary, style = DsType.trace, color = color, maxLines = 1)
            if (running) ShimmerOverlay()
        }
        if (running) {
            Spacer(Modifier.width(DsSpacing.s2))
            Box(Modifier.size(6.dp).clip(CircleShape).background(p.brand))
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

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.screenH, vertical = DsSpacing.s2)
            .clip(RoundedCornerShape(DsRadius.sheet))
            .background(p.surface)
            .padding(horizontal = DsSpacing.s4, vertical = DsSpacing.s3),
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            if (draft.isEmpty()) {
                Text(
                    DsStr.chatInputPlaceholderVoice,
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
                modifier = Modifier.fillMaxWidth().align(Alignment.CenterStart),
            )
        }
        Spacer(Modifier.height(DsSpacing.s2))
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            Spacer(Modifier.weight(1f))
            if (canSend) {
                DsCircleButton(
                    Icons.Filled.ArrowUpward, "发送",
                    { onSend(draft.trim()); draft = "" },
                    container = p.brand, tint = p.onBrand,
                )
            } else {
                DsCircleButton(Icons.Filled.Add, "更多", onToggleUpload)
                Spacer(Modifier.width(DsSpacing.s1))
                DsCircleButton(Icons.Filled.GraphicEq, DsStr.voiceInputButton, {})
            }
        }
    }
}
