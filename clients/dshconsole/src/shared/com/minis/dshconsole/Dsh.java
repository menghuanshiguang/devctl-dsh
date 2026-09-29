package com.minis.dshconsole;

import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Socket;

/** devctl-dsh Host 插件的控制端实现（JSON Lines over TCP，token 鉴权）。 */
public class Dsh {
    public static class Remote extends Exception {
        public String code;

        public Remote(String code, String message) {
            super(message);
            this.code = code == null ? "error" : code;
        }
    }

    public interface EvtSink {
        void onEvt(String evt, JSONObject data);
    }

    public final Store.Dev dev;
    private volatile Wire w;                 // 被掐了要能换一根
    private int counter = 0;
    private volatile long lastOkAt = 0;
    private String clientName = "dshconsole";
    private String clientPlatform = "android";
    public JSONObject hello;
    public String version = "";
    public String hostName = "";
    public String hostPlatform = "";

    private Dsh(Store.Dev d, Wire wire) {
        dev = d;
        w = wire;
    }

    public static Dsh open(Store.Dev d, int timeoutMs, String clientName, String platform) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(d.host, d.port), timeoutMs);
        } catch (Exception e) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
            throw new Exception("连接 " + d.addr() + " 失败: " + e.getMessage());
        }
        Dsh c = new Dsh(d, new Wire(s));
        c.clientName = clientName;
        c.clientPlatform = platform;
        c.helloOn(c.w, timeoutMs);          // 这条连接直接握手好（流式 watch 会用）
        c.lastOkAt = System.currentTimeMillis();
        JSONObject res = c.hello;
        JSONObject host = res == null ? null : res.optJSONObject("host");
        if (host != null) {
            c.hostName = host.optString("name", host.optString("hostname", ""));
            c.hostPlatform = host.optString("platform", "");
        } else {
            c.hostName = res.optString("hostName", res.optString("host", ""));
            c.hostPlatform = res.optString("platform", "");
        }
        return c;
    }

    public boolean alive() {
        return w.socket() != null && !w.socket().isClosed() && w.socket().isConnected();
    }

    public void close() {
        w.close();
    }

    private JSONObject unwrap(JSONObject frame) throws Remote {
        if (frame.optBoolean("ok", false)) {
            JSONObject r = frame.optJSONObject("result");
            return r == null ? new JSONObject() : r;
        }
        JSONObject err = frame.optJSONObject("error");
        String code = err == null ? "error" : err.optString("code", "error");
        String msg = err == null ? frame.optString("message", "未知错误") : err.optString("message", "未知错误");
        throw new Remote(code, msg);
    }

    /**
     * 换一根新连接。dsh 的长连接空闲约 26-30s 会被掐（errno 103 / 32），
     * 掐掉之后所有写入都发不出去，而且不会报错 —— 所以写之前必须先确认还活着。
     */
    public synchronized void reconnect() throws Exception {
        Dsh fresh = open(dev, 8000, clientName, clientPlatform);
        Wire old = this.w;
        this.w = fresh.w;
        this.hello = fresh.hello;
        this.version = fresh.version;
        this.hostName = fresh.hostName;
        this.hostPlatform = fresh.hostPlatform;
        if (old != null) {
            try {
                old.close();
            } catch (Exception ignored) {
            }
        }
    }

    private final Object idLock = new Object();

    public int begin(String method, JSONObject params) throws Exception {
        int id;
        synchronized (idLock) {
            id = ++counter;
        }
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("method", method);
        o.put("params", params == null ? new JSONObject() : params);
        if (!alive()) {
            reconnect();                       // 往死 socket 上写 = 石沉大海
        }
        try {
            w.send(o.toString());
        } catch (Exception e) {
            reconnect();                       // 对端刚断：换一根重试一次
            w.send(o.toString());
        }
        return id;
    }

    /** 等到 id 对应的响应；期间收到的 evt 交给 sink。超时返回 null。 */
    public JSONObject await(int id, int timeoutMs, EvtSink sink) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) return null;
            String line = w.readLine((int) Math.max(50, Math.min(remain, 800)));
            if (line == null) continue;
            if (line.trim().isEmpty()) continue;
            JSONObject frame = new JSONObject(line);
            if (frame.has("evt")) {
                if (sink != null) sink.onEvt(frame.optString("evt"), frame.optJSONObject("data"));
                continue;
            }
            if (frame.optInt("id", -1) == id) return unwrap(frame);
            // 别的命令的响应（并发场景）：忽略
        }
    }

    public JSONObject request(String method, JSONObject params, int timeoutMs, EvtSink sink) throws Exception {
        // 与 dshctl 一致：一条命令一条短连接，跑完就断（长连接空闲会被掐，且无法预知时机）
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Wire conn = null;
            try {
                conn = dial(Math.min(timeoutMs, 8000));
                if (!"hello".equals(method)) {
                    helloOn(conn, timeoutMs);
                }
                int id = sendOn(conn, method, params);
                JSONObject r = awaitOn(conn, id, timeoutMs, sink);
                if (r == null) {
                    throw new Remote("timeout", method + " 响应超时");
                }
                if ("hello".equals(method)) {
                    applyHello(r);
                }
                lastOkAt = System.currentTimeMillis();
                return r;
            } catch (Remote e) {
                lastOkAt = System.currentTimeMillis();
                throw e;
            } catch (Exception e) {
                last = e;
                if (attempt == 0) {
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ignored) {
                    }
                    continue;
                }
                throw e;
            } finally {
                if (conn != null) {
                    conn.close();
                }
            }
        }
        throw last;
    }

    /** 新开一条 TCP 连接（不握手）。 */
    private Wire dial(int connectMs) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(dev.host, dev.port), connectMs);
        } catch (Exception e) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
            throw new Exception("连接 " + dev.addr() + " 失败: " + e.getMessage());
        }
        return new Wire(s);
    }

    /** 在指定连接上完成 hello 握手。 */
    private void helloOn(Wire conn, int timeoutMs) throws Exception {
        JSONObject p = new JSONObject();
        p.put("token", dev.token);
        p.put("client", clientName);
        JSONObject dv = new JSONObject();
        dv.put("name", dev.name);
        dv.put("platform", clientPlatform);
        dv.put("version", "1.0");
        dv.put("cwd", "");
        p.put("device", dv);
        int id = sendOn(conn, "hello", p);
        JSONObject r = awaitOn(conn, id, timeoutMs, null);
        if (r == null) {
            throw new Remote("timeout", "hello 无响应");
        }
        applyHello(r);
    }

    private void applyHello(JSONObject res) {
        hello = res;
        version = res.optString("version", "");
        JSONObject host = res.optJSONObject("host");
        if (host != null) {
            hostName = host.optString("name", host.optString("hostname", ""));
            hostPlatform = host.optString("platform", "");
        } else {
            hostName = res.optString("hostName", res.optString("host", ""));
            hostPlatform = res.optString("platform", "");
        }
    }

    private int sendOn(Wire conn, String method, JSONObject params) throws Exception {
        int id = ++counter;
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("method", method);
        o.put("params", params == null ? new JSONObject() : params);
        conn.send(o.toString());
        return id;
    }

    private JSONObject awaitOn(Wire conn, int id, int timeoutMs, EvtSink sink) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                return null;
            }
            String line = conn.readLine((int) Math.max(50, Math.min(remain, 800)));
            if (line == null) {
                continue;
            }
            if (line.trim().length() == 0) {
                continue;
            }
            JSONObject frame = new JSONObject(line);
            if (frame.has("evt")) {
                if (sink != null) {
                    sink.onEvt(frame.optString("evt"), frame.optJSONObject("data"));
                }
                continue;
            }
            if (frame.optInt("id", -1) == id) {
                return unwrap(frame);
            }
        }
    }

    public interface FrameSink {
        void onFrame(JSONObject frame);
    }

    /** 纯事件泵：读帧直到 idleMs 内无事件或 shouldStop。 */
    public void pump(EvtSink sink, int idleMs, Stop stop) throws Exception {
        final EvtSink s = sink;
        pumpFrames(new FrameSink() {
            public void onFrame(JSONObject frame) {
                if (frame.has("evt")) s.onEvt(frame.optString("evt"), frame.optJSONObject("data"));
            }
        }, idleMs, stop);
    }

    /** 逐帧泵（含响应帧）：读帧直到 idleMs 内无帧或 shouldStop。 */
    public void pumpFrames(FrameSink sink, int idleMs, Stop stop) throws Exception {
        long last = System.currentTimeMillis();
        while (true) {
            if (stop != null && stop.stop()) return;
            String line = w.readLine(400);
            if (line == null) {
                if (System.currentTimeMillis() - last > idleMs) return;
                continue;
            }
            if (line.trim().isEmpty()) continue;
            last = System.currentTimeMillis();
            JSONObject frame = new JSONObject(line);
            sink.onFrame(frame);
        }
    }

    public interface Stop {
        boolean stop();
    }

    public static String text(JSONObject o, String key, String dflt) {
        String v = o == null ? null : o.optString(key, null);
        return v == null || v.length() == 0 ? dflt : v;
    }
}
