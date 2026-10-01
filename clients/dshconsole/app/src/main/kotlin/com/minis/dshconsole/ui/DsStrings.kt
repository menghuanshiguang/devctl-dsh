package com.minis.dshconsole.ui

/**
 * UI 文案 —— 中英双语，可在本地切换，并同步给 host。
 *
 * 中文键名与文案沿用 DeepSeek 客户端自己的资源表
 *   res/values-b+zh+Hans/strings.xml（693 条）
 *   res/values/strings.xml（742 条）
 *
 * ★ 语言的两层 ★
 *   本地：这里的 lang 决定 App 自己的界面用哪一套（立刻生效，落盘）
 *   云端：DshController.setHostLang() 会把同一个值发给 host，
 *         让 host 侧（会话标题生成、回复语言）也跟着变。
 *   host 回推的语言（settings 事件）会反过来写回本地。
 */
object DsStr {

    /** 当前语言。由 DshPrefs 在启动与切换时写入 */
    @Volatile
    var zh: Boolean = true

    // ---- 输入区
    val chatInputPlaceholderChat: String
        get() = if (zh) "发消息" else "Message"
    val chatInputPlaceholderVoice: String
        get() = if (zh) "发消息或按住说话" else "Message or hold to talk"
    val voiceInputButton: String
        get() = if (zh) "按住说话" else "Hold to talk"
    val messageR1Button: String
        get() = if (zh) "深度思考" else "DeepThink"
    val messageSearchButton: String
        get() = if (zh) "智能搜索" else "Search"

    // ---- 附件面板
    val cameraShutter: String
        get() = if (zh) "拍照" else "Camera"
    val uploadPanelAlbum: String
        get() = if (zh) "相册" else "Photos"
    val uploadFileMenuLocal: String
        get() = if (zh) "本地文件" else "Files"
    val uploadPanelAddMorePhotos: String
        get() = if (zh) "授权更多可访问图片" else "Allow more photos"

    // ---- 欢迎页
    val chatWelcomeTitle: String
        get() = if (zh) "欢迎使用" else "Welcome"
    val greeting: String
        get() = if (zh) "嗨！今天想聊些什么？" else "Hi! What shall we talk about today?"
    val greetingAlt: String
        get() = if (zh) "你好，有什么我能帮你的吗？" else "Hi, how can I help you?"

    // ---- 会话列表
    val searchHint: String
        get() = if (zh) "搜索对话内容..." else "Search chats..."
    val sessionGroup: String
        get() = if (zh) "会话" else "Chats"
    val noSessions: String
        get() = if (zh) "没有会话" else "No chats"
    val notConnected: String
        get() = if (zh) "未连接" else "Not connected"
    val more: String
        get() = if (zh) "更多" else "More"

    // ---- 设置
    val settings: String
        get() = if (zh) "设置" else "Settings"
    val groupConnection: String
        get() = if (zh) "连接" else "Connection"
    val connectDevice: String
        get() = if (zh) "连接设备" else "Device"
    val disconnect: String
        get() = if (zh) "断开连接" else "Disconnect"
    val groupAccount: String
        get() = if (zh) "账户" else "Account"
    val hostInfo: String
        get() = if (zh) "主机信息" else "Host"
    val dataControls: String
        get() = if (zh) "数据管理" else "Data"
    val groupApp: String
        get() = if (zh) "应用" else "App"
    val appLanguage: String
        get() = if (zh) "语言" else "Language"
    val appColorScheme: String
        get() = if (zh) "外观" else "Appearance"
    val fontSize: String
        get() = if (zh) "字体大小" else "Font size"
    val groupAbout: String
        get() = if (zh) "关于" else "About"
    val version: String
        get() = if (zh) "版本" else "Version"

    // ---- 外观 / 字体选项
    val themeSystem: String
        get() = if (zh) "系统" else "System"
    val themeLight: String
        get() = if (zh) "浅色" else "Light"
    val themeDark: String
        get() = if (zh) "深色" else "Dark"
    val fontSmall: String
        get() = if (zh) "小" else "Small"
    val fontNormal: String
        get() = if (zh) "标准" else "Default"
    val fontLarge: String
        get() = if (zh) "大" else "Large"

    // ---- 连接 / 配置
    val back: String
        get() = if (zh) "返回" else "Back"
    val hostPort: String
        get() = if (zh) "主机:端口" else "Host:port"
    val token: String
        get() = if (zh) "访问令牌（可留空）" else "Access token (optional)"
    val saveAndConnect: String
        get() = if (zh) "保存并连接" else "Save & connect"
    val addrHint: String
        get() = if (zh) "格式：主机:端口，例如 192.168.2.5:7788"
        else "Format: host:port, e.g. 192.168.2.5:7788"
    val addrTip: String
        get() = if (zh) "在 PC 上跑着 DSH + devctl-dsh 插件时，填那台机器的「局域网 IP:端口」。不写端口默认 7788。"
        else "Enter the LAN IP:port of the machine running DSH + devctl-dsh. Port defaults to 7788."
    val connected: String
        get() = if (zh) "已连接" else "Connected"
    val notConnectedShort: String
        get() = if (zh) "未连接" else "Offline"
    val statusLabel: String
        get() = if (zh) "连接状态" else "Status"
    val address: String
        get() = if (zh) "地址" else "Address"
    val hostNameLabel: String
        get() = if (zh) "主机名" else "Host name"

    // ---- 其它
    val aiAlert: String
        get() = if (zh) "回答由 AI 生成，仅供参考" else "AI-generated, for reference only"
    val send: String
        get() = if (zh) "发送" else "Send"
    val copy: String
        get() = if (zh) "复制" else "Copy"
    val regenerate: String
        get() = if (zh) "重新生成" else "Regenerate"
    val helpful: String
        get() = if (zh) "有帮助" else "Helpful"
    val notHelpful: String
        get() = if (zh) "没帮助" else "Not helpful"
    val readAloud: String
        get() = if (zh) "朗读" else "Read aloud"
    val share: String
        get() = if (zh) "分享" else "Share"
    val thinking: String
        get() = if (zh) "思考" else "Thinking"
}
