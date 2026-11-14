package com.larv.ide.ui.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.larv.ide.R;

import jackpal.androidterm.emulatorview.EmulatorView;
import jackpal.androidterm.emulatorview.TermSession;

import java.nio.charset.StandardCharsets;

/**
 * Emulator view that stays focused and forwards key input to the terminal
 * session so typing reaches the shell process.
 */
@SuppressLint("ViewConstructor")
public class SafeEmulatorView extends EmulatorView {

    private boolean inputEnabled = true;

    public SafeEmulatorView(Context context, TermSession session, android.util.DisplayMetrics metrics) {
        super(context, session, metrics);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setLongClickable(true);
    }

    public void setInputEnabled(boolean enabled) {
        inputEnabled = enabled;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!isScreenAlive()) {
            return false;
        }
        if (inputEnabled) {
            requestFocus();
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isScreenAlive() || !inputEnabled) {
            return false;
        }
        // Printable characters: send directly to the shell.
        int unicode = event.getUnicodeChar();
        if (unicode > 0 && !Character.isISOControl((char) unicode)) {
            try {
                byte[] bytes = String.valueOf((char) unicode).getBytes(StandardCharsets.UTF_8);
                getTermSession().write(bytes, 0, bytes.length);
            } catch (Exception ignored) {
            }
            return true;
        }
        // Special keys: let EmulatorView handle them (Enter, Backspace, arrows, etc.)
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        return super.onKeyPreIme(keyCode, event);
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

    private boolean isScreenAlive() {
        TermSession session = getTermSession();
        return session != null && session.isRunning();
    }
}
