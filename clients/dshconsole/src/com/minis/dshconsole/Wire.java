package com.minis.dshconsole;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;

/** JSON Lines over TCP 分帧：超时保留半行，与 dshctl 的行为一致。 */
public class Wire {
    public static final Charset UTF8 = Charset.forName("UTF-8");

    private final Socket sock;
    private final InputStream in;
    private final OutputStream out;
    private byte[] buf = new byte[16384];
    private int len = 0;
    /** 读、写各自一把锁：写不能被「正在等对端回包的读」挡住（那会让发送凭空慢一截）。 */
    private final Object readLock = new Object();
    private final Object writeLock = new Object();

    public Wire(Socket s) throws Exception {
        sock = s;
        in = s.getInputStream();
        out = s.getOutputStream();
        sock.setTcpNoDelay(true);
    }

    public Socket socket() {
        return sock;
    }

    public void send(String line) throws Exception {
        synchronized (writeLock) {
            out.write((line + "\n").getBytes(UTF8));
            out.flush();
        }
    }

    private int findNewline() {
        for (int i = 0; i < len; i++) {
            if (buf[i] == '\n') return i;
        }
        return -1;
    }

    /** 返回一行（无换行符）；超时且无完整行返回 null；连接关闭抛异常。 */
    public String readLine(int timeoutMs) throws Exception {
        synchronized (readLock) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            int nl = findNewline();
            if (nl >= 0) {
                String line = new String(buf, 0, nl, UTF8);
                int rest = len - nl - 1;
                System.arraycopy(buf, nl + 1, buf, 0, rest);
                len = rest;
                return line;
            }
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) return null;
            try {
                sock.setSoTimeout((int) Math.max(1, remain));
                if (len == buf.length) {
                    byte[] nb = new byte[buf.length * 2];
                    System.arraycopy(buf, 0, nb, 0, len);
                    buf = nb;
                }
                int n = in.read(buf, len, buf.length - len);
                if (n < 0) throw new Exception("连接已被对端关闭");
                len += n;
            } catch (java.net.SocketTimeoutException e) {
                return null;
            }
        }
        }
    }

    public void close() {
        try {
            sock.shutdownOutput();
        } catch (Exception ignored) {
        }
        try {
            sock.setSoTimeout(300);
            byte[] tmp = new byte[4096];
            while (in.read(tmp) > 0) {
                // 排空，避免 RST
            }
        } catch (Exception ignored) {
        }
        try {
            sock.close();
        } catch (Exception ignored) {
        }
    }
}
