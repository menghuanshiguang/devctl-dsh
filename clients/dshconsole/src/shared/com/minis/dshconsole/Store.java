package com.minis.dshconsole;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 设备与偏好存储（SharedPreferences + JSON）。 */
public class Store {
    private final SharedPreferences sp;

    public static class Dev {
        public String name = "";
        public String host = "";
        public int port = 7788;
        /** 原版设置页（网页）的端口。0 = 用 host 报回来的（局域网下就是 7790）。
         *  走内网穿透时公网端口跟 7790 不是一回事，必须在这里显式填。 */
        public int webPort = 0;
        public String token = "";
        /** 证书指纹（devctl TLS TOFU 固定用） */
        public String pin = "";

        public String addr() {
            return host + ":" + port;
        }

        /** 设置页（网页窗口）的对外地址：穿透模式下用 webPort。 */
        public int webAddr() {
            return webPort > 0 ? webPort : 7790;
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("host", host);
            o.put("port", port);
            o.put("webPort", webPort);
            o.put("token", token);
            o.put("pin", pin);
            return o;
        }

        public static Dev from(JSONObject o) {
            Dev d = new Dev();
            d.name = o.optString("name");
            d.host = o.optString("host");
            d.port = o.optInt("port", 7788);
            d.webPort = o.optInt("webPort", 0);
            d.token = o.optString("token");
            d.pin = o.optString("pin");
            return d;
        }
    }

    public Store(Context c) {
        sp = c.getSharedPreferences("dshconsole", Context.MODE_PRIVATE);
    }

    public List<Dev> devices(String kind) {
        List<Dev> out = new ArrayList<Dev>();
        try {
            JSONArray a = new JSONArray(sp.getString("devs_" + kind, "[]"));
            for (int i = 0; i < a.length(); i++) {
                out.add(Dev.from(a.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public void saveDevices(String kind, List<Dev> list) {
        try {
            JSONArray a = new JSONArray();
            for (Dev d : list) {
                a.put(d.toJson());
            }
            sp.edit().putString("devs_" + kind, a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public void putDevice(String kind, Dev d) {
        List<Dev> list = devices(kind);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(d.name)) {
                list.set(i, d);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(d);
        saveDevices(kind, list);
    }

    public Dev find(String kind, String name) {
        for (Dev d : devices(kind)) {
            if (d.name.equals(name)) return d;
        }
        return null;
    }

    public void remove(String kind, String name) {
        List<Dev> list = devices(kind);
        List<Dev> keep = new ArrayList<Dev>();
        for (Dev d : list) {
            if (!d.name.equals(name)) keep.add(d);
        }
        saveDevices(kind, keep);
    }

    public String def(String kind) {
        return sp.getString("def_" + kind, "");
    }

    public void setDef(String kind, String name) {
        sp.edit().putString("def_" + kind, name).apply();
    }

    public String lastSession(String dev) {
        return sp.getString("sess_" + dev, "");
    }

    public void setLastSession(String dev, String id) {
        sp.edit().putString("sess_" + dev, id).apply();
    }

    public String lastWorkspace(String dev) {
        return sp.getString("ws_" + dev, "");
    }

    public void setLastWorkspace(String dev, String id) {
        sp.edit().putString("ws_" + dev, id).apply();
    }

    public void setPin(String name, String pin) {
        sp.edit().putString("pin_" + name, pin).apply();
    }

    public String pin(String name) {
        return sp.getString("pin_" + name, "");
    }

    public String get(String k, String dflt) {
        return sp.getString(k, dflt);
    }

    public void set(String k, String v) {
        sp.edit().putString(k, v).apply();
    }
}
