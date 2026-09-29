package com.minis.dshconsole;

import org.json.JSONArray;
import org.json.JSONObject;

/** 把 DSH 的字段拍平成一行文字（与 dshctl 的渲染保持一致的宽容度）。 */
public class DshConsole {

    public static String flattenModel(Object value) {
        if (value instanceof String) return (String) value;
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            Object chosen = o.opt("next");
            if (!(chosen instanceof JSONObject)) chosen = o.opt("lastUsed");
            if (chosen instanceof JSONObject) {
                JSONObject c = (JSONObject) chosen;
                String provider = c.optString("provider", "");
                String model = c.optString("model", "");
                if (provider.length() > 0 && model.length() > 0) return provider + "/" + model;
                if (model.length() > 0) return model;
            }
            String p = o.optString("provider", "");
            String m = o.optString("model", "");
            if (p.length() > 0 && m.length() > 0) return p + "/" + m;
            if (m.length() > 0) return m;
        }
        return null;
    }

    public static String flattenPermission(Object value) {
        if (value instanceof String) return (String) value;
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            String[] keys = {"preset", "name", "id", "mode", "profile"};
            for (String k : keys) {
                String v = o.optString(k, "");
                if (v.length() > 0) return v;
            }
            String cur = o.optString("current", "");
            if (cur.length() > 0) return cur;
        }
        return null;
    }

    public static String pretty(JSONObject o) {
        try {
            return o.toString(2);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    public static String clamp(String s, int max) {
        if (s == null) return "";
        s = s.replace("\r", "");
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n… (已截断 " + (s.length() - max) + " 字)";
    }

    public static String arrayText(JSONArray arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(arr.optString(i));
        }
        return sb.toString();
    }
}
