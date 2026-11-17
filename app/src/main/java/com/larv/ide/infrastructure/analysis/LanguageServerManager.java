package com.larv.ide.infrastructure.analysis;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.larv.ide.infrastructure.model.Diagnostic;
import com.larv.ide.infrastructure.run.backend.embedded.EmbeddedLinuxBackend;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional, opt-in LSP client. Disabled unless the user explicitly accepts the
 * RAM warning in Settings. Only diagnostics are consumed — no completion or
 * refactoring — so it stays small and never blocks the editor.
 */
public final class LanguageServerManager {

    private static final String TAG = "LangServer";
    private static final String PREFIX = "lsp.";

    public static final String[] LANGS = {"java", "python", "cpp", "typescript"};
    public static final String[] LANGUAGE_IDS = {"java", "python", "cpp", "typescript"};
    public static final String[] DEFAULT_COMMANDS = {
        "jdtls",
        "pylsp",
        "clangd",
        "typescript-language-server --stdio"
    };
    public static final String[] PACKAGES = {
        "jdtls", "python-lsp-server", "clangd", "typescript-language-server"
    };
    public static final String[] TITLES = {"Java", "Python", "C/C++", "TypeScript / JavaScript"};

    public interface DiagnosticsCallback {
        void onDiagnostics(String file, List<Diagnostic> diagnostics);
    }

    private final Context context;
    private final EmbeddedLinuxBackend backend;
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, Server> servers = new HashMap<>();
    private DiagnosticsCallback callback;
    private SharedPreferences prefs;
    private String rootPath;

    public LanguageServerManager(Context context, EmbeddedLinuxBackend backend) {
        this.context = context.getApplicationContext();
        this.backend = backend;
    }

    public void configure(SharedPreferences prefs, String rootPath) {
        this.prefs = prefs;
        this.rootPath = rootPath;
    }

    public void setDiagnosticsCallback(DiagnosticsCallback callback) {
        this.callback = callback;
    }

    public static boolean warningAccepted(SharedPreferences prefs) {
        return prefs.getBoolean(PREFIX + "warningAccepted", false);
    }

    public static boolean isEnabled(SharedPreferences prefs, String lang) {
        return warningAccepted(prefs) && prefs.getBoolean(PREFIX + lang + ".enabled", false);
    }

    public static String commandFor(SharedPreferences prefs, String lang) {
        String fallback = DEFAULT_COMMANDS[indexOf(lang)];
        return prefs.getString(PREFIX + lang + ".command", fallback);
    }

    private static int indexOf(String lang) {
        for (int i = 0; i < LANGS.length; i++) {
            if (LANGS[i].equals(lang)) return i;
        }
        return 0;
    }

    /** Stop servers whose language was disabled, start newly enabled ones. */
    public void sync() {
        if (prefs == null || rootPath == null) return;
        for (String lang : LANGS) {
            boolean enabled = isEnabled(prefs, lang);
            Server server = servers.get(lang);
            if (!enabled) {
                if (server != null) {
                    stop(lang);
                }
            } else if (server == null || !server.running) {
                start(lang);
            }
        }
    }

    private void start(String lang) {
        final Server server = new Server(lang);
        servers.put(lang, server);
        executor.execute(() -> {
            try {
                String command = commandFor(prefs, lang);
                String[] argv = command.trim().split("\\s+");
                Process proc = backend.openLanguageServerProcess(
                    java.util.Arrays.asList(argv), rootPath);
                server.attach(proc);
                drainStderr(proc);
                JsonObject init = new JsonObject();
                init.addProperty("jsonrpc", "2.0");
                init.addProperty("id", server.nextId());
                init.addProperty("method", "initialize");
                JsonObject params = new JsonObject();
                String uri = fileUri(new File(rootPath));
                params.addProperty("processId", android.os.Process.myPid());
                params.addProperty("rootUri", uri);
                params.add("capabilities", new JsonObject());
                JsonArray folders = new JsonArray();
                JsonObject folder = new JsonObject();
                folder.addProperty("uri", uri);
                folder.addProperty("name", "project");
                folders.add(folder);
                params.add("workspaceFolders", folders);
                init.add("params", params);
                server.send(init);
                server.readLoop();
            } catch (Exception e) {
                Log.w(TAG, "Failed to start " + lang + " language server", e);
                server.running = false;
                notifyStartFailure(lang, e.getMessage());
            }
        });
    }

    /** Send didOpen for the active file, closing the previous one if needed. */
    public void open(String lang, String file, String content) {
        Server server = servers.get(lang);
        if (server == null || !server.running) return;
        executor.execute(() -> {
            String uri = fileUri(new File(file));
            if (server.activeUri != null && !server.activeUri.equals(uri)) {
                sendNotification(server, "textDocument/didClose", closeParams(server.activeUri));
            }
            server.activeUri = uri;
            server.versions.put(uri, 1);
            JsonObject textDocument = new JsonObject();
            textDocument.addProperty("uri", uri);
            textDocument.addProperty("languageId", LANGUAGE_IDS[indexOf(lang)]);
            textDocument.addProperty("version", 1);
            textDocument.addProperty("text", content == null ? "" : content);
            JsonObject params = new JsonObject();
            params.add("textDocument", textDocument);
            sendNotification(server, "textDocument/didOpen", params);
        });
    }

    public void change(String lang, String file, String content) {
        Server server = servers.get(lang);
        if (server == null || !server.running) return;
        executor.execute(() -> {
            String uri = fileUri(new File(file));
            if (!uri.equals(server.activeUri)) return;
            int version = server.version(uri) + 1;
            server.versions.put(uri, version);
            JsonObject doc = new JsonObject();
            doc.addProperty("uri", uri);
            doc.addProperty("version", version);
            JsonArray changes = new JsonArray();
            JsonObject change = new JsonObject();
            change.addProperty("text", content == null ? "" : content);
            changes.add(change);
            doc.add("contentChanges", changes);
            JsonObject params = new JsonObject();
            params.add("textDocument", doc);
            sendNotification(server, "textDocument/didChange", params);
        });
    }

    private static JsonObject closeParams(String uri) {
        JsonObject doc = new JsonObject();
        doc.addProperty("uri", uri);
        JsonObject params = new JsonObject();
        params.add("textDocument", doc);
        return params;
    }

    private void sendNotification(Server server, String method, JsonObject params) {
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("method", method);
        msg.add("params", params);
        server.send(msg);
    }

    private void drainStderr(Process proc) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[2048];
            try (InputStream in = proc.getErrorStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    Log.d(TAG, "stderr: " + new String(buf, 0, n, StandardCharsets.UTF_8).trim());
                }
            } catch (Exception ignored) {
            }
        }, "lsp-stderr");
        t.setDaemon(true);
        t.start();
    }

    private void notifyStartFailure(String lang, String message) {
        if (callback == null) return;
        String text = "Language server '" + lang + "' could not start. "
            + "Install '" + PACKAGES[indexOf(lang)] + "' first."
            + (message == null ? "" : " (" + message + ")");
        Diagnostic d = new Diagnostic(lang + ".lsp", 1, 1, text, Diagnostic.Severity.WARNING);
        callback.onDiagnostics(lang + ".lsp", Collections.singletonList(d));
    }

    public void stop(String lang) {
        Server server = servers.remove(lang);
        if (server != null) server.shutdown();
    }

    public void stopAll() {
        for (String lang : new ArrayList<>(servers.keySet())) stop(lang);
    }

    private static String fileUri(File file) {
        return file.toURI().toString();
    }

    private static void writeFrame(OutputStream out, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + bytes.length + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        out.write(header);
        out.write(bytes);
        out.flush();
    }

    private final class Server {
        final String lang;
        Process process;
        OutputStream stdin;
        DataInputStream stdout;
        volatile boolean running;
        String activeUri;
        final Map<String, Integer> versions = new HashMap<>();
        final AtomicInteger ids = new AtomicInteger(1);

        Server(String lang) {
            this.lang = lang;
        }

        int nextId() {
            return ids.getAndIncrement();
        }

        void attach(Process proc) throws Exception {
            process = proc;
            stdin = new BufferedOutputStream(proc.getOutputStream());
            stdout = new DataInputStream(proc.getInputStream());
            running = true;
        }

        void send(JsonObject message) {
            try {
                writeFrame(stdin, gson.toJson(message));
            } catch (Exception e) {
                Log.w(TAG, "send failed for " + lang, e);
                running = false;
            }
        }

        int version(String uri) {
            Integer v = versions.get(uri);
            return v == null ? 0 : v;
        }

        void readLoop() {
            try {
                String line;
                while (running && (line = stdout.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    if (!line.toLowerCase().startsWith("content-length:")) continue;
                    int length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    // Skip remaining headers.
                    while ((line = stdout.readLine()) != null && !line.isEmpty()) { }
                    byte[] body = new byte[length];
                    stdout.readFully(body);
                    handle(new String(body, StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "LSP read ended for " + lang, e);
            } finally {
                running = false;
            }
        }

        private void handle(String json) {
            try {
                JsonObject obj = gson.fromJson(json, JsonObject.class);
                if (obj == null) return;
                if (obj.has("id") && obj.has("result")) {
                    // initialize responded — finish the handshake.
                    sendNotification(this, "initialized", new JsonObject());
                    return;
                }
                String method = obj.has("method") ? obj.get("method").getAsString() : "";
                if (!"textDocument/publishDiagnostics".equals(method) || !obj.has("params")) return;
                JsonObject params = obj.getAsJsonObject("params");
                String uri = params.has("uri") ? params.get("uri").getAsString() : "";
                JsonArray diagnostics = params.getAsJsonArray("diagnostics");
                List<Diagnostic> result = new ArrayList<>();
                if (diagnostics != null) {
                    for (int i = 0; i < diagnostics.size() && i < 200; i++) {
                        JsonObject d = diagnostics.get(i).getAsJsonObject();
                        int line = 1;
                        int column = 1;
                        if (d.has("range")) {
                            JsonObject range = d.getAsJsonObject("range");
                            JsonObject start = range.getAsJsonObject("start");
                            if (start != null) {
                                line = start.has("line") ? start.get("line").getAsInt() + 1 : 1;
                                column = start.has("character")
                                    ? start.get("character").getAsInt() + 1 : 1;
                            }
                        }
                        String message = d.has("message") ? d.get("message").getAsString() : "Problem";
                        int severity = d.has("severity") ? d.get("severity").getAsInt() : 1;
                        Diagnostic.Severity sev = severity == 1 ? Diagnostic.Severity.ERROR
                            : severity == 2 ? Diagnostic.Severity.WARNING
                            : Diagnostic.Severity.INFO;
                        String path = uri.startsWith("file://")
                            ? new java.net.URI(uri).getPath() : uri;
                        result.add(new Diagnostic(path, line, column, message, sev));
                    }
                }
                if (callback != null) callback.onDiagnostics(pathOf(uri), result);
            } catch (Exception e) {
                Log.w(TAG, "bad LSP message for " + lang, e);
            }
        }

        private String pathOf(String uri) {
            try {
                return uri.startsWith("file://") ? new java.net.URI(uri).getPath() : uri;
            } catch (Exception e) {
                return uri;
            }
        }

        void shutdown() {
            running = false;
            try {
                JsonObject shutdown = new JsonObject();
                shutdown.addProperty("jsonrpc", "2.0");
                shutdown.addProperty("id", nextId());
                shutdown.addProperty("method", "shutdown");
                send(shutdown);
                JsonObject exit = new JsonObject();
                exit.addProperty("jsonrpc", "2.0");
                exit.addProperty("method", "exit");
                send(exit);
                if (stdin != null) stdin.close();
            } catch (Exception ignored) {
            }
            if (process != null) process.destroy();
        }
    }
}
