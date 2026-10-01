package com.minis.dshconsole.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.minis.dshconsole.ui.DsStr

/**
 * 本机设置（SharedPreferences 落盘 + Compose 可订阅状态）。
 *
 * 前四项全部立刻生效：
 *   themeMode   外观    跟随系统 / 浅色 / 深色   -> DshTheme(darkTheme)
 *   fontScale   字体大小  0.9 / 1.0 / 1.15       -> DsType 整体缩放
 *   lang        语言     中文 / English          -> DsStr 取哪一套
 *   renderStream 个性化   流式期间是否纯文本追加
 */
class DshPrefs(context: Context) {

    private val sp = context.getSharedPreferences("dsh_prefs", Context.MODE_PRIVATE)

    enum class ThemeMode { System, Light, Dark }
    enum class Lang { Zh, En }

    var themeMode by mutableStateOf(
        runCatching { ThemeMode.valueOf(sp.getString("theme", "System")!!) }
            .getOrDefault(ThemeMode.System)
    )
        private set

    var fontScale by mutableStateOf(sp.getFloat("fontScale", 1.0f))
        private set

    var lang by mutableStateOf(
        runCatching { Lang.valueOf(sp.getString("lang", "Zh")!!) }.getOrDefault(Lang.Zh)
    )
        private set

    var streamPlainText by mutableStateOf(sp.getBoolean("streamPlain", true))
        private set

    fun setTheme(m: ThemeMode) {
        themeMode = m
        sp.edit().putString("theme", m.name).apply()
    }

    fun setFontScale(f: Float) {
        fontScale = f
        sp.edit().putFloat("fontScale", f).apply()
    }

    fun setLang(l: Lang) {
        lang = l
        DsStr.zh = (l == Lang.Zh)
        sp.edit().putString("lang", l.name).apply()
    }

    init {
        // 启动时把落盘的语言灌给文案表
        DsStr.zh = (lang == Lang.Zh)
    }

    fun setStreamPlainText(v: Boolean) {
        streamPlainText = v
        sp.edit().putBoolean("streamPlain", v).apply()
    }

    /** 演示用：清掉本机的会话缓存与统计 */
    fun clearSessionData() {
        sp.edit().remove("stats").apply()
    }

    // ------------------------------------------------------------ 统计

    /** 本机累计的量（host 不提供 usage 时用这个） */
    var statMessages by mutableStateOf(sp.getInt("st_messages", 0))
        private set
    var statChars by mutableStateOf(sp.getInt("st_chars", 0))
        private set
    var statPrompts by mutableStateOf(sp.getInt("st_prompts", 0))
        private set

    /** 粗略估算 token：中英混排按 1 token ≈ 1.6 字符 估 */
    val estimatedTokens: Int get() = (statChars / 1.6f).toInt()

    fun recordPrompt(text: String) {
        statPrompts += 1
        statMessages += 1
        statChars += text.length
        persistStats()
    }

    fun recordAssistant(text: String) {
        statMessages += 1
        statChars += text.length
        persistStats()
    }

    fun resetStats() {
        statMessages = 0
        statChars = 0
        statPrompts = 0
        persistStats()
    }

    private fun persistStats() {
        sp.edit()
            .putInt("st_messages", statMessages)
            .putInt("st_chars", statChars)
            .putInt("st_prompts", statPrompts)
            .apply()
    }
}
