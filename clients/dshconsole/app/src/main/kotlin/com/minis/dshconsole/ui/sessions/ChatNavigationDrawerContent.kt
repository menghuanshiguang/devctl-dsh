package com.minis.dshconsole.ui.sessions

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.minis.dshconsole.ui.theme.DsRadius
import com.minis.dshconsole.protocol.DshController
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material.icons.filled.ExpandMore
import com.minis.dshconsole.ui.DsStr
import com.minis.dshconsole.ui.components.DsSearchBar
import com.minis.dshconsole.ui.theme.DsSpacing
import com.minis.dshconsole.ui.theme.DsType
import com.minis.dshconsole.ui.theme.DshTheme

/*
 * 侧边栏内容 —— 对应 DeepSeek 原包
 *   ui/pages/chat/drawer/ChatNavigationDrawerContent.kt
 *   ChatSessionList.kt / SessionGroupHeader.kt / ChatSessionItem.kt / ChatSessionListFooter.kt
 *   FullTextSearchBar.kt
 *
 * 层级（截图实测，这个层级不能省）：
 *   L0  搜索框            52dp 全圆角 #F5F5F5，占位「搜索对话内容...」
 *   L1  分组头            13sp #8F9094，右侧一个排序图标（仅当前分组显示）
 *        └ 置顶 / 7 天内 / 30 天内 / 2026年8月 …
 *   L2  会话条目          16sp #0F0F0F，行高约 56dp，左内边距 18dp
 *   L3  底部账号行        头像 36dp + 用户名 + ···
 */
@Composable
fun ChatNavigationDrawerContent(
    sessions: List<DshController.SessionItem>,
    workspaces: List<DshController.WorkspaceItem>,
    selectedWorkspaceId: String?,
    expandedIds: List<String>,
    sessionsOf: (DshController.WorkspaceItem) -> List<DshController.SessionItem>,
    onToggleWorkspace: (String) -> Unit,
    accountName: String,
    modifier: Modifier = Modifier,
    showAccount: Boolean = true,
    selectedId: String? = null,
    connected: Boolean = false,
    onOpenSession: (DshController.SessionItem) -> Unit = {},
    onOpenAccountMenu: () -> Unit = {},
    onSearch: () -> Unit = {},
    onNewSession: () -> Unit = {},
) {
    val p = DshTheme.p
    Column(
        modifier
            .fillMaxSize()
            .background(p.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = DsSpacing.s3),
    ) {
        // ---------- L0 搜索
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DsSpacing.screenH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                DsSearchBar(
                    placeholder = DsStr.searchHint,
                    icon = Icons.Filled.Search,
                    onClick = onSearch,
                )
            }
            Spacer(Modifier.width(DsSpacing.s2))
            // 新建会话（协议 sessions.create）
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(p.fill)
                    .clickable(onClick = onNewSession),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, "新建会话", tint = p.textPrimary, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(DsSpacing.s2))

        // 诊断用计数条（一眼看出数据到底有没有到）
        Text(
            "工作区 ${workspaces.size} · 会话 ${sessions.size} · 选中 ${selectedId?.take(8) ?: "—"}",
            style = DsType.rowSubtitle,
            color = DshTheme.p.textPlaceholder,
            modifier = Modifier.padding(horizontal = DsSpacing.screenH, vertical = 2.dp),
        )

        // ---------- L1 分组 + L2 会话
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            // ---------------- 树形：工作区 → 下伸子列表
            if (workspaces.isNotEmpty()) {
                item(key = "ws_header") {
                    SessionGroupHeader(label = "工作区", showSortIcon = false)
                }
                workspaces.forEach { w ->
                    val expanded = expandedIds.contains(w.id)
                    item(key = "ws_" + w.id) {
                        WorkspaceRow(
                            title = w.title.ifEmpty { w.path },
                            subtitle = "${w.sessionIds.size} 个会话",
                            expanded = expanded,
                            selected = w.id == selectedWorkspaceId,
                            onClick = { onToggleWorkspace(w.id) },
                        )
                    }
                    if (expanded) {
                        val kids = sessionsOf(w)
                        if (kids.isEmpty()) {
                            item(key = "ws_empty_" + w.id) {
                                SubRow(text = "该工作区暂无会话", muted = true, onClick = {})
                            }
                        } else {
                            kids.forEachIndexed { k, sess ->
                                item(key = "s_" + w.id + "_" + k + "_" + sess.id) {
                                    SubRow(
                                        text = sess.title,
                                        muted = false,
                                        selected = sess.id == selectedId,
                                        onClick = { onOpenSession(sess) },
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "ws_gap") { Spacer(Modifier.height(DsSpacing.s3)) }
            } else {
                // 老 host 没有工作区：退化成平铺会话
                if (sessions.isEmpty()) {
                    item {
                        Text(
                            if (connected) "没有会话" else "未连接",
                            style = DsType.rowSubtitle,
                            color = DshTheme.p.textPlaceholder,
                            modifier = Modifier.padding(horizontal = DsSpacing.screenH, vertical = DsSpacing.s4),
                        )
                    }
                } else {
                    item(key = "group_header") { SessionGroupHeader(label = "会话", showSortIcon = true) }
                    itemsIndexed(sessions, key = { idx, it -> it.id + "#" + idx }) { _, it ->
                        ChatSessionItem(
                            title = it.title,
                            selected = it.id == selectedId,
                            onClick = { onOpenSession(it) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(DsSpacing.s4)) }
        }

        // ---------- L3 底部账号
        if (showAccount) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = DsSpacing.screenH),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(p.fill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Person,
                        null,
                        tint = p.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(DsSpacing.s3))
                Text(
                    accountName,
                    style = DsType.sessionTitle,
                    color = p.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(DsSpacing.touch)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenAccountMenu),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.MoreHoriz,
                        null,
                        tint = p.textSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * 对应 ChatSessionItem.kt —— L2 会话条目（含选中态）
 *
 * 对照真机 1000052262 量得：
 *   行高 56dp（固定），选中胶囊【居中内缩】在行内 —— 高约 39dp（上下各留约 8.5dp）
 *   胶囊左内边距 12dp（= 屏幕边距 DsSpacing.screenH - 6dp）
 *   胶囊为全圆（radius = 高/2）
 * 我原来把胶囊直接撑满整行（52dp 高）并贴左右边，所以看着比原版"胖"。
 */
@Composable
fun ChatSessionItem(
    title: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    subtitle: String? = null,
    onClick: () -> Unit = {},
    onMore: () -> Unit = {},
) {
    val p = DshTheme.p
    val bg = if (selected) p.brandSoft else Color.Transparent
    val fg = if (selected) p.brand else p.textPrimary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RadiiPill))
                .background(bg)
                .clickable(onClick = onClick)
                .padding(start = 6.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = DsType.sessionTitle,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = DsType.rowSubtitle,
                        color = if (selected) p.brand else p.textSecondary,
                        maxLines = 1,
                    )
                }
            }
            if (selected) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onMore),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.MoreHoriz,
                        "更多",
                        tint = p.brand,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** 胶囊圆角 = 高/2（39dp 高 → 19.5dp，取整 20dp，看着就是全圆） */
private val RadiiPill = 20.dp

/**
 * 分组头 —— 对应 SessionGroupHeader.kt（L1）
 * 13sp 次级灰，左侧与条目文字对齐，首个分组右侧带排序图标
 */
@Composable
fun SessionGroupHeader(
    label: String,
    modifier: Modifier = Modifier,
    showSortIcon: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = DsSpacing.screenH, end = DsSpacing.screenH)
            .padding(top = DsSpacing.s4, bottom = DsSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = DsType.sectionLabel,
            color = DshTheme.p.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (showSortIcon) {
            Icon(
                Icons.Filled.Tune,
                "排序",
                tint = DshTheme.p.textPlaceholder,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 工作区行 —— 树形的父节点。
 * 右边一个会转的 chevron：收起时指向右，展开时指向下。
 */
@Composable
fun WorkspaceRow(
    title: String,
    subtitle: String,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val p = DshTheme.p
    val bg = if (selected) p.brandSoft else Color.Transparent
    val fg = if (selected) p.brand else p.textPrimary
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RadiiPill))
                .background(bg)
                .clickable(onClick = onClick)
                .padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Workspaces,
                null,
                tint = fg,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = DsType.sessionTitle, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = DsType.rowSubtitle, color = if (selected) p.brand else p.textSecondary, maxLines = 1)
            }
            Icon(
                if (expanded) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                if (expanded) "收起" else "展开",
                tint = p.textPlaceholder,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 子列表行 —— 树形的叶节点。
 * 左侧加一条竖直引导线 + 缩进，层级一眼可辨。
 */
@Composable
fun SubRow(
    text: String,
    muted: Boolean,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    val p = DshTheme.p
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 26.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 引导线
        Box(
            Modifier
                .width(1.5.dp)
                .height(40.dp)
                .background(p.divider)
        )
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(RadiiPill))
                .background(if (selected) p.brandSoft else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text,
                style = DsType.sessionTitle,
                color = when {
                    selected -> p.brand
                    muted -> p.textPlaceholder
                    else -> p.textPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
