package com.larv.ide.ui.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.util.DisplayMetrics;
import android.view.MotionEvent;

import jackpal.androidterm.emulatorview.EmulatorView;
import jackpal.androidterm.emulatorview.TermSession;

@SuppressLint("ViewConstructor")
public class SafeEmulatorView extends EmulatorView {

    public SafeEmulatorView(Context context, TermSession session, DisplayMetrics metrics) {
        super(context, session, metrics);
    }

    private boolean isScreenAlive() {
        TermSession session = getTermSession();
        return session != null && session.isRunning();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!isScreenAlive()) {
            return false;
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public void page(int delta) {
        if (!isScreenAlive()) {
            return;
        }
        super.page(delta);
    }

    @Override
    protected int computeVerticalScrollRange() {
        if (!isScreenAlive()) {
            return 0;
        }
        return super.computeVerticalScrollRange();
    }

    @Override
    protected int computeVerticalScrollOffset() {
        if (!isScreenAlive()) {
            return 0;
        }
        return super.computeVerticalScrollOffset();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!isScreenAlive()) {
            return;
        }
        super.onDraw(canvas);
    }
}
