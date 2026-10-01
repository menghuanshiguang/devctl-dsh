package com.minis.dshconsole.ui

/**
 * UI 文案 —— 逐条取自 DeepSeek 客户端自己的资源表
 * res/values-b+zh+Hans/strings.xml（693 条）与 res/values/strings.xml（742 条）
 *
 * 键名沿用原包，便于对照。
 */
object DsStr {
    // ---- 输入区（ChatSessionBottomBar.kt / ChatInputField.kt）
    const val chatInputPlaceholderChat = "发消息"                       // chat_input_placeholder_chat
    const val chatInputPlaceholderVoice = "发消息或按住说话"             // chat_input_placeholder_voice
    const val voiceInputButton = "按住说话"                             // voice_input_button
    const val messageR1Button = "深度思考"                              // message_r1_button
    const val messageSearchButton = "智能搜索"                          // message_search_button

    // ---- 上传面板（UploadPanel.kt / UploadPanelActionButton.kt）
    const val cameraShutter = "拍照"                                    // camera_shutter
    const val uploadPanelAlbum = "相册"                                 // upload_panel_album
    const val uploadPanelAddMorePhotos = "授权更多可访问图片"            // upload_panel_add_more_photos
    const val uploadFileMenuLocal = "本地文件"                          // upload_file_menu_local
    const val uploadFileMenuWeChat = "微信文件"                         // upload_file_menu_we_chat

    // ---- 欢迎页（ChatWelcome.kt / ChatWelcomeLogo.kt）
    const val chatWelcomeTitle = "欢迎使用"                             // chat_welcome_title
    const val chatWelcomeInfo1Title = "官方免费应用"                     // chat_welcome_info1_title
    const val chatWelcomeInfo1Content = "体验 DeepSeek 最新模型，跨设备同步历史记录。"
    const val chatWelcomeInfo2Title = "DeepSeek 可能不准确"              // chat_welcome_info2_title
    const val chatWelcomeInfo2Content =
        "输出内容由 AI 生成，医疗、法律、金融等专业领域的内容不构成任何诊疗、法律或投资建议，请注意甄别。"

    /** 空会话问候语（截图实测：「嗨！今天想聊些什么？」/「你好，有什么我能帮你的吗？」） */
    val greeting = listOf(
        "嗨！今天想聊些什么？",
        "你好，有什么我能帮你的吗？",
    )

    // ---- 会话列表（ChatSessionList.kt / ChatSessionItem.kt / SessionGroupHeader.kt）
    const val searchHint = "搜索对话内容..."                            // search_hint
    const val sessionGroupPinned = "置顶"                               // session_group_pinned
    const val sessionBatchEntryButton = "多选"                          // session_batch_entry_button
    const val sessionDeleteConfirm = "删除该对话"                       // session_delete_confirm
    const val sessionTitleEdit = "重命名"                               // (SessionTitleEditDialog.kt)

    // ---- 设置页（SettingsPage.kt / SettingItem.kt）
    const val settings = "设置"                                         // settings
    const val accountManagement = "账号管理"                            // account_management
    const val dataControls = "数据管理"                                 // data_controls
    const val appLanguage = "语言"                                      // app_language
    const val appColorScheme = "外观"                                   // app_color_scheme
    const val fontSize = "字体大小"                                     // font_size
    const val personalization = "个性化"                                // personalization
    const val voiceSwitchTitle = "朗读音色"                             // voice_switch_bottom_sheet_title
    const val checkForUpdates = "检查更新"                              // profile_check_for_updates
    const val serviceSection = "服务协议"                               // profile_service_section_header
    const val termsOfUse = "用户协议"                                   // terms_of_use
    const val privacyPolicy = "隐私政策"                                // privacy_policy

    /** 设置页的分组标题 */
    const val groupAccount = "账户"
    const val groupApp = "应用"
    const val groupVoice = "语音"
    const val groupAbout = "关于"

    // ---- 消息操作栏（AssistantChatMessageFooter.kt / UserMessageActionView.kt）
    const val messagePressCopy = "复制"                                 // message_press_copy
    const val messagePressRegenerate = "重新生成"                       // message_press_regenerate
    const val readAloudStartLabel = "朗读"                              // read_aloud_start_label
    const val share = "分享"                                            // share

    // ---- 其它
    const val aiAlert = "回答由 AI 生成，仅供参考"                       // input_a_i_alert / session_a_i_alert
    const val networkError = "网络异常，请检查你的网络状况"               // common_network_error_toast
    const val searchNoResult = "暂无相关结果"                            // search_empty_result
    const val fileUploading = "请在上传完成后发送"                       // file_uploading
    const val voiceSendError = "语音发送失败"                            // voice_send_error
}
