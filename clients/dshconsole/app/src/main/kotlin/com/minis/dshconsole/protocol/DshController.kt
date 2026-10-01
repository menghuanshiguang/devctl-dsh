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
 * 协议层桥接 —— 把老的 Java 协议实现（Dsh/Store/Wire）接到新的 Compose UI 上。
 *
 * 协议（devctl-dsh Host 插件，JSON Lines over TCP，token 鉴权）：
 *   sessions.list    列出会话
 *   sessions.prompt  发送消息    { sessionId, text, mode?, images? }
 *   sessions.watch   订阅会话    → watch-start / snapshot / event / delta / tool-delta / watch-end
 *   sessions.cancel  打断
 *   sessions.inbox   信箱（排队项）
 *
 * 记录类型（event 里）：
 *   user/message · assistant/message · tool/call · tool/result
 *   turn/start · turn/end · system/message · developer/message · approval/request
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

    val sessionTitles = mutableStateListOf<String>()
    val messages = mutableStateListOf<ChatMessage>()
    var currentSessionId by mutableStateOf<String?>(null)
        private set

    // ---------------------------------------------------------------- 设备

    /** 取第一台已保存的设备；没有就返回 null（UI 会提示去配） */
    fun firstDevice(): Store.Dev? = store.devices("android").firstOrNull()

    /** 保存设备并立即连接（对应老客户端的「添加/编辑设备」） */
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
        status = "未连接"
    }

    // ---------------------------------------------------------------- 会话

    private fun loadSessions() {
        val d = dsh ?: return
        runCatching {
            val r = d.request("sessions.list", JSONObject(), 20000, null)
            val arr: JSONArray = r.optJSONArray("sessions") ?: r.optJSONArray("items") ?: JSONArray()
            val titles = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("title").ifEmpty { o.optString("name") }
                if (t.isNotEmpty()) titles.add(t)
            }
            sessionTitles.clear()
            sessionTitles.addAll(titles)
        }.onFailure { status = "会话列表失败：${it.message}" }
    }

    /** 打开某个会话并开始订阅（按标题匹配，找不到就原样当 id 用） */
    fun openSession(idOrTitle: String) {
        val d = dsh ?: return
        val id = resolveSessionId(d, idOrTitle) ?: idOrTitle
        currentSessionId = id
        messages.clear()
        watch(id)
    }

    private fun resolveSessionId(d: Dsh, key: String): String? = runCatching {
        val r = d.request("sessions.list", JSONObject(), 20000, null)
        val arr = r.optJSONArray("sessions") ?: r.optJSONArray("items") ?: return@runCatching null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("title").ifEmpty { o.optString("name") }
            if (t == key) return@runCatching o.optString("id").ifEmpty { o.optString("sessionId") }
        }
        null
    }.getOrNull()

    // ---------------------------------------------------------------- 订阅

    private fun watch(sessionId: String) {
        val d = dsh ?: return
        if (watching.getAndSet(true)) {
            return  // 已经在看别的会话；真实实现应先取消旧订阅
        }
        runCatching {
            val params = JSONObject().put("sessionId", sessionId)
            val reqId = d.begin("sessions.watch", params)
            watchThread = thread(name = "dsh-watch") {
                runCatching {
                    d.await(reqId, 0, Dsh.EvtSink { evt, data -> onEvt(evt, data) })
                }.onFailure { /* 连接断了，UI 上会显示状态 */ }
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
                val arr = data.optJSONArray("records") ?: data.optJSONArray("messages") ?: return
                messages.clear()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { appendRecord(it) }
                }
            }
            "event" -> data.optJSONObject("record")?.let { appendRecord(it) }
            "delta" -> appendDelta(data)
            "tool-delta" -> Unit
            "watch-end" -> status = "订阅结束"
        }
    }

    /** 把一条完整记录转成一条消息 */
    private fun appendRecord(rec: JSONObject) {
        val type = rec.optString("type")
        val fromUser = type == "user/message"
        val text = rec.optString("text")
        val id = rec.optString("id").ifEmpty { "r${messages.size}" }
        when (type) {
            "user/message" -> messages.add(
                ChatMessage(id = id, fromUser = true, fragments = listOf(ChatFragment.TextFragment(text)))
            )
            "assistant/message" -> messages.add(
                ChatMessage(id = id, fromUser = false, fragments = listOf(ChatFragment.TextFragment(text)))
            )
            "tool/call" -> messages.add(
                ChatMessage(
                    id = id, fromUser = false,
                    fragments = listOf(
                        ChatFragment.ToolFragment(
                            name = rec.optString("name").ifEmpty { "tool" },
                            summary = rec.optString("summary").ifEmpty { rec.optString("input") },
                            state = ToolState.Running,
                        )
                    ),
                )
            )
            else -> Unit
        }
    }

    /** 流式增量：同一 messageId 追加文本，遵循「字符只增不改」 */
    private fun appendDelta(d: JSONObject) {
        val mid = d.optString("messageId").ifEmpty { d.optString("id") }
        val text = d.optString("text").ifEmpty { d.optString("delta") }
        if (text.isEmpty()) return
        val idx = messages.indexOfLast { it.id == mid }
        if (idx < 0) {
            messages.add(
                ChatMessage(
                    id = mid.ifEmpty { "s${messages.size}" },
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

    // ---------------------------------------------------------------- 发送

    fun send(text: String) {
        val d = dsh ?: run { status = "未连接"; return }
        val sid = currentSessionId
        if (sid == null) {
            status = "还没有打开会话"
            return
        }
        // 先本地上屏，等 host 回执
        messages.add(
            ChatMessage(
                id = "local${messages.size}",
                fromUser = true,
                fragments = listOf(ChatFragment.TextFragment(text)),
            )
        )
        thread(name = "dsh-send") {
            runCatching {
                val params = JSONObject()
                    .put("sessionId", sid)
                    .put("text", text)
                d.request("sessions.prompt", params, 60000, null)
                status = "已发送"
            }.onFailure { status = "发送失败：${it.message}" }
        }
    }

    fun cancel() {
        val d = dsh ?: return
        val sid = currentSessionId ?: return
        thread(name = "dsh-cancel") {
            runCatching { d.request("sessions.cancel", JSONObject().put("sessionId", sid), 10000, null) }
        }
    }
}
