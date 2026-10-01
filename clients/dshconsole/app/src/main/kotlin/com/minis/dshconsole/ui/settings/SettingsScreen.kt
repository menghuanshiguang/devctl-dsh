package com.minis.dshconsole.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Stars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.minis.dshconsole.ui.components.DsCircleButton
import com.minis.dshconsole.ui.components.DsGroupCard
import com.minis.dshconsole.ui.components.DsRow
import com.minis.dshconsole.ui.components.DsRowDivider
import com.minis.dshconsole.ui.components.DsSectionLabel
import com.minis.dshconsole.ui.components.DsTopBar
import com.minis.dshconsole.ui.theme.DsColor
import com.minis.dshconsole.ui.theme.DsSpacing
import com.minis.dshconsole.ui.theme.DshTheme

/**
 * 设置页 —— 按真机截图逐项还原（原包 ui/pages/settings/SettingsPage.kt）
 *
 * 结构：分组标签（13sp，#8F9094）+ 白色圆角卡片（12dp 圆角、左右 18dp）+ 56dp 行
 * 行内：图标 20dp → 20dp 间距 → 标题 17sp →（右侧值 15sp 灰）→ chevron
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDeviceSetup: () -> Unit = {},
    connectionStatus: String = "",
) {
    Column(
        modifier
            .fillMaxSize()
            .background(DshTheme.p.bgGrouped())
    ) {
        DsTopBar(
            title = "设置",
            left = {
                DsCircleButton(
                    Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack,
                    container = DshTheme.p.fill,
                    tint = DshTheme.p.textPrimary,
                )
            },
        )

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(6.dp))

            // ---------------- 账户
            DsSectionLabel("连接")
            DsGroupCard {
                DsRow(
                    "连接设备",
                    subtitle = connectionStatus.ifEmpty { "配置 DSH 的 IP 与端口" },
                    icon = Icons.Filled.Person,
                    onClick = onOpenDeviceSetup,
                )
            }
            Spacer(Modifier.height(DsSpacing.cardGap))

            DsSectionLabel("账户")
            DsGroupCard {
                DsRow("账号管理", icon = Icons.Filled.Person)
                DsRowDivider()
                DsRow("数据管理", icon = Icons.Filled.DataUsage)
            }
            Spacer(Modifier.height(DsSpacing.cardGap))

            // ---------------- 应用
            DsSectionLabel("应用")
            DsGroupCard {
                DsRow("语言", icon = Icons.Filled.Language, value = "中文（简体中文）")
                DsRowDivider()
                DsRow("外观", icon = Icons.Filled.DarkMode, value = "系统")
                DsRowDivider()
                DsRow("字体大小", icon = Icons.Filled.FontDownload)
                DsRowDivider()
                DsRow("个性化", icon = Icons.Filled.Stars)
            }
            Spacer(Modifier.height(DsSpacing.cardGap))

            // ---------------- 语音
            DsSectionLabel("语音")
            DsGroupCard {
                DsRow("朗读音色", icon = Icons.Filled.GraphicEq, showChevron = false)
            }
            Spacer(Modifier.height(DsSpacing.cardGap))

            // ---------------- 关于
            DsSectionLabel("关于")
            DsGroupCard {
                DsRow("检查更新", icon = Icons.Filled.Info, value = "2.6.1(279)")
                DsRowDivider()
                DsRow("服务协议", icon = Icons.AutoMirrored.Filled.MenuBook)
            }

            Spacer(Modifier.height(40.dp))
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

/** 分组页面底色（#F8F8F8），聊天页是纯白 */
@Composable
private fun com.minis.dshconsole.ui.theme.DshPalette.bgGrouped() =
    if (this.bg == DsColor.DarkBg) DsColor.DarkBg else DsColor.BgGrouped
