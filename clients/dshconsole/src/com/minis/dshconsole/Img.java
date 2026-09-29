package com.minis.dshconsole;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片：协议元数据 / 本地位图 / 按需取字节 / 选图压缩。
 * 记录里只带 {@code attachmentId} 之类的元数据，真要显示时再向 host 要；
 * {@code size=thumb} 走 704px、{@code size=full} 走 2048px，别把原图搬到手机上。
 */
public final class Img {
    /** 发出去前的字节上限：再大就会撞上 devctl 的单帧上限。 */
    public static final int MAX_SEND_BYTES = 1_500_000;
    private static final int CACHE_MAX = 24;
    private static final int URI_READ_LIMIT = 24 * 1024 * 1024;

    public String attachmentId = "";
    public String mediaType = "image/png";
    public String name = "";
    /** host 工作区里的路径（markdown 图片 / 文件引用指过来的）：取字节时先落库再取。 */
    public String path = "";
    public int bytes, width, height;
    /** 只有「马上要发出去」的图才带 base64 字节。 */
    public String data;
    /** 已经拿到的位图（本地刚发的或缓存命中的）。 */
    public Bitmap bmp;

    public static Img from(JSONObject o) {
        Img img = new Img();
        if (o == null) return img;
        img.attachmentId = o.optString("attachmentId", o.optString("id", ""));
        img.mediaType = o.optString("mediaType", "image/png");
        img.name = o.optString("name", "");
        img.bytes = o.optInt("bytes", 0);
        img.width = o.optInt("width", 0);
        img.height = o.optInt("height", 0);
        img.path = o.optString("path", "");
        img.data = o.optString("data", "");
        if (img.data.length() == 0) img.data = null;
        return img;
    }

    public static ArrayList<Img> list(JSONArray a) {
        ArrayList<Img> out = new ArrayList<Img>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(from(o));
        }
        return out;
    }

    public JSONObject sendPayload() throws org.json.JSONException {
        JSONObject p = new JSONObject();
        p.put("mediaType", mediaType);
        p.put("data", data == null ? "" : data);
        if (name.length() > 0) p.put("name", name);
        return p;
    }

    /** 记录里回传的元数据（取字节时要原样带上，host 靠它重建附件引用）。 */
    public JSONObject ref() {
        JSONObject p = new JSONObject();
        try {
            p.put("attachmentId", attachmentId);
            p.put("mediaType", mediaType);
            p.put("bytes", bytes);
            p.put("width", width);
            p.put("height", height);
        } catch (org.json.JSONException ignored) {
        }
        return p;
    }

    /** 看后缀像不像能显示的图（host 那边也只认这几种）。 */
    public static boolean looksImage(String p) {
        if (p == null) return false;
        String t = p.toLowerCase();
        int q = t.indexOf('?');
        if (q > 0) t = t.substring(0, q);
        return t.endsWith(".png") || t.endsWith(".jpg") || t.endsWith(".jpeg")
                || t.endsWith(".webp") || t.endsWith(".gif");
    }

    public static String sizeText(int n) {
        if (n <= 0) return "";
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return Math.round(n / 1024.0) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0);
    }

    public String label() {
        String s = sizeText(bytes);
        if (width > 0 && height > 0) s = width + "×" + height + (s.length() > 0 ? " · " + s : "");
        return s;
    }

    // ==================== 位图缓存 + 取字节 ====================

    private static final LinkedHashMap<String, Bitmap> CACHE =
            new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    if (size() <= CACHE_MAX) return false;
                    Bitmap gone = eldest.getValue();
                    if (gone != null && !gone.isRecycled()) gone.recycle();
                    return true;
                }
            };

    /** 真正的取字节动作由聊天页注入：它才知道该用哪条连接问 host。 */
    public interface Loader {
        /** size: thumb / full。 */
        Bitmap load(Img img, String size) throws Exception;
    }

    public interface Cb {
        void done(Bitmap b);
    }

    private static Loader loader;

    public static void setLoader(Loader l) {
        loader = l;
    }

    private static String key(Img img, String size) {
        return size + ":" + (img.attachmentId.length() > 0 ? img.attachmentId : "local:" + img.hashCode());
    }

    public static Bitmap cached(Img img, String size) {
        if (size.equals("thumb") && img.bmp != null && !img.bmp.isRecycled()) return img.bmp;
        synchronized (CACHE) {
            return CACHE.get(key(img, size));
        }
    }

    public static void put(Img img, String size, Bitmap b) {
        if (b == null) return;
        if (size.equals("thumb")) img.bmp = b;
        synchronized (CACHE) {
            CACHE.put(key(img, size), b);
        }
    }

    /** base64 → 位图；长边超过 maxEdge 就降采样，避免手机 OOM。 */
    public static Bitmap decode(String base64, int maxEdge) {
        if (base64 == null || base64.length() == 0) return null;
        byte[] raw;
        try {
            raw = Base64.decode(base64, Base64.DEFAULT);
        } catch (Throwable t) {
            return null;
        }
        return decodeBytes(raw, maxEdge);
    }

    /** 本来就有字节的图（本地选的图）走这条，别再 base64 绕一圈。 */
    public static Bitmap decodeBytes(byte[] raw, int maxEdge) {
        if (raw == null || raw.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        int sample = 1;
        int edge = Math.max(bounds.outWidth, bounds.outHeight);
        while (edge / sample > maxEdge * 2 && sample < 16) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        try {
            return BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 全屏查看 ====================

    /** 点缩略图 → 全屏大图：先给缩略图顶住，full 到了再替换；点任意处关闭。 */
    public static void view(final Activity act, final Img img) {
        final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF000000);
        box.setGravity(Gravity.CENTER);

        final ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(true);
        Bitmap first = cached(img, "thumb");
        if (first == null) first = cached(img, "full");
        if (first != null) iv.setImageBitmap(first);
        box.addView(iv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = Ui.row(act);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(act, 14), Ui.dp(act, 10), Ui.dp(act, 14), Ui.dp(act, 18));
        TextView info = Ui.tv(act, img.label(), 12.5f, 0xFFBBBBBB);
        bar.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView save = Ui.tv(act, "保存", 13.5f, 0xFFFFFFFF);
        save.setPadding(Ui.dp(act, 14), Ui.dp(act, 7), Ui.dp(act, 14), Ui.dp(act, 7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(Ui.dp(act, 16));
        save.setBackground(bg);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Bitmap b = cached(img, "full");
                if (b == null) b = cached(img, "thumb");
                if (b == null) {
                    toast(act, "图片还没加载好");
                    return;
                }
                String err = saveToGallery(act, b, img.name);
                toast(act, err == null ? "已保存到相册" : err);
            }
        });
        bar.addView(save);
        box.addView(bar);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                d.dismiss();
            }
        });
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        d.show();
        box.getLayoutParams();
        // 大图异步补上：缩略图先顶住，避免点开一片黑
        if (cached(img, "full") == null) {
            resolve(img, "full", new Cb() {
                public void done(Bitmap b) {
                    if (b != null) iv.setImageBitmap(b);
                }
            });
        }
    }

    private static void toast(Context ctx, String msg) {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Android 10+ 走 MediaStore，不用任何存储权限。 */
    public static String saveToGallery(Context ctx, Bitmap b, String name) {
        if (Build.VERSION.SDK_INT < 29) return "系统版本过低，暂不支持保存";
        String file = name != null && name.length() > 0 ? name : "dsh-" + System.currentTimeMillis() + ".jpg";
        if (file.indexOf('.') < 0) file = file + ".jpg";
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, file);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DSH");
            Uri uri = ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return "保存失败：无法创建相册条目";
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            if (os == null) return "保存失败：无法写入";
            b.compress(Bitmap.CompressFormat.JPEG, 92, os);
            os.close();
            return null;
        } catch (Throwable t) {
            return "保存失败：" + t.getMessage();
        }
    }

    /** 把缩略图塞进 ImageView：先给缓存，没有就留占位文字，取到了自己替换。 */
    public void into(final ImageView iv, final TextView placeholder) {
        Bitmap hit = cached(this, "thumb");
        if (hit != null) {
            iv.setImageBitmap(hit);
            if (placeholder != null) placeholder.setVisibility(View.GONE);
            return;
        }
        if (attachmentId.length() == 0 && path.length() == 0) return;
        resolve(this, "thumb", new Cb() {
            public void done(Bitmap b) {
                if (b == null) {
                    if (placeholder != null) placeholder.setText("取图失败");   // 元数据到了、字节没到
                    return;
                }
                iv.setImageBitmap(b);
                if (placeholder != null) placeholder.setVisibility(View.GONE);
            }
        });
    }

    /** 先给缓存，缓存没有再去 host 取；回调一定在主线程。 */
    public static void resolve(final Img img, final String size, final Cb cb) {
        final Bitmap hit = cached(img, size);
        if (hit != null) {
            cb.done(hit);
            return;
        }
        if (img.attachmentId.length() == 0 || loader == null) return;
        new Thread(new Runnable() {
            public void run() {
                Bitmap b = null;
                try {
                    b = loader.load(img, size);
                } catch (Exception ignored) {
                }
                if (b != null) put(img, size, b);
                final Bitmap got = b;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        cb.done(got);
                    }
                });
            }
        }).start();
    }

    // ==================== 选图 → 可发送的字节 ====================

    /** 相册/选择器回来的 Uri → 能直接发的图：小图原样发，大图压到 1600px / 体积达标。 */
    public static Img fromUri(Context ctx, Uri uri) throws Exception {
        byte[] raw = readAll(ctx, uri);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        String mime = ctx.getContentResolver().getType(uri);
        String nm = lastSegment(uri);
        int edge = Math.max(bounds.outWidth, bounds.outHeight);
        boolean small = raw.length <= MAX_SEND_BYTES && (edge <= 2048 || edge == 0);
        if (small && (mime == null || mime.startsWith("image/"))) {
            Bitmap b = decodeBytes(raw, 2048);
            if (b == null) throw new Exception("这张图打不开");
            Img img = new Img();
            img.mediaType = mime == null ? "image/jpeg" : mime;
            img.name = nm;
            img.bytes = raw.length;
            img.width = b.getWidth();
            img.height = b.getHeight();
            img.data = Base64.encodeToString(raw, Base64.NO_WRAP);
            img.bmp = b;
            return img;
        }
        Bitmap src = decodeBytes(raw, 1600);
        if (src == null) throw new Exception("这张图打不开");
        byte[] out = null;
        int quality = 86;
        while (true) {
            out = jpegFrom(src, quality);
            if (out != null && out.length <= MAX_SEND_BYTES) break;
            quality -= 12;
            if (quality < 62) break;
        }
        if (out == null || out.length > MAX_SEND_BYTES) {
            Bitmap smaller = decodeBytes(raw, 1100);
            if (smaller != null) {
                src = smaller;
                out = jpegFrom(smaller, 74);
            }
        }
        if (out == null || out.length > MAX_SEND_BYTES) throw new Exception("这张图压不到能发送的大小");
        Img img = new Img();
        img.mediaType = "image/jpeg";
        img.name = nm.length() > 0 ? nm : "image.jpg";
        img.bytes = out.length;
        img.width = src.getWidth();
        img.height = src.getHeight();
        img.data = Base64.encodeToString(out, Base64.NO_WRAP);
        img.bmp = src;
        return img;
    }

    private static byte[] readAll(Context ctx, Uri uri) throws Exception {
        InputStream is = ctx.getContentResolver().openInputStream(uri);
        if (is == null) throw new Exception("读不到这张图");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int total = 0;
        try {
            int n;
            while ((n = is.read(buf)) > 0) {
                total += n;
                if (total > URI_READ_LIMIT) throw new Exception("图片太大（超过 24 MB）");
                raw.write(buf, 0, n);
            }
        } finally {
            is.close();
        }
        if (total == 0) throw new Exception("这张图是空的");
        return raw.toByteArray();
    }

    private static byte[] jpegFrom(Bitmap b, int quality) {
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, quality, os);
            return os.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String lastSegment(Uri uri) {
        String s = uri == null ? "" : uri.getLastPathSegment();
        if (s == null) return "";
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }
}
