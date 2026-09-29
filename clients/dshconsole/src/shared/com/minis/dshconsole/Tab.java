package com.minis.dshconsole;

import android.view.View;

/** 功能页基类。 */
public abstract class Tab {
    protected final MainActivity act;
    private View view;

    public Tab(MainActivity a) {
        act = a;
    }

    public View view() {
        if (view == null) view = build();
        return view;
    }

    protected abstract View build();

    public void onShow() {
    }

    public void onHide() {
    }
}
