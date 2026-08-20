package com.larv.ide.ui.fragment;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.gson.Gson;
import com.larv.ide.R;
import com.larv.ide.completion.CompletionItem;
import com.larv.ide.model.Diagnostic;

import java.util.List;

public class EditorFragment extends Fragment {

    private static final Gson GSON = new Gson();

    private WebView webView;
    private EditorListener listener;
    private String currentFile;
    private boolean isReady = false;

    public interface EditorListener {
        void onContentChange(String file, String content);
        void onCursorChange(int line, int column);
        void onCompletionsRequested(String file, int line, int column, CompletionCallback callback);
        void onEditorReady();
    }

    public interface CompletionCallback {
        void onCompletions(List<CompletionItem> completions);
    }

    public void setListener(EditorListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_editor, container, false);
        webView = view.findViewById(R.id.editorWebView);
        setupWebView();
        return view;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        // Ensure tapping the editor opens the soft keyboard
        webView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                v.requestFocus();
                InputMethodManager imm = (InputMethodManager) requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(webView, InputMethodManager.SHOW_FORCED);
                }
            }
            return false;
        });

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isReady = true;
                view.requestFocus();
                if (listener != null) {
                    listener.onEditorReady();
                }
            }
        });

        webView.addJavascriptInterface(new EditorBridge(), "LarvIDE");
        webView.loadUrl("file:///android_asset/editor.html");
    }

    public void setContent(String file, String content) {
        currentFile = file;
        if (isReady && webView != null) {
            webView.post(() -> {
                String escaped = escapeForJs(content);
                webView.evaluateJavascript("window.setContent('" + escapeForJs(file) + "', '" + escaped + "');", null);
            });
        }
    }

    public String getContent() {
        if (isReady && webView != null) {
            // This would be async, use callback instead
        }
        return "";
    }

    public void showDiagnostics(List<Diagnostic> diagnostics) {
        if (isReady && webView != null) {
            String json = GSON.toJson(diagnostics);
            webView.evaluateJavascript("window.showDiagnostics(" + json + ");", null);
        }
    }

    public void clearDiagnostics() {
        if (isReady && webView != null) {
            webView.evaluateJavascript("window.clearDiagnostics();", null);
        }
    }

    public void focus() {
        if (isReady && webView != null) {
            webView.post(() -> webView.evaluateJavascript("window.focus();", null));
        }
    }

    public void execAction(String action) {
        if (isReady && webView != null) {
            webView.evaluateJavascript("window.execAction('" + action + "');", null);
        }
    }

    @NonNull
    private String escapeForJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("</", "<\\/");
    }

    private class EditorBridge {
        @JavascriptInterface
        public void onContentChange(String file, String content) {
            if (listener != null) {
                listener.onContentChange(file, content);
            }
        }

        @JavascriptInterface
        public void onCursorChange(int line, int column) {
            if (listener != null) {
                listener.onCursorChange(line, column);
            }
        }

        @JavascriptInterface
        public void requestCompletions(String file, int line, int column) {
            if (listener != null) {
                listener.onCompletionsRequested(file, line, column, completions -> {
                    if (isReady && webView != null) {
                        String json = GSON.toJson(completions);
                        webView.post(() -> webView.evaluateJavascript(
                            "window.monacoCompletionCallback && window.monacoCompletionCallback(" + json + ");", null));
                    }
                });
            }
        }

        @JavascriptInterface
        public void onEditorReady() {
            if (listener != null) {
                listener.onEditorReady();
            }
        }
    }
}