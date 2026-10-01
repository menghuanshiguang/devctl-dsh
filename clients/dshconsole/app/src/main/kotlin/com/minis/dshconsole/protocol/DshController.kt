package com.minis.dshconsole.protocol

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.minis.dshconsole.Dsh
import com.minis.dshconsole.Store
import com.minis.dshconsole.ui.chat.ChatFragment
import com.minis.dshconsole.ui.chat.ChatMessage
import com.minis.dshconsole.ui.chat.ToolState
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/*
 * 协议层桥接 —— 把老的 Java 协议实现（Dsh/Store/Wire）接到 Compose UI 上。
 *
 * 协议（devctl-dsh Host 插件，JSON Lines over TCP，token 鉴权）
 *   sessions.list    列出会话        -> { sessions|items: [{ id, title|name, ... }] }
 *   sessions.prompt  发送消息        { sessionId, text, mode?, images? }
 *   sessions.watch   订阅会话        -> 事件流（见下）
 *   sessions.cancel  打断
 *
 * watch 事件
 *   watch-start | snapshot | event | delta | tool-delta | watch-end
 *
 * 记录类型（event.record.type）
 *   user/message      用户消息      { id, text }
 *   assistant/message 助手消息      { id, text }（reasoning 另见片段字段）
 *   tool/call         工具调用开始  { id, name, summary|input }
 *   tool/result       工具返回      { id, ok|status, summary|output }
 *   system/message · developer/message   过程说明
 *   turn/start · turn/end                一轮开始/结束（决定 streaming）
 *   approval/request                     需要用户确认（可点击）
 *
 * ★ 字段名容错 ★
 * 插件侧的字段名我无法在本机验证，因此所有读取都走 pick() 多候选名，
 * 拿不到就退化成空串，绝不让解析异常冒到 UI。
 */
class DshController(private val appContext: Context) {

    private val store = Store(appContext)

    private var dsh: Dsh? = null
    private var watchThread: Thread? = null
    private val watching = AtomicBoolean(false)

    var connected by mutableStateOf(false)
        private set
    var status by mutableStateOf("未连接")
        private set
    var hostName by mutableStateOf("")
        private set

    /** 侧栏会话：id -> 标题 */
    val sessions = mutableStateListOf<SessionItem>()
    val messages = mutableStateListOf<ChatMessage>()
    var currentSessionId by mutableStateOf<String?>(null)
        private set
    var streaming by mutableStateOf(false)
        private set

    data class SessionItem(val id: String, val title: String)

    /** 正在串流的那条助手消息的 id */
    private var streamingId: String? = null

    // ---------------------------------------------------------------- 设备

    fun firstDevice(): Store.Dev? = store.devices("android").firstOrNull()

    fun saveAndConnect(dev: Store.Dev) {
        runCatching {
            store.putDevice("android", dev)
            store.setDef("android", dev.name)
        }
        connect(dev)
    }

    // ---------------------------------------------------------------- 连接

    fun connect(dev: Store.Dev) {
        disconnect()
        status = "连接中 ${dev.addr()}…"
        thread(name = "dsh-connect") {
            runCatching {
                val d = Dsh.open(dev, 12000, "dshconsole", "android")
                dsh = d
                connected = true
                hostName = d.hostName.ifEmpty { dev.name }
                status = "已连接 ${dev.addr()}"
                loadSessions()
            }.onFailure { e ->
                connected = false
                status = "连接失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun disconnect() {
        watching.set(false)
        watchThread = null
        runCatching { dsh?.close() }
        dsh = null
        connected = false
        streaming = false
        status = "未连接"
    }

    // ---------------------------------------------------------------- 会话

    fun refreshSessions() {
        if (!connected) return
        thread(name = "dsh-sessions") { loadSessions() }
    }

    private fun loadSessions() {
        val d = dsh ?: return
        runCatching {
            val r = d.request("sessions.list", JSONObject(), 20000, null)
            val arr = pickArray(r, "sessions", "items", "list", "data")
            val out = ArrayList<SessionItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = pick(o, "id", "sessionId", "key")
                val title = pick(o, "title", "name", "label").ifEmpty { id }
                if (id.isNotEmpty()) out.add(SessionItem(id, title))
            }
            sessions.clear()
            sessions.addAll(out)
            status = "已连接 · ${out.size} 个会话"
        }.onFailure { status = "会话列表失败：${it.message}" }
    }

    fun openSession(item: SessionItem) {
        currentSessionId = item.id
        messages.clear()
        streaming = false
        streamingId = null
        watch(item.id)
    }

    // ---------------------------------------------------------------- 订阅

    private fun watch(sessionId: String) {
        val d = dsh ?: return
        watching.set(true)
        runCatching {
            val reqId = d.begin("sessions.watch", JSONObject().put("sessionId", sessionId))
            watchThread = thread(name = "dsh-watch") {
                runCatching { d.await(reqId, 0, Dsh.EvtSink { evt, data -> onEvt(evt, data) }) }
                    .onFailure { if (connected) status = "订阅中断：${it.message}" }
                watching.set(false)
            }
        }.onFailure {
            watching.set(false)
            status = "订阅失败：${it.message}"
        }
    }

    private fun onEvt(evt: String, data: JSONObject) {
        when (evt) {
            "watch-start" -> status = "已订阅"
            "snapshot" -> {
                val arr = pickArray(data, "records", "messages", "items", "events")
                messages.clear()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { applyRecord(it) }
                }
            }
            "event" -> {
                val rec = data.optJSONObject("record") ?: data.optJSONObject("message") ?: data
                applyRecord(rec)
            }
            "delta" -> applyDelta(data)
            "tool-delta" -> applyToolDelta(data)
            "watch-end" -> {
                streaming = false
                streamingId = null
                status = "订阅结束"
            }
        }
    }

    // ---------------------------------------------------------------- 记录

    private fun applyRecord(rec: JSONObject) {
        when (pick(rec, "type", "kind", "event")) {
            "turn/start" -> {
                streaming = true
                // 本轮的第一条助手消息还没出现，先不建气泡
            }

            "turn/end", "turn/complete" -> {
                streaming = false
                streamingId?.let { id ->
                    val i = messages.indexOfLast { it.id == id }
                    if (i >= 0) messages[i] = messages[i].copy(streaming = false)
                }
                streamingId = null
            }

            "user/message" -> {
                val id = pick(rec, "id", "messageId").ifEmpty { "u${messages.size}" }
                messages.add(
                    ChatMessage(
                        id = id,
                        fromUser = true,
                        fragments = listOf(ChatFragment.TextFragment(pick(rec, "text", "content"))),
                    )
                )
            }

            "assistant/message" -> {
                val id = pick(rec, "id", "messageId").ifEmpty { "a${messages.size}" }
                val frags = ArrayList<ChatFragment>()
                pick(rec, "reasoning", "thinking").takeIf { it.isNotEmpty() }?.let {
                    frags.add(ChatFragment.ReasoningFragment(it))
                }
                frags.add(ChatFragment.TextFragment(pick(rec, "text", "content")))
                messages.add(ChatMessage(id = id, fromUser = false, fragments = frags))
            }

            "tool/call" -> {
                val id = pick(rec, "id", "callId", "toolCallId").ifEmpty { "t${messages.size}" }
                messages.add(
                    ChatMessage(
                        id = id,
                        fromUser = false,
                        fragments = listOf(
                            ChatFragment.ToolFragment(
                                name = pick(rec, "name", "tool", "toolName").ifEmpty { "tool" },
                                summary = pick(rec, "summary", "description", "input", "command"),
                                state = ToolState.Running,
                            )
                        ),
                    )
                )
            }

            "tool/result" -> {
                val id = pick(rec, "id", "callId", "toolCallId", "parentId")
                val okRaw = pick(rec, "ok", "success", "status")
                val ok = okRaw.isEmpty() || okRaw == "true" || okRaw == "ok" || okRaw == "success"
                val i = messages.indexOfLast {
                    it.fragments.any { f -> f is ChatFragment.ToolFragment } &&
                        (id.isEmpty() || it.id == id)
                }
                if (i >= 0) {
                    val old = messages[i]
                    messages[i] = old.copy(
                        fragments = old.fragments.map { f ->
                            if (f is ChatFragment.ToolFragment) {
                                f.copy(
                                    state = if (ok) ToolState.Ok else ToolState.Error,
                                    summary = f.summary.ifEmpty {
                                        pick(rec, "summary", "output", "result")
                                    },
                                )
                            } else f
                        }
                    )
                }
            }

            "system/message", "developer/message" -> {
                val t = pick(rec, "text", "content")
                if (t.isNotEmpty()) {
                    messages.add(
                        ChatMessage(
                            id = pick(rec, "id").ifEmpty { "s${messages.size}" },
                            fromUser = false,
                            fragments = listOf(ChatFragment.ReasoningFragment(t)),
                        )
                    )
                }
            }

            "approval/request" -> {
                // 需要用户确认的卡片 —— 先作为一条过程行呈现（后续接按钮）
                val t = pick(rec, "text", "prompt", "message")
                messages.add(
                    ChatMessage(
                        id = pick(rec, "id").ifEmpty { "ap${messages.size}" },
                        fromUser = false,
                        fragments = listOf(ChatFragment.ToolFragment("需要确认", t, ToolState.Running)),
                    )
                )
            }
        }
    }

    /** 流式增量：按 messageId 追加文本（字符只增不改 → 不触发整行重排） */
    private fun applyDelta(d: JSONObject) {
        val mid = pick(d, "messageId", "id").ifEmpty { streamingId ?: "s${messages.size}" }
        val text = pick(d, "text", "delta", "content")
        if (text.isEmpty()) return
        streaming = true
        streamingId = mid
        val idx = messages.indexOfLast { it.id == mid }
        if (idx < 0) {
            messages.add(
                ChatMessage(
                    id = mid,
                    fromUser = false,
                    streaming = true,
                    fragments = listOf(ChatFragment.TextFragment(text)),
                )
            )
            return
        }
        val old = messages[idx]
        val cur = old.fragments.filterIsInstance<ChatFragment.TextFragment>()
            .joinToString("") { it.text } + text
        messages[idx] = old.copy(fragments = listOf(ChatFragment.TextFragment(cur)), streaming = true)
    }

    /** 工具行的流式增量 */
    private fun applyToolDelta(d: JSONObject) {
        val id = pick(d, "id", "callId", "toolCallId").ifEmpty { return }
        val chunk = pick(d, "text", "delta", "output")
        if (chunk.isEmpty()) return
        val i = messages.indexOfLast { it.id == id }
        if (i < 0) return
        val old = messages[i]
        messages[i] = old.copy(
            fragments = old.fragments.map { f ->
                if (f is ChatFragment.ToolFragment) f.copy(summary = f.summary + chunk) else f
            }
        )
    }

    // ---------------------------------------------------------------- 发送

    fun send(text: String) {
        val d = dsh ?: run { status = "未连接"; return }
        val sid = currentSessionId ?: run { status = "还没有打开会话"; return }
        // 先本地上屏，等 host 回执
        messages.add(
            ChatMessage(
                id = "local${messages.size}",
                fromUser = true,
                fragments = listOf(ChatFragment.TextFragment(text)),
            )
        )
        streaming = true
        thread(name = "dsh-send") {
            runCatching {
                d.request(
                    "sessions.prompt",
                    JSONObject().put("sessionId", sid).put("text", text),
                    60000, null,
                )
                status = "已发送"
            }.onFailure {
                streaming = false
                status = "发送失败：${it.message}"
            }
        }
    }

    fun cancel() {
        val d = dsh ?: return
        val sid = currentSessionId ?: return
        thread(name = "dsh-cancel") {
            runCatching {
                d.request("sessions.cancel", JSONObject().put("sessionId", sid), 10000, null)
                streaming = false
            }.onFailure { status = "打断失败：${it.message}" }
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 从多个候选字段名里取第一个非空字符串 */
    private fun pick(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            if (o.has(k) && !o.isNull(k)) {
                val s = o.optString(k, "")
                if (s.isNotEmpty() && s != "null") return s
            }
        }
        return ""
    }

    private fun pickArray(o: JSONObject, vararg keys: String): JSONArray {
        for (k in keys) {
            o.optJSONArray(k)?.let { return it }
        }
        return JSONArray()
    }
}
