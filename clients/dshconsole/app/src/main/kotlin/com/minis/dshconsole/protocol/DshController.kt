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
    val workspaces = mutableStateListOf<WorkspaceItem>()
    var selectedWorkspaceId by mutableStateOf<String?>(null)
        private set

    /** 当前工作区下的会话（工作区用 sessionIds 反查；没有工作区就全部平铺） */
    val visibleSessions: List<SessionItem>
        get() {
            if (workspaces.isEmpty()) return sessions
            val ws = workspaces.firstOrNull { it.id == selectedWorkspaceId } ?: return sessions
            // 工作区没带 sessionIds，或反查不出任何会话 —— 都退回全部，避免列表空白
            if (ws.sessionIds.isEmpty()) return sessions
            val set = ws.sessionIds.toHashSet()
            val hit = sessions.filter { it.id in set }
            return hit.ifEmpty { sessions }
        }

    fun selectWorkspace(id: String) {
        selectedWorkspaceId = id
    }

    /** 树形展开：哪些工作区当前是展开的 */
    val expandedWorkspaces = mutableStateListOf<String>()

    fun isExpanded(id: String) = expandedWorkspaces.contains(id)

    /** 点工作区 = 展开/收起它的子列表（同时记成「当前工作区」） */
    fun toggleWorkspace(id: String) {
        selectedWorkspaceId = id
        if (!expandedWorkspaces.remove(id)) expandedWorkspaces.add(id)
    }

    /** 某个工作区下的会话 */
    fun sessionsOf(w: WorkspaceItem): List<SessionItem> {
        if (w.sessionIds.isEmpty()) return sessions      // 老 host 没给 ids：退化成全部
        val set = w.sessionIds.toHashSet()
        return sessions.filter { it.id in set }
    }
    val messages = mutableStateListOf<ChatMessage>()
    var currentSessionId by mutableStateOf<String?>(null)
        private set
    var streaming by mutableStateOf(false)
        private set

    data class SessionItem(
        val id: String,
        val title: String,
        val cwd: String = "",
        val running: Boolean = false,
    )

    /** workspaces.list 的公开投影：{ workspaceId, path, title, sessionIds[] } */
    data class WorkspaceItem(
        val id: String,
        val title: String,
        val path: String,
        val sessionIds: List<String>,
    )

    /** 正在串流的那条助手消息的 id */
    private var streamingId: String? = null

    /** 协议日志（数据管理页可见）—— 每帧一行，最多留 300 行 */
    val debugLog = mutableStateListOf<String>()

    private fun dbg(line: String) {
        val t = android.util.Log.d("DshProto", line)
        debugLog.add(line)
        while (debugLog.size > 300) debugLog.removeAt(0)
    }

    private fun trim(s: String, n: Int = 400): String =
        if (s.length <= n) s else s.substring(0, n) + "\u2026"


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
                dbg("connected ${dev.addr()} host=${d.hostName}")
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
        // 先拉工作区（它带 sessionIds，是分组的依据）
        runCatching {
            val wr = d.request("workspaces.list", JSONObject(), 20000, null)
            dbg("workspaces.list ← " + trim(wr.toString()))
            val wArr = pickArray(wr, "items", "workspaces")
            val ws = ArrayList<WorkspaceItem>()
            for (i in 0 until wArr.length()) {
                val o = wArr.optJSONObject(i) ?: continue
                val id = pick(o, "workspaceId", "id")
                if (id.isEmpty()) continue
                val idsArr = o.optJSONArray("sessionIds") ?: JSONArray()
                val ids = ArrayList<String>()
                for (j in 0 until idsArr.length()) idsArr.optString(j)?.let { if (it.isNotEmpty()) ids.add(it) }
                ws.add(
                    WorkspaceItem(
                        id = id,
                        title = pick(o, "title", "name").ifEmpty { pick(o, "path") },
                        path = pick(o, "path"),
                        sessionIds = ids,
                    )
                )
            }
            workspaces.clear()
            workspaces.addAll(ws)
            if (selectedWorkspaceId == null || ws.none { it.id == selectedWorkspaceId }) {
                selectedWorkspaceId = ws.firstOrNull()?.id
            }
            dbg("工作区 ${ws.size} 个，选中 ${selectedWorkspaceId ?: "-"}")
            ws.take(3).forEach { w ->
                dbg("  ws=${w.id} title=${w.title} sessionIds=${w.sessionIds.size} " +
                    "样本=${w.sessionIds.take(2)}")
            }
        }.onFailure { dbg("workspaces.list 失败：${it.message}") }

        runCatching {
            val r = d.request("sessions.list", JSONObject(), 20000, null)
            dbg("sessions.list ← " + trim(r.toString()))
            val arr = pickArray(r, "sessions", "items", "list", "data")
            val out = ArrayList<SessionItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = pick(o, "sessionId", "id", "key")
                val title = pick(o, "title", "name", "label").ifEmpty { id }
                val cwd = pick(o, "cwd", "path")
                val running = pick(o, "running") == "true"
                if (id.isNotEmpty()) out.add(SessionItem(id, title, cwd, running))
            }
            sessions.clear()
            sessions.addAll(out)
            dbg("会话 ${out.size} 个，样本 id=${out.take(2).map { it.id }}")
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
        dbg("watch → $sessionId")

        thread(name = "dsh-tail") {
            // ① 先拉历史（这才是消息的来源；老客户端同样用 sessions.tail）
            runCatching {
                val r = d.request(
                    "sessions.tail",
                    JSONObject().put("sessionId", sessionId),
                    25000, null,
                )
                dbg("tail ← ${trim(r.toString())}")
                val arr = pickArray(r, "records", "messages", "items", "events")
                messages.clear()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { applyRecord(it) }
                }
                dbg("tail 解析出 ${arr.length()} 条记录 / 消息数 ${messages.size}")
            }.onFailure { dbg("tail 失败：${it.message}") }
        }

        runCatching {
            // ② 订阅（只 begin，不用 await —— await(id, 0) 会立刻返回 null！）
            val reqId = d.begin("sessions.watch", JSONObject().put("sessionId", sessionId))
            dbg("watch begin id=$reqId")

            // ③ 独立线程跑事件泵，idleMs 给足（老客户端用 3600000）
            watchThread = thread(name = "dsh-watch") {
                runCatching {
                    d.pump(
                        Dsh.EvtSink { evt, data -> onEvt(evt, data) },
                        3_600_000,
                        Dsh.Stop { !watching.get() },
                    )
                }.onFailure { dbg("pump 结束：${it.message}") }
            }
        }.onFailure {
            watching.set(false)
            status = "订阅失败：${it.message}"
            dbg("watch begin 失败：${it.message}")
        }
    }

    private fun onEvt(evt: String, data: JSONObject) {
        when (evt) {
            "watch-start" -> { status = "已订阅"; dbg("evt watch-start") }
            "snapshot" -> {
                dbg("evt snapshot")
                val arr = pickArray(data, "records", "messages", "items", "events")
                messages.clear()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { applyRecord(it) }
                }
            }
            "event" -> {
                dbg("evt event")
                val rec = data.optJSONObject("record") ?: data.optJSONObject("message") ?: data
                applyRecord(rec)
            }
            "delta" -> { dbg("evt delta"); applyDelta(data) }
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

    /**
     * 把本地语言同步给 host —— 让 host 侧（会话标题、回复语言）也跟着变。
     * 方法名走多候选，host 不支持就静默跳过，不影响本地。
     */
    fun setHostLang(zh: Boolean) {
        val d = dsh ?: return
        thread(name = "dsh-lang") {
            val lang = if (zh) "zh-Hans" else "en"
            val params = JSONObject().put("lang", lang).put("language", lang)
            for (m in listOf("settings.set", "config.set", "host.settings")) {
                val ok = runCatching { d.request(m, params, 8000, null) }.isSuccess
                if (ok) {
                    status = if (zh) "已同步语言：中文" else "Language synced: English"
                    return@thread
                }
            }
            status = if (zh) "语言已本地切换（host 未提供设置接口）"
            else "Language switched locally (host has no settings API)"
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
