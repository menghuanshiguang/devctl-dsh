package com.minis.dshconsole;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 设计令牌 + 控件工厂。
 * 稳定感的来源全在这儿：3 层表面、1 条发丝线、1 套字级、1 套间距/圆角、统一按压反馈。
 * 任何地方想写 0xFF…… 之前，先看这里有没有现成的。
 */
public class Ui {

    // ─── 表面：三层，深/浅两套（applyTheme 里按系统模式赋值）───
    public static int BG, SURF, SURF2, SURF3;
    public static int STROKE, STROKE2, PRESS;
    public static int TEXT, DIM, MUT;
    public static int ACCENT, MINE, THEIRS, GREEN, RED, AMBER, VIOLET;
    public static int CODE_BG, CODE_FG, ICODE_BG, ICODE_FG;
    public static int TINT_TOOL, TINT_ERR, TINT_INJ;
    public static int PANEL, PANEL2;                 // 兼容旧名
    public static int CARD, CHIP_BG, CHIP_BD;        // 输入卡片底 / 胶囊底 / 胶囊描边

    /** 自己画的软阴影底：view 的 padding 正好当"呼吸位"，shadowLayer 从内容区（= 卡片那一圈）往外晕开。
     *  用它替 setElevation —— 又宽又扁的 view 走 spot shadow 只会冒出四角，直边被父层裁掉，调不动。
     *  用法：v.setPadding(p,p,p,p); v.setBackground(new ShadowBg(act, 22, 8, 3, 0x1F000000, Ui.CARD));
     *        v.setLayerType(View.LAYER_TYPE_SOFTWARE, null);   // 硬件层会忽略 shadowLayer */
    public static class ShadowBg extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float rad;

        public ShadowBg(Context c, int radiusDp, int blurDp, int dyDp, int shadow, int fill) {
            rad = dp(c, radiusDp);
            p.setColor(fill);                                   // 和卡片同色，边上不留接缝
            p.setShadowLayer(dp(c, blurDp), 0, dp(c, dyDp), shadow);
        }

        /** 形状按 view 的 padding 反推（正好是卡片那一圈），模糊就晕在 padding 里。
         *  不能信 getBounds()：背景 bounds 到底是整块还是内容区各家实现不一致，画歪了就被卡面盖住。 */
        @Override public void draw(android.graphics.Canvas cv) {
            android.view.View v = (getCallback() instanceof android.view.View)
                    ? (android.view.View) getCallback() : null;
            if (v == null) { cv.drawRoundRect(new android.graphics.RectF(getBounds()), rad, rad, p); return; }
            cv.drawRoundRect(new android.graphics.RectF(v.getPaddingLeft(), v.getPaddingTop(),
                    v.getWidth() - v.getPaddingRight(), v.getHeight() - v.getPaddingBottom()),
                    rad, rad, p);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** 竖直线性渐变底：盖在列表顶上，把滚上来的内容"化"掉，跟顶栏同色收口（不是硬切）。 */
    public static class FadeBg extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p = new android.graphics.Paint();

        public FadeBg(int heightPx, int from, int to) {
            p.setShader(new android.graphics.LinearGradient(0, 0, 0, heightPx, from, to,
                    android.graphics.Shader.TileMode.CLAMP));
        }

        @Override public void draw(android.graphics.Canvas cv) {
            android.graphics.Rect b = getBounds();
            cv.drawRect(b.left, b.top, b.right, b.bottom, p);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
    public static boolean DARK = true;               // 当前是否深色，亮色时状态栏图标要压黑

    /**
     * 按系统深/浅色刷一遍调色板。Activity 每次重建都会走这里，
     * 所以 uiMode 变化交给系统重建 Activity 即可，视图里不用留任何监听。
     */
    public static void applyTheme(Context c) {
        android.content.res.Configuration cf = c.getResources().getConfiguration();
        DARK = (cf.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (DARK) {
            BG = 0xFF000000; SURF = 0xFF26262A; SURF2 = 0xFF2C2C30; SURF3 = 0xFF3A3A3F;
            STROKE = 0xFF38383A; STROKE2 = 0x40545458; PRESS = 0x1FFFFFFF;
            TEXT = 0xFFFFFFFF; DIM = 0x99EBEBF5; MUT = 0x4DEBEBF5;
            ACCENT = 0xFF0A84FF; MINE = 0xFF2F3A5C;
            GREEN = 0xFF30D158; RED = 0xFFFF453A; AMBER = 0xFFFF9F0A; VIOLET = 0xFFBF5AF2;
            CODE_BG = 0xFF262626; CODE_FG = 0xFF8CF38C;
            ICODE_BG = 0xFF34343A; ICODE_FG = 0xFFFF9F0A;
            TINT_TOOL = 0xFF26262A; TINT_ERR = 0xFF2A1A1C; TINT_INJ = 0xFF1E1A2A;
        } else {
            BG = 0xFFFFFFFF; SURF = 0xFFF2F2F7; SURF2 = 0xFFF2F2F7; SURF3 = 0xFFE5E5EA;
            STROKE = 0xFFD8D8DC; STROKE2 = 0x1F000000; PRESS = 0x14000000;
            TEXT = 0xFF000000; DIM = 0x99000000; MUT = 0x4D000000;
            ACCENT = 0xFF007AFF; MINE = 0xFFD8E6FF;
            GREEN = 0xFF248A3D; RED = 0xFFD70015; AMBER = 0xFFB25000; VIOLET = 0xFF8944AB;
            CODE_BG = 0xFFF6F6F8; CODE_FG = 0xFF1B5E20;
            ICODE_BG = 0xFFEDEDF2; ICODE_FG = 0xFF9A4B00;
            TINT_TOOL = 0xFFF2F2F7; TINT_ERR = 0xFFFFEDEE; TINT_INJ = 0xFFF4EEFF;
        }
        THEIRS = 0x00000000;                         // 对方无气泡，直接铺底
        // 输入卡片 / 胶囊：亮色下白底卡靠细边立住，胶囊是淡蓝底蓝字（DeepSeek 那种）
        if (DARK) {
            CARD = 0xFF2C2C30; CHIP_BG = 0xFF243449; CHIP_BD = 0xFF33507A;
        } else {
            CARD = 0xFFFFFFFF; CHIP_BG = 0xFFEDF3FF; CHIP_BD = 0xFFCFE0FB;
        }
        PANEL = SURF;
        PANEL2 = SURF2;
    }

    // ─── 字级：对齐 Minis（正文 16sp / 单元格 14sp / 代码 13sp）───
    public static final float FS_TITLE = 16f;
    public static final float FS_BODY = 16f;      // Minis 助手正文
    public static final float FS_SMALL = 14f;     // Minis 表格单元格 · 指示器
    public static final float FS_MONO = 13f;      // Minis 代码块（行高 18sp）
    public static final float FS_TINY = 11.5f;

    /** 标题六档：抄 Minis markdown/MarkdownText.kt 的 HeadingBlock。 */
    public static final float H1 = 24f, H2 = 20f, H3 = 18f, H4 = 16f, H5 = 15f, H6 = 14f;

    // ─── 间距（dp）与圆角（dp）───
    public static final int S1 = 4, S2 = 8, S3 = 12, S4 = 16, S5 = 20;
    public static final int R_CARD = 14, R_BUBBLE = 18, R_CHIP = 10, R_PILL = 22;
    /** 行长规范：正文左右各 16，卡片内边距 12，行触区 44。 */
    public static final int PAD_H = 16, PAD_CARD = 12, TOUCH_H = 44;

    // ═══════════ 基础 ═══════════

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable bg(int color, int radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable bg(int color, int radiusDp, Context c, int strokeColor, int strokeDp) {
        GradientDrawable g = bg(color, radiusDp, c);
        g.setStroke(dp(c, strokeDp), strokeColor);
        return g;
    }

    /** 标准表面：底色 + 发丝描边。全局观感一致靠它。 */
    public static GradientDrawable surf(int fill, int radiusDp, Context c) {
        return bg(fill, radiusDp, c, STROKE, 1);
    }

    /** 会话气泡：一角收小做出"尾巴"，其余同半径。 */
    public static GradientDrawable bubble(int fill, Context c, boolean mine) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        float r = dp(c, R_BUBBLE), s = dp(c, 4);
        float[] tl, tr, br, bl;
        if (mine) {
            tl = new float[]{r, r}; tr = new float[]{r, r};
            br = new float[]{s, s}; bl = new float[]{r, r};
        } else {
            tl = new float[]{r, r}; tr = new float[]{r, r};
            br = new float[]{r, r}; bl = new float[]{s, s};
        }
        g.setCornerRadii(new float[]{tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1]});
        return g;
    }

    /** 统一按压反馈：水波纹叠在底上，API21 以下退化为原底。 */
    public static void press(View v, Context c, int fill, int radiusDp) {
        GradientDrawable base = bg(fill, radiusDp, c);      // 不要描边：全 app 的可点行都靠这里收口
        if (Build.VERSION.SDK_INT >= 21) {
            v.setBackground(new RippleDrawable(ColorStateList.valueOf(PRESS), base, null));
        } else {
            v.setBackground(base);
        }
        v.setClickable(true);
    }

    /** 发丝分隔线（1px，横向）。 */
    public static View hairline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(STROKE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
        return v;
    }

    // ═══════════ 文字 ═══════════

    public static TextView tv(Context c, String text, float sizeSp, int color) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        v.setTextColor(color);
        return v;
    }

    public static TextView mono(Context c, String text, float sizeSp, int color) {
        TextView v = tv(c, text, sizeSp, color);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        return v;
    }

    public static TextView title(Context c, String text) {
        TextView v = tv(c, text, FS_TITLE, TEXT);
        v.setPadding(0, dp(c, 10), 0, dp(c, 6));
        return v;
    }

    public static TextView dim(Context c, String text) {
        TextView v = tv(c, text, FS_SMALL, DIM);
        v.setPadding(0, dp(c, 2), 0, dp(c, 4));
        return v;
    }

    /** 段落小标题：10.5sp + 字距，用来做"回合 12"这种分隔标签。 */
    public static TextView label(Context c, String text) {
        TextView v = tv(c, text, FS_TINY, MUT);
        if (Build.VERSION.SDK_INT >= 21) v.setLetterSpacing(0.06f);
        return v;
    }

    /** 状态胶囊。 */
    public static TextView chip(Context c, String text, int color) {
        TextView v = tv(c, text, FS_TINY, color);
        v.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        v.setBackground(bg(color & 0x00FFFFFF | 0x22000000, R_CHIP, c));
        return v;
    }

    /** 发丝线夹着的居中标签，做回合边界。 */
    public static LinearLayout sep(Context c, String text) {
        LinearLayout r = row(c);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int m = dp(c, S2);
        r.setPadding(0, dp(c, S2), 0, dp(c, S2));
        r.addView(line(c));
        TextView t = label(c, text);
        t.setPadding(m, 0, m, 0);
        r.addView(t);
        r.addView(line(c));
        return r;
    }

    private static View line(Context c) {
        View v = new View(c);
        v.setBackgroundColor(STROKE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.max(1, dp(c, 1)), 1f);
        v.setLayoutParams(lp);
        return v;
    }

    // ═══════════ 控件 ═══════════

    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, FS_BODY);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUT);
        e.setBackground(surf(SURF2, R_CARD, c));
        e.setPadding(dp(c, PAD_CARD), dp(c, 10), dp(c, PAD_CARD), dp(c, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, S2);
        e.setLayoutParams(lp);
        return e;
    }

    public static Button btn(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, FS_SMALL);
        b.setTextColor(TEXT);
        b.setBackground(surf(SURF2, R_CHIP, c));
        b.setPadding(dp(c, S3), dp(c, S2), dp(c, S3), dp(c, S2));
        b.setMinHeight(dp(c, 36));
        b.setStateListAnimator(null);
        v21(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(c, S2);
        lp.bottomMargin = dp(c, S2);
        b.setLayoutParams(lp);
        return b;
    }

    /** 主按钮：实心强调色 + 深色字。 */
    public static Button primary(Context c, String text, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, FS_SMALL);
        b.setTextColor(0xFF0B1220);
        b.setBackground(bg(ACCENT, R_CHIP, c));
        b.setPadding(dp(c, S3), dp(c, S2), dp(c, S3), dp(c, S2));
        b.setMinHeight(dp(c, 36));
        b.setStateListAnimator(null);
        v21(b);
        b.setOnClickListener(l);
        return b;
    }

    /** API21+ 去掉按钮默认的浮起阴影（阴影忽大忽小很破坏稳定感）。 */
    private static void v21(View v) {
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                v.setElevation(0f);
            } catch (Throwable ignored) {
            }
        }
    }

    public static Button tabBtn(Context c, String text) {
        Button b = btn(c, text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, FS_SMALL);
        b.setPadding(dp(c, 2), dp(c, 6), dp(c, 2), dp(c, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(c, 2);
        lp.rightMargin = dp(c, 2);
        b.setLayoutParams(lp);
        return b;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    public static HorizontalScrollView hscroll(Context c, View child) {
        HorizontalScrollView h = new HorizontalScrollView(c);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(child);
        return h;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(surf(SURF, R_CARD, c));
        int p = dp(c, PAD_CARD);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, S2);
        l.setLayoutParams(lp);
        return l;
    }

    public static View gap(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, h)));
        return v;
    }

    public static View dividerV(Context c) {
        View v = new View(c);
        v.setBackgroundColor(STROKE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.max(1, dp(c, 1)), LinearLayout.LayoutParams.MATCH_PARENT);
        v.setLayoutParams(lp);
        return v;
    }

    public static ScrollView scroller(Context c, View child) {
        ScrollView s = new ScrollView(c);
        s.setClipToPadding(false);
        s.addView(child, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        return s;
    }

    public static TextView plain(Context c, String text) {
        TextView v = tv(c, text, 12.5f, TEXT);
        v.setLineSpacing(dp(c, 2), 1f);
        v.setTextIsSelectable(true);
        return v;
    }

    // ---- 便捷重载 ----

    public static TextView tv(Context c, String text, int sizeSp, int color, boolean mono) {
        TextView v = tv(c, text, (float) sizeSp, color);
        if (mono) v.setTypeface(android.graphics.Typeface.MONOSPACE);
        return v;
    }

    public static Button btn(Context c, String text, View.OnClickListener l) {
        Button b = btn(c, text);
        b.setOnClickListener(l);
        return b;
    }

    public static LinearLayout section(Context c, String title, String sub) {
        LinearLayout box = col(c);
        TextView t = tv(c, "▎" + title, FS_SMALL, ACCENT);
        t.setPadding(0, dp(c, 10), 0, dp(c, 2));
        box.addView(t);
        if (sub != null && sub.length() > 0) {
            box.addView(tv(c, sub, FS_TINY, DIM));
        }
        return box;
    }

    public static LinearLayout sectionBtn(Context c, String title, View.OnClickListener l) {
        LinearLayout box = section(c, title, null);
        TextView t = (TextView) box.getChildAt(0);
        t.setText("▎" + title + "   ↻");
        t.setOnClickListener(l);
        return box;
    }

    public static LinearLayout box(Context c, View child) {
        LinearLayout b = card(c);
        b.addView(child);
        return b;
    }


    /** \u7ad6\u8f68\uff1a\u5728 x \u5904\u753b\u4e00\u6761 w \u5bbd\u7684\u7ec6\u7ebf\uff0c\u4e0a\u4e0b\u5404\u7559 inset\uff0c\u7ed9\u8f68\u8ff9\u65f6\u95f4\u7ebf\u5f53\u80cc\u666f\u3002 */
    /** 限高的 ScrollView：思考面板正文用它，最高 maxH，超出就在内部滚。 */
    /** 运行中的一行：一道高光从左扫到右（harness 的 TextShimmer，把整行的文字一起扫）。 */
    private static final java.util.WeakHashMap<TextView, android.animation.ValueAnimator> SHIMMERS =
            new java.util.WeakHashMap<TextView, android.animation.ValueAnimator>();

    public static void shimmer(final TextView tv, final int base, final int hi) {
        if (SHIMMERS.containsKey(tv)) return;          // 已经扫着就别重开：流式里 paint 会反复调
        stopShimmer(tv);
        final int span = dp(tv.getContext(), 90);
        final android.graphics.LinearGradient g = new android.graphics.LinearGradient(
                -span, 0, 0, 0, new int[]{base, hi, base}, new float[]{0f, 0.45f, 1f},
                android.graphics.Shader.TileMode.CLAMP);
        tv.setTextColor(base);
        tv.getPaint().setShader(g);
        android.animation.ValueAnimator an = android.animation.ValueAnimator.ofFloat(0f, 1f);
        an.setDuration(1500);
        an.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        an.setInterpolator(new android.view.animation.LinearInterpolator());
        an.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                float t = ((Float) a.getAnimatedValue()).floatValue();
                float x = -span + t * (span * 2 + tv.getWidth());
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setTranslate(x, 0);
                g.setLocalMatrix(m);
                tv.invalidate();
            }
        });
        an.start();
        SHIMMERS.put(tv, an);
    }

    /** 停下并还原成普通文字色（不做的话 shader 会一直挂在 paint 上）。 */
    public static void stopShimmer(TextView tv) {
        android.animation.ValueAnimator an = SHIMMERS.remove(tv);
        if (an != null) an.cancel();
        if (tv.getPaint().getShader() != null) {
            tv.getPaint().setShader(null);
            tv.invalidate();
        }
    }

    public static class MaxScroll extends android.widget.ScrollView {
        public int maxH = 0;
        public MaxScroll(android.content.Context c) {
            super(c);
            setVerticalScrollBarEnabled(false);
            setFillViewport(true);
        }
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, h);
            if (maxH > 0 && getMeasuredHeight() > maxH) {
                super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec(
                        maxH, android.view.View.MeasureSpec.AT_MOST));
            }
        }
    }

    public static class Rail extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int x, w, top, bot;
        public Rail(int color, int xPx, int wPx, int topPx, int botPx) {
            p.setColor(color);
            p.setStyle(android.graphics.Paint.Style.FILL);
            x = xPx; w = wPx; top = topPx; bot = botPx;
        }
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            int y0 = b.top + top, y1 = b.bottom - bot;
            if (y1 <= y0) return;
            c.drawRect(b.left + x, y0, b.left + x + w, y1, p);
        }
        public void setAlpha(int a) { p.setAlpha(a); }
        public void setColorFilter(android.graphics.ColorFilter cf) { p.setColorFilter(cf); }
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
