package com.larv.ide.build;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DependencyResolver {

    private static final String TAG = "DependencyResolver";
    private static final int MAX_DEPTH = 12;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final List<String> DEFAULT_REPOS = List.of("https://repo1.maven.org/maven2");

    private final File cacheRoot;

    public DependencyResolver(File cacheRoot) {
        this.cacheRoot = cacheRoot;
        this.cacheRoot.mkdirs();
    }

    public interface ProgressListener {
        void onProgress(String message);
    }

    public static class BuildSpec {
        public final List<String> dependencies = new ArrayList<>();
        public final List<String> repositories = new ArrayList<>();
        public String mainClass = null;
        public String language = null;
        public String entry = null;
    }

    public static class ResolveResult {
        public final boolean success;
        public final List<File> jars;
        public final List<File> pyPackageDirs;
        public final String error;

        ResolveResult(boolean success, List<File> jars, List<File> pyDirs, String error) {
            this.success = success;
            this.jars = jars;
            this.pyPackageDirs = pyDirs;
            this.error = error;
        }
    }

    @NonNull
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
        JSONArray deps = root.optJSONArray("dependencies");
        if (deps != null) {
            for (int i = 0; i < deps.length(); i++) {
                String dep = deps.getString(i).trim();
                if (!dep.isEmpty()) {
                    spec.dependencies.add(dep);
                }
            }
        }
        JSONArray repos = root.optJSONArray("repositories");
        if (repos != null) {
            for (int i = 0; i < repos.length(); i++) {
                String repo = repos.getString(i).trim();
                if (!repo.isEmpty()) {
                    spec.repositories.add(repo.endsWith("/") ? repo.substring(0, repo.length() - 1) : repo);
                }
            }
        }
        return spec;
    }

    public ResolveResult resolve(List<String> coordinates, List<String> repositories,
                                 ProgressListener listener) {
        List<String> repos = (repositories == null || repositories.isEmpty())
            ? DEFAULT_REPOS : repositories;
        LinkedHashMap<String, File> resolved = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        List<File> pyDirs = new ArrayList<>();
        try {
            for (String coordinate : coordinates) {
                String dep = coordinate.trim();
                if (isNpmCoordinate(dep)) {
                    resolved.put(dep, fetchNpmPackage(dep, listener));
                    if (listener != null) {
                        listener.onProgress("Resolved " + dep);
                    }
                } else if (isPypiCoordinate(dep)) {
                    File dir = resolvePypiPackage(dep, listener);
                    pyDirs.add(dir);
                    resolved.put(dep, dir);
                    if (listener != null) {
                        listener.onProgress("Installed " + dep);
                    }
                } else {
                    resolveCoordinate(dep, repos, resolved, visited, 0, listener);
                }
            }
            return new ResolveResult(true, new ArrayList<>(resolved.values()), pyDirs, null);
        } catch (Exception e) {
            Log.e(TAG, "Dependency resolution failed", e);
            return new ResolveResult(false,
                new ArrayList<>(resolved.values()), pyDirs, e.getMessage());
        }
    }

    static boolean isNpmCoordinate(String dep) {
        return dep.indexOf('@', 1) >= 0 && !dep.contains(":");
    }

    static boolean isPypiCoordinate(String dep) {
        if (dep.contains(":")) return false;
        return dep.contains("==") || dep.matches("[A-Za-z0-9_.\\-]+");
    }

    private File fetchNpmPackage(String dep, ProgressListener listener) throws IOException {
        int at = dep.indexOf('@', 1);
        String name = dep.substring(0, at);
        String rest = dep.substring(at + 1);
        String version = rest;
        String subpath = "";
        int colon = rest.indexOf(':');
        if (colon >= 0) {
            version = rest.substring(0, colon);
            subpath = "/" + rest.substring(colon + 1).replace('.', '/');
        }
        String url = "https://cdn.jsdelivr.net/npm/" + name + "@" + version + subpath;
        String safeName = (name + "-" + version + subpath).replaceAll("[^A-Za-z0-9._-]", "_");
        File target = new File(cacheRoot, "npm/" + safeName);
        if (target.exists() && target.length() > 0) {
            if (listener != null) listener.onProgress("Cached " + dep);
            return target;
        }
        downloadToFile(url, target);
        return target;
    }

    private File resolvePypiPackage(String dep, ProgressListener listener) throws IOException {
        try {
            return resolvePypiPackageInner(dep, listener);
        } catch (org.json.JSONException e) {
            throw new IOException("PyPI metadata error: " + e.getMessage(), e);
        }
    }

    private File resolvePypiPackageInner(String dep, ProgressListener listener) throws IOException, org.json.JSONException {
        String name = dep;
        String version = null;
        int eq = dep.indexOf("==");
        if (eq >= 0) {
            name = dep.substring(0, eq);
            version = dep.substring(eq + 2);
        }
        File destDir = new File(cacheRoot, "pypackages/" + name
            + (version == null ? "" : "-" + version));
        if (destDir.exists()) {
            return destDir;
        }
        String metaUrl = "https://pypi.org/pypi/" + name
            + (version == null ? "/json" : "/" + version + "/json");
        File metaFile = new File(cacheRoot, "pypackages/.meta-" + name + "-"
            + (version == null ? "latest" : version) + ".json");
        downloadToFile(metaUrl, metaFile);

        StringBuilder sb = new StringBuilder();
        try (java.io.FileInputStream fis = new java.io.FileInputStream(metaFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        JSONObject meta = new JSONObject(sb.toString());
        JSONArray urls = meta.getJSONArray("urls");
        String wheelUrl = null;
        for (int i = 0; i < urls.length(); i++) {
            JSONObject u = urls.getJSONObject(i);
            String filename = u.optString("filename", "");
            if ("bdist_wheel".equals(u.optString("packagetype"))
                && filename.endsWith("-none-any.whl")) {
                wheelUrl = u.getString("url");
                break;
            }
        }
        if (wheelUrl == null) {
            throw new IOException(name + ": no pure-Python wheel available "
                + "(native packages are not supported)");
        }
        File wheelFile = new File(cacheRoot, "pypackages/.wheel-" + name + ".whl");
        downloadToFile(wheelUrl, wheelFile);
        unzipWheel(wheelFile, destDir);
        wheelFile.delete();
        return destDir;
    }

    private void unzipWheel(File zip, File destDir) throws IOException {
        destDir.mkdirs();
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
            new java.io.FileInputStream(zip))) {
            byte[] buf = new byte[8192];
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void resolveCoordinate(String coordinate, List<String> repos,
                                   LinkedHashMap<String, File> resolved, Set<String> visited,
                                   int depth, ProgressListener listener) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("Dependency tree too deep at " + coordinate);
        }
        if (visited.contains(coordinate)) {
            return;
        }
        visited.add(coordinate);

        String[] parts = coordinate.split(":");
        if (parts.length != 3) {
            throw new IOException("Invalid dependency coordinate: " + coordinate
                + " (expected group:artifact:version)");
        }
        String groupId = parts[0].trim();
        String artifactId = parts[1].trim();
        String version = parts[2].trim();

        File pomFile = fetchArtifact(repos, groupId, artifactId, version, "pom", listener);
        PomInfo pom = parsePom(pomFile);

        for (Dep dep : pom.dependencies) {
            if (dep.groupId.isEmpty() || dep.artifactId.isEmpty()) continue;
            String depVersion = expand(dep.version, pom.properties, pom.version);
            if (depVersion.startsWith("$")) continue;
            resolveCoordinate(dep.groupId + ":" + dep.artifactId + ":" + depVersion,
                repos, resolved, visited, depth + 1, listener);
        }

        File jarFile = fetchArtifact(repos, groupId, artifactId, version, "jar", listener);
        resolved.put(coordinate, jarFile);
        if (listener != null && depth == 0) {
            listener.onProgress("Resolved " + artifactId + "-" + version);
        }
    }

    private static class Dep {
        String groupId = "";
        String artifactId = "";
        String version = "";
        String scope = "compile";
        boolean optional = false;
    }

    private static class PomInfo {
        String groupId = "";
        String artifactId = "";
        String version = "";
        final Map<String, String> properties = new LinkedHashMap<>();
        final List<Dep> dependencies = new ArrayList<>();
    }

    private PomInfo parsePom(File pomFile) throws IOException {
        PomInfo pom = new PomInfo();
        try (InputStream in = new FileInputStream(pomFile)) {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());

            Deque<String> path = new ArrayDeque<>();
            StringBuilder buf = new StringBuilder();
            String parentGroupId = "";
            String parentVersion = "";
            Dep curDep = null;
            boolean inProperties = false;
            boolean inExclusions = false;

            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                switch (event) {
                    case XmlPullParser.START_TAG: {
                        String name = parser.getName();
                        path.push(name);
                        buf.setLength(0);
                        if (curDep != null && name.equals("exclusions")) {
                            inExclusions = true;
                        } else if (path.size() == 2 && name.equals("properties")) {
                            inProperties = true;
                        } else if (path.size() == 3 && name.equals("dependency")
                            && "dependencies".equals(second(path))) {
                            curDep = new Dep();
                        }
                        break;
                    }
                    case XmlPullParser.TEXT:
                    case XmlPullParser.CDSECT:
                        buf.append(parser.getText());
                        break;
                    case XmlPullParser.END_TAG: {
                        String name = parser.getName();
                        String value = buf.toString().trim();
                        buf.setLength(0);

                        if (curDep != null) {
                            if (name.equals("exclusions")) {
                                inExclusions = false;
                            } else if (!inExclusions) {
                                switch (name) {
                                    case "groupId": curDep.groupId = value; break;
                                    case "artifactId": curDep.artifactId = value; break;
                                    case "version": curDep.version = value; break;
                                    case "scope": curDep.scope = value; break;
                                    case "optional": curDep.optional = Boolean.parseBoolean(value); break;
                                }
                            }
                            if (name.equals("dependency")) {
                                boolean ok = !curDep.optional
                                    && (curDep.scope.isEmpty()
                                        || curDep.scope.equals("compile")
                                        || curDep.scope.equals("runtime"))
                                    && !curDep.groupId.isEmpty()
                                    && !curDep.artifactId.isEmpty()
                                    && !curDep.version.isEmpty();
                                if (ok) {
                                    pom.dependencies.add(curDep);
                                }
                                curDep = null;
                                inExclusions = false;
                            }
                        } else if (path.size() == 2 && name.equals("properties")) {
                            inProperties = false;
                        } else if (inProperties && path.size() == 2) {
                            pom.properties.put(name, value);
                        } else if (path.size() == 3 && name.equals("groupId")
                            && "parent".equals(second(path))) {
                            parentGroupId = value;
                        } else if (path.size() == 3 && name.equals("version")
                            && "parent".equals(second(path))) {
                            parentVersion = value;
                        } else if (!inProperties && path.size() == 2) {
                            switch (name) {
                                case "groupId": pom.groupId = value; break;
                                case "artifactId": pom.artifactId = value; break;
                                case "version": pom.version = value; break;
                            }
                        }

                        path.pop();
                        break;
                    }
                }
                event = parser.next();
            }

            if (pom.groupId.isEmpty()) pom.groupId = parentGroupId;
            if (pom.version.isEmpty()) pom.version = parentVersion;
            if (!pom.properties.containsKey("project.version")) {
                pom.properties.put("project.version", pom.version);
            }
            if (!pom.properties.containsKey("project.groupId")) {
                pom.properties.put("project.groupId", pom.groupId);
            }
            if (!pom.properties.containsKey("pom.version")) {
                pom.properties.put("pom.version", pom.version);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse POM " + pomFile.getName() + ": " + e.getMessage(), e);
        }
        return pom;
    }

    private String second(@NonNull Deque<String> path) {
        if (path.size() < 2) return "";
        var it = path.iterator();
        it.next();
        return it.next();
    }

    @NonNull
    private String expand(String value, Map<String, String> props, String projectVersion) {
        if (value == null) return "";
        String result = value.trim();
        int guard = 0;
        while (guard++ < 10) {
            int start = result.indexOf("${");
            if (start < 0) break;
            int end = result.indexOf('}', start);
            if (end < 0) break;
            String key = result.substring(start + 2, end);
            String replacement = props.containsKey(key) ? props.get(key)
                : key.equals("project.parent.version") || key.equals("parent.version") ? projectVersion : "";
            result = result.substring(0, start) + replacement + result.substring(end + 1);
        }
        return result.trim();
    }

    private File fetchArtifact(List<String> repos, String groupId, String artifactId,
                               String version, String ext, ProgressListener listener) throws IOException {
        String relPath = groupId.replace('.', '/') + "/" + artifactId + "/" + version
            + "/" + artifactId + "-" + version + "." + ext;
        File cacheFile = new File(cacheRoot, relPath);

        if (cacheFile.exists() && cacheFile.length() > 0) {
            if (listener != null && ext.equals("jar")) {
                listener.onProgress("Cached " + artifactId + "-" + version);
            }
            return cacheFile;
        }

        IOException lastError = null;
        for (String repo : repos) {
            String url = repo + "/" + relPath;
            try {
                downloadToFile(url, cacheFile);
                return cacheFile;
            } catch (IOException e) {
                lastError = e;
                cacheFile.delete();
            }
        }
        throw new IOException("Could not fetch " + groupId + ":" + artifactId + ":" + version
            + " (" + ext + ") from any repository"
            + (lastError != null ? ": " + lastError.getMessage() : ""));
    }

    private void downloadToFile(String urlStr, File target) throws IOException {
        target.getParentFile().mkdirs();
        File temp = new File(target.getAbsolutePath() + ".tmp." + Thread.currentThread().getId());
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for " + urlStr);
            }
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(temp)) {
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    total += n;
                }
                if (total == 0) {
                    throw new IOException("Empty response for " + urlStr);
                }
            }
            if (!temp.renameTo(target) && !target.exists()) {
                throw new IOException("Failed to move downloaded file into place: " + target);
            }
        } finally {
            temp.delete();
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
