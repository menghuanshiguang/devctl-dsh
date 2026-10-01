package com.minis.dshconsole.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.minis.dshconsole.ui.components.DsCircleButton
import com.minis.dshconsole.ui.components.DsGroupCard
import com.minis.dshconsole.ui.components.DsRow
import com.minis.dshconsole.ui.components.DsRowDivider
import com.minis.dshconsole.ui.components.DsSectionLabel
import com.minis.dshconsole.ui.components.DsTopBar
import com.minis.dshconsole.ui.theme.DsColor
import com.minis.dshconsole.ui.theme.DsSpacing
import com.minis.dshconsole.ui.theme.DsType
import com.minis.dshconsole.ui.theme.DshTheme

/**
 * 设置页 —— 每一行都对应一个真实能力（没有占位行）。
 *
 * 对照原包 ui/pages/settings/SettingsPage.kt + SettingItem.kt 的结构，
 * 但内容按 DSH 客户端自身能做的事重排：
 *   连接  连接设备 / 断开连接          -> DeviceSetupScreen / DshController.disconnect
 *   账户  主机信息 / 数据管理（token 统计）-> HostInfoScreen / TokenStatsScreen
 *   应用  语言 / 外观 / 字体大小 / 个性化  -> 立即生效并可落盘
 *   关于  版本 / 协议
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDeviceSetup: () -> Unit = {},
    onOpenTokenStats: () -> Unit = {},
    onOpenHostInfo: () -> Unit = {},
    connectionStatus: String = "",
    connected: Boolean = false,
    hostName: String = "",
    prefs: DshPrefs,
    onDisconnect: () -> Unit = {},
    onSyncLang: (DshPrefs.Lang) -> Unit = {},
) {
    Column(
        modifier
            .fillMaxSize()
            .background(groupedBg())
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

            // ------------------------------------------------ 连接
            DsSectionLabel("连接")
            DsGroupCard {
                DsRow(
                    "连接设备",
                    subtitle = connectionStatus.ifEmpty { "未连接" },
                    icon = Icons.Filled.Dns,
                    onClick = onOpenDeviceSetup,
                )
                if (connected) {
                    DsRowDivider()
                    DsRow(
                        "断开连接",
                        subtitle = hostName.ifEmpty { "当前连接" },
                        icon = Icons.Filled.LinkOff,
                        showChevron = false,
                        onClick = onDisconnect,
                    )
                }
            }
            Spacer(Modifier.height(DsSpacing.cardGap))

            // ------------------------------------------------ 账户 / 数据
            DsSectionLabel("账户")
            DsGroupCard {
                DsRow(
                    "主机信息",
                    subtitle = if (connected) hostName else "未连接",
                    icon = Icons.Filled.Person,
                    onClick = onOpenHostInfo,
                )
                DsRowDivider()
                DsRow(
                    "数据管理",
                    subtitle = "Token 统计 · 本机用量",
                    icon = Icons.Filled.DarkMode,
                    onClick = onOpenTokenStats,
                )
            }
            Spacer(Modifier.height(DsSpacing.cardGap))

            // ------------------------------------------------ 应用
            DsSectionLabel("应用")
            DsGroupCard {
                DsRow(
                    "语言",
                    value = if (prefs.lang == DshPrefs.Lang.Zh) "中文（简体中文）" else "English",
                    icon = Icons.Filled.Language,
                    onClick = {
                        val next = if (prefs.lang == DshPrefs.Lang.Zh) DshPrefs.Lang.En else DshPrefs.Lang.Zh
                        prefs.applyLang(next)          // 本地立刻生效
                        onSyncLang(next)             // 同时同步给 host
                    },
                )
                DsRowDivider()
                DsRow(
                    "外观",
                    value = when (prefs.themeMode) {
                        DshPrefs.ThemeMode.System -> "系统"
                        DshPrefs.ThemeMode.Light -> "浅色"
                        DshPrefs.ThemeMode.Dark -> "深色"
                    },
                    icon = Icons.Filled.DarkMode,
                    onClick = {
                        prefs.setTheme(
                            when (prefs.themeMode) {
                                DshPrefs.ThemeMode.System -> DshPrefs.ThemeMode.Light
                                DshPrefs.ThemeMode.Light -> DshPrefs.ThemeMode.Dark
                                DshPrefs.ThemeMode.Dark -> DshPrefs.ThemeMode.System
                            }
                        )
                    },
                )
                DsRowDivider()
                DsRow(
                    "字体大小",
                    value = when {
                        prefs.fontScale < 0.97f -> "小"
                        prefs.fontScale > 1.08f -> "大"
                        else -> "标准"
                    },
                    icon = Icons.Filled.FormatSize,
                    onClick = {
                        prefs.applyFontScale(
                            when {
                                prefs.fontScale < 0.97f -> 1.0f
                                prefs.fontScale > 1.08f -> 0.9f
                                else -> 1.15f
                            }
                        )
                    },
                )
            }
            Spacer(Modifier.height(DsSpacing.cardGap))

            // ------------------------------------------------ 关于
            DsSectionLabel("关于")
            DsGroupCard {
                DsRow("版本", value = "3.5.0", icon = Icons.Filled.Person, showChevron = false)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * 分组页底色 —— 设置/数据/主机这几页用 #F8F8F8（暗色 #0F0F0F），
 * 靠白色卡片区分层级；聊天页才用纯白。
 * ★ 这几页必须用灰色底，否则白卡白底 = 圆角与边距全部看不见 ★
 */
@Composable
private fun groupedBg() =
    if (DshTheme.p.bg == DsColor.DarkBg) DsColor.DarkBg else DsColor.BgGrouped

/** Token 统计页 */
@Composable
fun TokenStatsScreen(
    prefs: DshPrefs,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(groupedBg())
    ) {
        DsTopBar(
            title = "数据管理",
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
            DsSectionLabel("本机用量")
            DsGroupCard {
                DsRow("发送的消息", value = "${prefs.statPrompts} 条", showChevron = false)
                DsRowDivider()
                DsRow("消息总数", value = "${prefs.statMessages} 条", showChevron = false)
                DsRowDivider()
                DsRow("字符数", value = "${prefs.statChars}", showChevron = false)
                DsRowDivider()
                DsRow(
                    "估算 Token",
                    value = "≈ ${prefs.estimatedTokens}",
                    subtitle = "按 1 token ≈ 1.6 字符 估算（中英混排）",
                    showChevron = false,
                )
            }
            Spacer(Modifier.height(DsSpacing.cardGap))
            DsSectionLabel("操作")
            DsGroupCard {
                DsRow("清空统计", icon = Icons.Filled.LinkOff, showChevron = false, onClick = { prefs.resetStats() })
            }
            Spacer(Modifier.height(DsSpacing.s3))
            Text(
                "当前统计只记在本机。host 侧若提供 usage 接口，可替换成服务端真实计量。",
                style = DsType.rowSubtitle,
                color = DshTheme.p.textPlaceholder,
                modifier = Modifier.padding(horizontal = DsSpacing.screenH),
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** 主机信息页 */
@Composable
fun HostInfoScreen(
    hostName: String,
    status: String,
    connected: Boolean,
    addr: String,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(groupedBg())
    ) {
        DsTopBar(
            title = "主机信息",
            left = {
                DsCircleButton(
                    Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack,
                    container = DshTheme.p.fill,
                    tint = DshTheme.p.textPrimary,
                )
            },
        )
        Spacer(Modifier.height(6.dp))
        DsSectionLabel("当前设备")
        DsGroupCard {
            DsRow("主机名", value = hostName.ifEmpty { "—" }, showChevron = false)
            DsRowDivider()
            DsRow("地址", value = addr.ifEmpty { "—" }, showChevron = false)
            DsRowDivider()
            DsRow("状态", value = if (connected) "已连接" else "未连接", showChevron = false)
        }
        Spacer(Modifier.height(DsSpacing.cardGap))
        DsGroupCard {
            DsRow("断开连接", icon = Icons.Filled.LinkOff, showChevron = false, onClick = onDisconnect)
        }
        Spacer(Modifier.height(DsSpacing.s3))
        Text(
            status,
            style = DsType.rowSubtitle,
            color = DshTheme.p.textSecondary,
            modifier = Modifier.padding(horizontal = DsSpacing.screenH),
        )
    }
}

/** 设置页底部那行小字（原包 SettingItem 的 value 槽位复用） */
@Composable
private fun RowHint(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = DsType.rowValue, color = DshTheme.p.textSecondary)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = DshTheme.p.textPlaceholder,
            modifier = Modifier.size(18.dp),
        )
    }
}
