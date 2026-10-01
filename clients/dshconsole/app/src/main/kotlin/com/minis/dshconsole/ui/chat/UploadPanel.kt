package com.minis.dshconsole.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.minis.dshconsole.ui.DsStr
import com.minis.dshconsole.ui.theme.DsRadius
import com.minis.dshconsole.ui.theme.DsSpacing
import com.minis.dshconsole.ui.theme.DsType
import com.minis.dshconsole.ui.theme.DshTheme

/*
 * 上传面板 —— 对应 DeepSeek 原包
 *   ui/pages/chat/session/uploadpanel/UploadPanel.kt
 *   UploadPanelActionButton.kt / UploadPanelActionButtonGroup.kt
 *   UploadPanelAddImageButton.kt / UploadPanelImage.kt / UploadPanelImageScroller.kt
 *   UploadPanelInfo.kt / UploadPanelPermission.kt
 *
 * 截图实测结构（点 ⊕ 展开后）：
 *   第一行：已选图片缩略图（圆角方块）+「＋ 授权更多可访问图片」方卡
 *   第二行：三个等宽方卡 —— 拍照 / 相册 / 文件（图标在上、文字在下）
 */

/** 缩略图边长 */
private val ThumbSize = 88.dp

/** 动作卡尺寸 */
private val ActionCardHeight = 88.dp

/** 动作卡圆角（截图实测约 16dp） */
private val ActionCardRadius = 16.dp

@Composable
fun UploadPanel(
    modifier: Modifier = Modifier,
    attachedCount: Int = 1,
    onPickCamera: () -> Unit = {},
    onPickAlbum: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onAddMorePhotos: () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.screenH),
    ) {
        // ---------- 第一行：缩略图 + 授权更多
        LazyRow(horizontalArrangement = Arrangement.spacedBy(DsSpacing.s2)) {
            items(attachedCount) { i ->
                Box(
                    Modifier
                        .size(ThumbSize)
                        .clip(RoundedCornerShape(ActionCardRadius))
                        .background(DshTheme.p.fillStrong),
                )
            }
            item {
                Column(
                    Modifier
                        .size(ThumbSize)
                        .clip(RoundedCornerShape(ActionCardRadius))
                        .background(DshTheme.p.fill)
                        .clickable(onClick = onAddMorePhotos),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        null,
                        tint = DshTheme.p.textSecondary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        DsStr.uploadPanelAddMorePhotos,
                        style = DsType.bodySmall,
                        color = DshTheme.p.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(DsSpacing.s3))

        // ---------- 第二行：拍照 / 相册 / 文件
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.s2)) {
            UploadPanelActionButton(
                label = DsStr.cameraShutter,
                icon = Icons.Filled.PhotoCamera,
                onClick = onPickCamera,
                modifier = Modifier.weight(1f),
            )
            UploadPanelActionButton(
                label = DsStr.uploadPanelAlbum,
                icon = Icons.Filled.PhotoLibrary,
                onClick = onPickAlbum,
                modifier = Modifier.weight(1f),
            )
            UploadPanelActionButton(
                label = DsStr.uploadFileMenuLocal,
                icon = Icons.Filled.AttachFile,
                onClick = onPickFile,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 对应 UploadPanelActionButton.kt：图标在上、标签在下，灰底圆角方卡 */
@Composable
private fun UploadPanelActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .height(ActionCardHeight)
            .clip(RoundedCornerShape(ActionCardRadius))
            .background(DshTheme.p.fill)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = DshTheme.p.textPrimary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = DsType.body, color = DshTheme.p.textPrimary)
    }
}


