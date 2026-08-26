package com.larv.ide.build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class LarvBuildParser {

    public static class BuildSpec {
        public final List<String> runArgs = new ArrayList<>();
        public final List<String> runCommand = new ArrayList<>();
        public String mainClass = null;
        public String language = null;
        public String entry = null;
        public String stdinFile = null;
    }

    private LarvBuildParser() {
    }

    public static BuildSpec readBuildSpec(File larvJson) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(larvJson)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        BuildSpec spec = new BuildSpec();
        JSONObject root = new JSONObject(sb.toString());
        spec.mainClass = root.optString("main", null);
        spec.language = root.optString("language", null);
        spec.entry = root.optString("entry", null);

        JSONObject run = root.optJSONObject("run");
        if (run != null) {
            JSONArray args = run.optJSONArray("args");
            if (args != null) {
                for (int i = 0; i < args.length(); i++) {
                    String a = args.getString(i).trim();
                    if (!a.isEmpty()) spec.runArgs.add(a);
                }
            }
            JSONArray cmd = run.optJSONArray("cmd");
            if (cmd != null) {
                for (int i = 0; i < cmd.length(); i++) {
                    String c = cmd.getString(i).trim();
                    if (!c.isEmpty()) spec.runCommand.add(c);
                }
            }
            String stdin = run.optString("stdin", "");
            if (!stdin.isEmpty()) spec.stdinFile = stdin;
        }
        return spec;
    }
}
