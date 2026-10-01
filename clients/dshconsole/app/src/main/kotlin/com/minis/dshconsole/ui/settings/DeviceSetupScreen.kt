package com.minis.dshconsole.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.minis.dshconsole.Store
import com.minis.dshconsole.ui.DsStr
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.minis.dshconsole.ui.components.DsCircleButton
import com.minis.dshconsole.ui.components.DsButton
import com.minis.dshconsole.ui.components.DsGroupCard
import com.minis.dshconsole.ui.components.DsSectionLabel
import com.minis.dshconsole.ui.components.DsTopBar
import com.minis.dshconsole.ui.theme.DsRadius
import com.minis.dshconsole.ui.theme.DsSpacing
import com.minis.dshconsole.ui.theme.DsType
import com.minis.dshconsole.ui.theme.DshTheme

/**
 * 设备配置页 —— 连 DSH 前必须填的东西。
 *
 * 对应老客户端的 MainActivity 输入区（host / port / token），
 * 这里用与设置页同一套视觉（DsTopBar + DsSectionLabel + DsGroupCard + DsButton）。
 */
@Composable
fun DeviceSetupScreen(
    initial: Store.Dev?,
    status: String,
    connected: Boolean,
    onBack: (() -> Unit)? = null,
    onSaveAndConnect: (Store.Dev) -> Unit,
) {
    val p = DshTheme.p
    var name by remember { mutableStateOf(initial?.name?.ifEmpty { "我的 DSH" } ?: "我的 DSH") }
    // 主机与端口合并成一个输入框：192.168.2.5:7788（不写端口默认 7788）
    var addr by remember {
        mutableStateOf(
            if (initial != null && initial.host.isNotEmpty()) "${initial.host}:${initial.port}" else ""
        )
    }
    var token by remember { mutableStateOf(initial?.token ?: "") }

    val parsed = remember(addr) { parseAddr(addr) }
    val canSave = parsed != null

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
    ) {
        DsTopBar(
            title = "连接设备",
            left = if (onBack == null) null else ({
                DsCircleButton(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    "返回",
                    onBack,
                    container = DshTheme.p.fill,
                    tint = DshTheme.p.textPrimary,
                )
            }),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(DsSpacing.s2))

            DsSectionLabel("连接状态")
            DsGroupCard {
                Box(Modifier.fillMaxWidth().padding(DsSpacing.cardInset)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(DsRadius.pill))
                                .background(if (connected) p.brandSoft else p.fill)
                                .padding(horizontal = DsSpacing.s3, vertical = 6.dp)
                        ) {
                            Text(
                                if (connected) "已连接" else "未连接",
                                style = DsType.rowSubtitle,
                                color = if (connected) p.brand else p.textSecondary,
                            )
                        }
                        Spacer(Modifier.padding(horizontal = DsSpacing.s2))
                        Text(
                            status,
                            style = DsType.rowSubtitle,
                            color = p.textSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(DsSpacing.cardGap))

            DsSectionLabel("地址")
            DsGroupCard {
                Column(Modifier.padding(DsSpacing.cardInset)) {
                    DsField(
                        value = addr,
                        onValueChange = { addr = it.filter { c -> c.isLetterOrDigit() || c == '.' || c == ':' || c == '-' } },
                        label = "主机:端口",
                        placeholder = "192.168.2.5:7788",
                        keyboardType = KeyboardType.Uri,
                    )
                    if (addr.isNotBlank() && parsed == null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "格式：主机:端口，例如 192.168.2.5:7788",
                            style = DsType.rowSubtitle,
                            color = DshTheme.p.danger,
                        )
                    }
                    Spacer(Modifier.height(DsSpacing.s3))
                    DsField(
                        value = token,
                        onValueChange = { token = it.trim() },
                        label = "访问令牌（可留空）",
                        placeholder = "devctl",
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    )
                }
            }

            Spacer(Modifier.height(DsSpacing.s4))

            Box(Modifier.fillMaxWidth().padding(horizontal = DsSpacing.screenH)) {
                DsButton(
                    text = "保存并连接",
                    onClick = {
                        val pr = parsed
                        if (pr != null) {
                            val dev = Store.Dev().apply {
                                this.name = name.ifBlank { "我的 DSH" }
                                this.host = pr.first
                                this.port = pr.second
                                this.token = token.trim()
                            }
                            onSaveAndConnect(dev)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSave,
                )
            }

            Spacer(Modifier.height(DsSpacing.s3))
            Text(
                "在 PC 上跑着 DSH + devctl-dsh 插件时，填那台机器的「局域网 IP:端口」。不写端口默认 7788。",
                style = DsType.rowSubtitle,
                color = p.textPlaceholder,
                modifier = Modifier.padding(horizontal = DsSpacing.screenH),
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * 解析 "主机[:端口]"。
 *   192.168.2.5        -> ("192.168.2.5", 7788)
 *   192.168.2.5:10201  -> ("192.168.2.5", 10201)
 *   2001:db8::1:7788   -> 取最后一个冒号切分
 */
private fun parseAddr(raw: String): Pair<String, Int>? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    val i = t.lastIndexOf(':')
    if (i < 0) return t to 7788
    val h = t.substring(0, i).trim().trim('[', ']')
    val p = t.substring(i + 1).trim().toIntOrNull() ?: return null
    if (h.isEmpty() || p !in 1..65535) return null
    return h to p
}

@Composable
private fun DsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction = ImeAction.Next,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = DsType.rowSubtitle, color = DshTheme.p.textSecondary)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(placeholder, style = DsType.body, color = DshTheme.p.textPlaceholder)
            },
            singleLine = true,
            textStyle = DsType.body,
            shape = RoundedCornerShape(DsRadius.card),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
