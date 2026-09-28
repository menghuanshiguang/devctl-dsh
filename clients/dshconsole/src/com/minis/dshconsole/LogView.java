package com.minis.dshconsole;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;

/** 可滚动的等宽输出面板。 */
public class LogView extends ScrollView {
    private final TextView tv;
    private SpannableStringBuilder sb = new SpannableStringBuilder();
    private static final int MAX = 120000;

    public LogView(Context c) {
        super(c);
        tv = new TextView(c);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTextColor(Ui.TEXT);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(Ui.dp(c, 2), 1f);
        tv.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        tv.setBackground(Ui.bg(0xFF0B0E13, 10, c));
        tv.setGravity(Gravity.START);
        addView(tv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setBackground(Ui.bg(0xFF0B0E13, 10, c));
    }

    public void setMinHeightDp(int dp) {
        setMinimumHeight(Ui.dp(getContext(), dp));
    }

    public void append(final String text, final int color) {
        post(new Runnable() {
            public void run() {
                int start = sb.length();
                sb.append(text);
                if (color != Ui.TEXT) {
                    sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (sb.length() > MAX) {
                    sb.delete(0, sb.length() - MAX / 2);
                }
                tv.setText(sb);
                fullScroll(FOCUS_DOWN);
            }
        });
    }

    public void append(String text) {
        append(text, Ui.TEXT);
    }

    public void line(String text, int color) {
        append(text + "\n", color);
    }

    public void line(String text) {
        line(text, Ui.TEXT);
    }

    public void clear() {
        post(new Runnable() {
            public void run() {
                sb = new SpannableStringBuilder();
                tv.setText("");
            }
        });
    }

    public String text() {
        return sb.toString();
    }
}
