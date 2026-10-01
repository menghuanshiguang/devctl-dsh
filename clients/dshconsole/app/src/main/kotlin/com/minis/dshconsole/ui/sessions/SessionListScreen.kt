package com.minis.dshconsole.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import com.minis.dshconsole.ui.components.DsSearchBar
import com.minis.dshconsole.ui.components.DsSectionLabel
import com.minis.dshconsole.ui.components.DsCircleButton
import com.minis.dshconsole.ui.theme.DsSpacing
import com.minis.dshconsole.ui.theme.DsType
import com.minis.dshconsole.ui.theme.DshTheme

/**
 * 会话列表页 —— 按真机截图还原（原包 ChatSearchList.kt / ChatNavigationDrawerContent.kt）
 *
 * 结构：顶部搜索框（52dp 全圆角 #F5F5F5）+ 时间分组标签 + 条目标题（16sp）
 *       + 底部账号行（头像 36dp + 用户名 + ···）
 */
@Composable
fun SessionListScreen(
    groups: List<Pair<String, List<String>>>,
    accountName: String,
    modifier: Modifier = Modifier,
    onOpenSession: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val p = DshTheme.p
    Column(modifier.fillMaxSize().background(p.bg)) {
        Spacer(Modifier.height(DsSpacing.s3))
        DsSearchBar(
            placeholder = "搜索对话内容…",
            icon = Icons.Filled.Search,
            onClick = {},
        )
        Spacer(Modifier.height(DsSpacing.s2))

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            groups.forEach { (label, items) ->
                item(key = "h_$label") {
                    Row(
                        Modifier.fillMaxWidth().padding(end = DsSpacing.screenH),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) { DsSectionLabel(label) }
                        Icon(
                            Icons.Filled.Tune,
                            "排序",
                            tint = p.textPlaceholder,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                itemsIndexed(items, key = { i2, _ -> "${label}_$i2" }) { _, title ->
                    Text(
                        title,
                        style = DsType.sessionTitle,
                        color = p.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSession(title) }
                            .padding(horizontal = DsSpacing.screenH, vertical = 14.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(DsSpacing.s6)) }
        }

        // 底部账号行
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
                Icon(Icons.Filled.Person, null, tint = p.textSecondary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(DsSpacing.s3))
            Text(
                accountName,
                style = DsType.sessionTitle,
                color = p.textPrimary,
                modifier = Modifier.weight(1f),
            )
            DsCircleButton(Icons.Filled.MoreHoriz, "更多", {}, tint = p.textSecondary)
        }
    }
}
