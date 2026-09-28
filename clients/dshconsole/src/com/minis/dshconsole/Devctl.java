package com.minis.dshconsole;

import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** devctl agent 控制端（TLS + JSON Lines，自签证书 TOFU 固定）。 */
public class Devctl {
    public static class Remote extends Exception {
        public Devctl.Remote self() {
            return this;
        }

        public Remote(String m) {
            super(m);
        }
    }

    public interface EvtSink {
        void onEvt(JSONObject frame);
    }

    public final Store.Dev dev;
    private final Wire w;
    private int counter = 0;
    public String agentVersion = "";
    public String agentDevice = "";
    /** 本次握手看到的证书指纹（可存起来做 TOFU） */
    public String observedPin = "";

    private Devctl(Store.Dev d, Wire wire) {
        dev = d;
        w = wire;
    }

    public static String sha256Pin(X509Certificate cert) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(cert.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", d[i]));
        }
        return sb.toString();
    }

    public static Devctl open(Store.Dev d, String knownPin, boolean trustNew, int timeoutMs)
            throws Exception {
        final X509Certificate[] seen = new X509Certificate[1];
        final String pinCheck = knownPin == null || knownPin.length() == 0 ? null : knownPin;
        final boolean allowNew = trustNew;
        TrustManager tm = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("无证书");
                seen[0] = chain[0];
                if (pinCheck != null) {
                    String got;
                    try {
                        got = sha256Pin(chain[0]);
                    } catch (Exception e) {
                        throw new CertificateException("证书解析失败");
                    }
                    if (!got.equalsIgnoreCase(pinCheck)) {
                        throw new CertificateException("证书指纹与已信任的不一致（可能被替换）");
                    }
                } else if (!allowNew) {
                    throw new CertificateException("尚无已信任的证书指纹");
                }
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket();
        try {
            s.connect(new InetSocketAddress(d.host, d.port), timeoutMs);
            s.startHandshake();
        } catch (Exception e) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
            throw new Exception("TLS 连接 " + d.addr() + " 失败: " + e.getMessage());
        }
        Devctl c = new Devctl(d, new Wire(s));
        if (seen[0] != null) c.observedPin = sha256Pin(seen[0]);
        JSONObject hello = new JSONObject();
        hello.put("t", "hello");
        hello.put("token", d.token);
        hello.put("name", "dshconsole");
        c.w.send(hello.toString());
        String line = c.w.readLine(timeoutMs);
        if (line == null) throw new Exception("hello 无响应（agent 版本过旧可能导致卡住）");
        JSONObject ack = new JSONObject(line);
        if (!ack.optBoolean("ok", false)) {
            throw new Exception("鉴权失败: " + ack.optString("stderr", "bad token"));
        }
        c.agentVersion = ack.optString("version", "");
        c.agentDevice = ack.optString("device", "");
        return c;
    }

    public boolean alive() {
        return w.socket() != null && !w.socket().isClosed() && w.socket().isConnected();
    }

    public void close() {
        w.close();
    }

    public JSONObject cmd(String method, String[] args, String data, int timeoutMs, EvtSink sink)
            throws Exception {
        int id = ++counter;
        JSONObject o = new JSONObject();
        o.put("t", "cmd");
        o.put("id", id);
        o.put("method", method);
        if (args != null && args.length > 0) {
            org.json.JSONArray a = new org.json.JSONArray();
            for (String s : args) a.put(s);
            o.put("args", a);
        }
        if (data != null && data.length() > 0) o.put("data", data);
        w.send(o.toString());
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) throw new Remote(method + " 超时");
            String line = w.readLine((int) Math.max(50, Math.min(remain, 800)));
            if (line == null) continue;
            if (line.trim().isEmpty()) continue;
            JSONObject f = new JSONObject(line);
            String t = f.optString("t");
            if ("evt".equals(t)) {
                if (sink != null) sink.onEvt(f);
                continue;
            }
            if ("pong".equals(t)) continue;
            if (f.optInt("id", -1) == id) return f;
        }
    }

    /** 只发命令不等待（用于 logcat 这类流式命令）。 */
    public int send(String method, String[] args) throws Exception {
        int id = ++counter;
        JSONObject o = new JSONObject();
        o.put("t", "cmd");
        o.put("id", id);
        o.put("method", method);
        if (args != null && args.length > 0) {
            org.json.JSONArray a = new org.json.JSONArray();
            for (String s : args) a.put(s);
            o.put("args", a);
        }
        w.send(o.toString());
        return id;
    }

    /** 泵事件直到 stop() 为真或连接断开。 */
    public void pump(EvtSink sink, Dsh.Stop stop) {
        try {
            while (stop == null || !stop.stop()) {
                String line = w.readLine(500);
                if (line == null) continue;
                if (line.trim().isEmpty()) continue;
                JSONObject f = new JSONObject(line);
                if ("evt".equals(f.optString("t")) && sink != null) sink.onEvt(f);
            }
        } catch (Exception ignored) {
        }
    }

    public static String out(JSONObject res) {
        StringBuilder sb = new StringBuilder();
        String so = res.optString("stdout", "");
        String se = res.optString("stderr", "");
        if (so.length() > 0) sb.append(so);
        if (se.length() > 0) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("[stderr] ").append(se);
        }
        if (sb.length() == 0) sb.append("(无输出) rc=").append(res.optInt("rc", 0));
        return sb.toString();
    }
}
