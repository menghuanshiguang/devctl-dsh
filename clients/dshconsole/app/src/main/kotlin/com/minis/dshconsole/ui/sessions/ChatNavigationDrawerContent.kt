package com.minis.dshconsole.ui.sessions

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
    groups: List<Pair<String, List<String>>>,
    accountName: String,
    modifier: Modifier = Modifier,
    showAccount: Boolean = true,
    onOpenSession: (String) -> Unit = {},
    onOpenAccountMenu: () -> Unit = {},
    onSearch: () -> Unit = {},
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
        DsSearchBar(
            placeholder = DsStr.searchHint,
            icon = Icons.Filled.Search,
            onClick = onSearch,
        )
        Spacer(Modifier.height(DsSpacing.s2))

        // ---------- L1 分组 + L2 会话
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            groups.forEachIndexed { gi, (label, sessions) ->
                item(key = "group_$label") {
                    SessionGroupHeader(
                        label = label,
                        showSortIcon = gi == 0,
                    )
                }
                items(sessions, key = { "${label}_$it" }) { title ->
                    ChatSessionItem(title = title, onClick = { onOpenSession(title) })
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

/** 对应 SessionGroupHeader.kt —— L1 分组头：13sp 次级灰 + 右侧排序图标 */
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

/** 对应 ChatSessionItem.kt —— L2 会话条目 */
@Composable
fun ChatSessionItem(
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Text(
        title,
        style = DsType.sessionTitle,
        color = DshTheme.p.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = DsSpacing.screenH)
            .padding(vertical = 15.dp),
    )
}
