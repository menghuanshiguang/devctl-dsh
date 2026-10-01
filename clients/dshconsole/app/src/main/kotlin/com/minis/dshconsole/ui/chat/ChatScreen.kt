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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.minis.dshconsole.ui.components.AppIconButton
import com.minis.dshconsole.ui.components.brush.ShimmerOverlay
import com.minis.dshconsole.ui.theme.AppTypography
import com.minis.dshconsole.ui.theme.DshTheme
import com.minis.dshconsole.ui.theme.Radii
import com.minis.dshconsole.ui.theme.Spacing

/*
 * 聊天页 —— 对应 DeepSeek 原包
 *   ui/pages/chat/ChatMessageFragment.kt
 *   ui/pages/chat/message/UserChatMessageCell.kt / UserChatMessageBubble.kt
 *   ui/pages/chat/message/AssistantFragmentGroup.kt
 *   ui/pages/chat/message/AssistantChatMessageFooter.kt
 *   ui/pages/chat/session/input/ChatInputActionBar.kt
 *   ui/markdown/MarkdownCodeBlockHeader.kt
 *
 * 渲染方式与原包一致：
 *   消息列表 = LazyColumn + Modifier.animateItem(...)
 *     —— 原包正是 LazyLayoutAnimateItemElement(fadeInSpec, placementSpec, fadeOutSpec)
 *   流式期间正文按纯文本追加（字符只增不改 → 不触发整行重排），收尾再一次性解析 Markdown
 *     —— 这与原包 _DSFMLexer 按 [start,end) 区间增量解析是同一思路
 */

// ---------------------------------------------------------------- 数据模型

/** 对应 domain.chat.model.completion.message.fragments.* */
sealed interface ChatFragment {
    data class TextFragment(val text: String) : ChatFragment
    data class ReasoningFragment(val text: String) : ChatFragment
    data class ToolFragment(
        val name: String,
        val summary: String,
        val state: ToolState,
    ) : ChatFragment
}

enum class ToolState { Running, Ok, Error }

data class ChatMessage(
    val id: String,
    val fromUser: Boolean,
    val fragments: List<ChatFragment> = emptyList(),
    val streaming: Boolean = false,
)

// ---------------------------------------------------------------- 页面

@Composable
fun ChatScreen(
    title: String,
    subtitle: String?,
    messages: List<ChatMessage>,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

    // 流式追加时每帧钉在底部（瞬时 scrollTo，不做平滑动画 —— 避免动画互相打断导致抖动）
    LaunchedEffect(messages.size, messages.lastOrNull()?.fragments?.size) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.scrollToItem(last)
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = Spacing.messageH,
                vertical = Spacing.s4,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.messageGap),
        ) {
            items(messages, key = { it.id }) { msg ->
                Box(Modifier.fillMaxWidth().animateItem()) {
                    if (msg.fromUser) UserMessageCell(msg) else AssistantMessageCell(msg)
                }
            }
        }
        ChatInputBar(
            value = draft,
            onValueChange = { draft = it },
            onSend = {
                val t = draft.trim()
                if (t.isNotEmpty()) {
                    onSend(t)
                    draft = ""
                }
            },
        )
    }
}

// ---------------------------------------------------------------- 用户气泡

/** 对应 UserChatMessageCell.kt / UserChatMessageBubble.kt */
@Composable
private fun UserMessageCell(msg: ChatMessage) {
    val text = msg.fragments.filterIsInstance<ChatFragment.TextFragment>().joinToString("") { it.text }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.82f)
                .clip(
                    RoundedCornerShape(
                        topStart = Radii.bubble,
                        topEnd = Radii.bubble,
                        bottomStart = Radii.bubble,
                        bottomEnd = Radii.bubbleTail,
                    )
                )
                .background(DshTheme.colors.userBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text,
                style = AppTypography.markdownBody,
                color = DshTheme.colors.onUserBubble,
            )
        }
    }
}

// ---------------------------------------------------------------- 助手消息

/** 对应 AssistantFragmentGroup.kt —— 思考/工具过程行 + 正文 */
@Composable
private fun AssistantMessageCell(msg: ChatMessage) {
    Column(Modifier.fillMaxWidth()) {
        val traces = msg.fragments.filter { it is ChatFragment.ReasoningFragment || it is ChatFragment.ToolFragment }
        if (traces.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.card))
                    .background(DshTheme.colors.traceBackground)
                    .padding(horizontal = Spacing.s3, vertical = Spacing.s2),
                verticalArrangement = Arrangement.spacedBy(Spacing.traceGap),
            ) {
                traces.forEach { f ->
                    when (f) {
                        is ChatFragment.ReasoningFragment ->
                            TraceRow(
                                icon = Icons.Filled.Psychology,
                                label = "思考",
                                summary = f.text.lineSequence().firstOrNull().orEmpty(),
                                running = msg.streaming,
                            )
                        is ChatFragment.ToolFragment ->
                            TraceRow(
                                icon = Icons.Filled.Build,
                                label = f.name,
                                summary = f.summary,
                                running = f.state == ToolState.Running,
                                failed = f.state == ToolState.Error,
                            )
                        else -> Unit
                    }
                }
            }
            Spacer(Modifier.height(Spacing.groupGap))
        }

        val body = msg.fragments.filterIsInstance<ChatFragment.TextFragment>().joinToString("") { it.text }
        if (body.isNotEmpty()) {
            MarkdownBody(body)
        }
    }
}

/**
 * 过程行（思考 / 工具）。
 * 折叠态就是一行摘要 —— 与原包 ReasoningRow / ToolRow 的行为一致：
 * 流式中摘要显示"最后一段已完成内容的首行"，结算后显示全文首行。
 */
@Composable
private fun TraceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    summary: String,
    running: Boolean,
    failed: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val summaryColor by animateColorAsState(
        when {
            failed -> DshTheme.colors.danger
            running -> scheme.onSurfaceVariant
            else -> scheme.onSurfaceVariant.copy(alpha = 0.75f)
        },
        label = "traceColor",
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(Spacing.s2))
        Text(label, style = AppTypography.traceSummary, color = scheme.onSurface, maxLines = 1)
        Spacer(Modifier.width(Spacing.s2))
        Box(Modifier.weight(1f)) {
            Text(
                summary,
                style = AppTypography.traceSummary,
                color = summaryColor,
                maxLines = 1,
            )
            if (running) ShimmerOverlay()
        }
        if (running) {
            Spacer(Modifier.width(Spacing.s2))
            ProgressDot()
        }
    }
}

/** 运行中的三点头（对应原包的运行指示） */
@Composable
private fun ProgressDot() {
    Box(
        Modifier
            .size(6.dp)
            .clip(RoundedCornerShape(Radii.pill))
            .background(MaterialTheme.colorScheme.primary)
    )
}

// ---------------------------------------------------------------- 正文

/**
 * 轻量 Markdown：行内 `code`、**粗体**、围栏代码块。
 * 与原包一样，代码块带一条"语言 + 复制"的头部横幅（MarkdownCodeBlockHeader.kt）。
 */
@Composable
private fun MarkdownBody(text: String) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        val blocks = remember(text) { splitFences(text) }
        blocks.forEach { (lang, content) ->
            if (lang == null) {
                Text(
                    inlineMarkdown(content),
                    style = AppTypography.markdownBody,
                    color = scheme.onSurface,
                )
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radii.code))
                        .background(DshTheme.colors.codeBackground)
                        .border(1.dp, scheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(Radii.code)),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.s3, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            lang.ifEmpty { "text" },
                            style = AppTypography.markdownBodySmall,
                            color = scheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "复制",
                            style = AppTypography.markdownBodySmall,
                            color = scheme.primary,
                        )
                    }
                    Text(
                        content.trimEnd('\n'),
                        style = AppTypography.code,
                        color = scheme.onSurface,
                        modifier = Modifier.padding(horizontal = Spacing.s3, vertical = Spacing.s3),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.s2))
        }
    }
}

private fun splitFences(text: String): List<Pair<String?, String>> {
    val out = mutableListOf<Pair<String?, String>>()
    val lines = text.split('\n')
    var i = 0
    val plain = StringBuilder()
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
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
            plain.append(line).append('\n')
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

// ---------------------------------------------------------------- 输入栏

/** 对应 ChatInputActionBar.kt —— 圆角输入卡片 + 右侧发送按钮 */
@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val enabled = value.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenH, vertical = Spacing.s2),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(Radii.input))
                .background(scheme.surfaceContainerHigh)
                .padding(horizontal = Spacing.s4, vertical = Spacing.s3),
        ) {
            if (value.isEmpty()) {
                Text(
                    "发消息…",
                    style = AppTypography.markdownBody,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = AppTypography.markdownBody.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(Spacing.s2))
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(Radii.pill))
                .background(if (enabled) scheme.primary else scheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            AppIconButton(
                icon = Icons.Filled.ArrowUpward,
                contentDescription = "发送",
                onClick = { if (enabled) onSend() },
                tint = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant,
            )
        }
    }
}

