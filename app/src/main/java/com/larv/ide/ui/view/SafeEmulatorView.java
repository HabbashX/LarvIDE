package com.larv.ide.ui.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import jackpal.androidterm.emulatorview.EmulatorView;
import jackpal.androidterm.emulatorview.TermSession;

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
        // Raw (TYPE_NULL) IME mode relies on hardware key events, which most
        // soft keyboards never send for ordinary characters. Cooked mode lets
        // the IME commit text through InputConnection, which EmulatorView maps
        // into terminal bytes (including Ctrl via commitText/control state).
        setUseCookedIME(true);
    }

    /**
     * Advertise a normal text field to soft keyboards. Without this, Gboard
     * and friends may refuse to show or may never call commitText().
     * FLAG_NO_ENTER_ACTION keeps Return as a key event so EmulatorView sends
     * the terminal's CR byte instead of swallowing it as an IME action.
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        outAttrs.label = "Terminal";
        return super.onCreateInputConnection(outAttrs);
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
        // Let EmulatorView/TermKeyListener do the mapping for every key.
        // Writing event.getUnicodeChar() here would bypass control/Alt/Fn
        // handling and could duplicate text already committed by the IME.
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
