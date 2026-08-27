# ⚠️ LarvIDE — Under Active Development

> **Not ready for use.** The project is under heavy development. The UI is unfinished, many features are experimental, and there are known bugs. Do not use in production. Contributors welcome — see Architecture below.

---

# LarvIDE — Real-World Java IDE for Android

A lightweight, **offline-first** IDE for students who have an Android phone but no laptop. Built for low-end devices (API 21+, Islands Dark theme), enabling complete Java / Python / JavaScript development on-device — built with and for **university students in Gaza**.

## Highlights

- **Zero-setup Java**: bundled ECJ 3.26.0 + D8 (8.6.27) → compile → dex → run entirely offline
- **Real Python 3.14**: bundled CPython (arm64) via `jniLibs` — no Termux required; extension modules preloaded via `sitecustomize`
- **JavaScript (Rhino 1.7.14)** and **HTML/CSS live Preview** in the same editor
- **C/C++ / heavy toolchains** via external **Termux** sandbox (shared workspace, `RUN_COMMAND`)
- **Monaco Editor** (VS Code engine) with 5 themes and full language packs

## Feature Map

### Editor
- Monaco 0.45 via WebView — languages: Java, Python, JavaScript, HTML, CSS, JSON, XML, Markdown
- Syntax highlighting + Monarch tokenizers, per-file language switching
- **Member completion after `.`** — resolves `StringBuilder sb → append/reverse`, `System.out → println`, primitives via `int→Integer` mapping, local vars/params/fields
- **Stdlib + project symbol completion** (`ProjectIndexer` + `MemberCatalog`)
- **Auto-import quick-fix** — lightbulb (`Ctrl+.`) offers `Import 'java.util.ArrayList'` from stdlib/project index
- **Two-tier diagnostics** — fast syntax check (350ms) + full type check (1400ms idle) with red squiggles & Problems tab
- **5 editor themes**: Islands Dark (default), VS Dark+, Monokai, Light, Solarized Light
- **Appearance**: font size (10–24pt) + family (JetBrains Mono / Fira Code / Roboto Mono / System) + word wrap / line numbers / minimap / indent guides / highlight line
- **Session restore**: tabs, order, active file, cursor positions, and unsaved dirty buffers → `.larv/session.json` per project; survives process death

### Execution
- **`larvbuild.json`** (fallback `larv.json`): `language` / `entry` / `main` / `run{args,cmd,stdin}` / `repositories`
- **RunDispatcher** — single router: `language → engine` (built-in ECJ/D8/Rhino/WebView vs Termux `bash -c` command templates for Java/C++)
- **`run.args` + `run.stdin`** wired: Java `main(String[] args)` + `System.in` file feed (async pipe feeder, no deadlock), JS `arguments` global, Python `pendingArgs` slot
- **Dedicated runtimes**: `JavaRunner` (PathClassLoader), `JavascriptRunner` (Rhino), `PythonRunner` (Chaquopy-slot reflection — guidance message until CPython fully wired), `PreviewFragment` (WebView `loadDataWithBaseURL`), `EmbeddedShellSession` (`/system/bin/sh` → `TermSession` → `SafeEmulatorView`)
- **D8 per-run isolation**: unique `run_<ts>` dex output dir (pruned to 3) — fixes stale OatDexFile warning

### Termux Integration
- **ExecutionBackend** abstraction (`READY / TERMUX_MISSING / PLAY_BUILD / PERMISSION_NOT_GRANTED / EXTERNAL_APPS_UNKNOWN`)
- **TermuxCommandBackend** — explicit `RUN_COMMAND` intent to `com.termux/.RunCommandService` (`TERMUX_BIN=/data/data/com.termux/files/usr/bin`, `BACKGROUND` ↔ interactive)
- **TermuxSetupWizard** — 4-step diagnosis with per-step deep-links (F-Droid) + permission request; disabled placeholder menu `Run with Termux` / `Terminal (Termux)`
- **Workspace** is the contract: default project root → `/sdcard/LarvIDE/projects` (MANAGE_EXTERNAL_STORAGE prompt, legacy internal `getExternalFilesDir/JavaProjects` auto-migrated once)
- **Embedded Terminal tab** — real interactive `sh` session at project root; **"Terminal (Termux)"** opens a `bash` session in Termux's own window

### Project System
- Auto-discovered languages (`ProjectRecognizer` scan ≤6 depth, 400-file cap): Java, Python, JS, C/C++, HTML/CSS
- Multi-language file templates (`ProjectManager.templateFor`): `.java` / `.py` / `.js` / `.html` / `.css` / `.json`
- Drag-and-drop file tree, overflow menus, selective `notifyItemChanged` + stable IDs

### UI / Design
- Always-dark `Theme.LarvIDE` (NoActionBar) + 6 accent overlays (Blue/Purple/Green/Orange/Pink/Cyan) applied before `setContentView` + `recreate()` on change
- IntelliJ-style dialogs (`dialog_background` 12dp, `edittext_ide` focused `?attr/colorPrimary`), popup menus (`popup_menu_background`), safe bottom panel `Output / Problems / Preview / Terminal` (ViewPager2, offscreen 4)
- Accent-aware switch/seekbar tints via `themeColor(R.attr.colorPrimary)`

### Quality
- **40 JVM unit tests** (`testDebugUnitTest`): `DependencyResolver` routing/POM walk, recognizer, indexer/member completion, JsRunner capture/preload, syntaxCheck, templates, Termux intent-spec builder, RunDispatcher routing
- `RuntimeRegistry` / `RuntimeProvisioner.java` (JEP 330, SHA-256 verified) — pins engine packs (`tools/packs/larv-python-arm64.zip` 10.7 MB → 84 `jniLibs/arm64-v8a/lib*.so` + `python_std.zip`)

## What Works / Limitations

| Area | Status |
|------|--------|
| Java 16–17 (`var`, records, patterns) via ECJ 3.26 + D8 | ✅ |
| Core APIs (java.lang/util/io/nio/net/time), Collections/Streams, File I/O | ✅ |
| Python 3.14 stdlib + 62 dynload extensions (zipimport + `sitecustomize` preloader) | ✅ bundled, wiring finalizing |
| `int x = ;` / unquoted string diagnostics | ✅ |
| Swing/AWT/JavaFX, enterprise stacks, debugger, multi-module Maven | ❌ intentional |
| JDK 17 / Clang via Termux (requires setup) vs bundled Python | Termux-gated; Java fallback remains the fast in-app compiler |

## Architecture

```
LarvIDE (Android App, API 21 / target 34)
├─ UI
│  ├─ MainActivity ── RunDispatcher ─┬─ ExecutionBackend ─┬─ TermuxCommandBackend
│  ├─ EditorFragment (Monaco WebView + JS bridge) │      └─ Built-in (ECJ/D8/Rhino)
│  ├─ BottomPanel: Output / Errors / Preview / Terminal
│  ├─ SessionManager (.larv/session.json) + SettingsDialog + LanguagesDialog
│  └─ FileTreeAdapter (stable IDs) + BottomPanelAdapter
├─ Services
│  ├─ ProjectManager (shared workspace + migration)  ProjectRecognizer
│  ├─ JavaCompiler (ECJ, async bootClasspath + path-hash cache)
│  ├─ Dexer (per-run dir) + JavaRunner / JavascriptRunner / PythonRunner
│  ├─ ProjectIndexer → MemberCatalog + Stdlib completion
│  ├─ RuntimeRegistry / RuntimeProvisioner (jniLibs/arm64-v8a)
│  └─ EmbeddedShellSession (/system/bin/sh → TermSession)
├─ Editor (Monaco)
│  ├─ 5 themes + font stacks + applyEditorSettings (UI-thread posted)
│  ├─ Completion provider (dot-context → memberOf) + CodeAction import
│  └─ Diagnostic markers + positions{}
└─ Tests (app/src/test/java, 40)
```

## Dependencies

- ECJ `3.26.0` (last Java-8-runtime-safe), R8 `8.6.27`, Rhino `1.7.14`
- `jackpal/Android-Terminal-Emulator:emulatorview:v1.0.70`
- `gson 2.10.1`, `kxml2 2.3.0` (POM parsing in tests), `androidx.webkit`, `material 1.11.0`
- Test: `junit 4.13.2`, `org.json:json`, `net.sf.kxml:kxml2`

## Building

```bash
# Requires Android SDK + JDK 17 + Gradle 8.3+
./gradlew assembleDebug          # ~56 MB debug APK (includes Python runtime)
./gradlew testDebugUnitTest      # 40 tests (DependencyResolver live network tests included)
# Optional runtime pack provisioning
java tools/RuntimeProvisioner.java
```

## Project Structure

```
LarvIDE/
├── app/src/main/
│   ├── java/com/larv/ide/
│   │   ├── build/         LarvBuildParser
│   │   ├── compiler/      JavaCompiler, Dexer, JavaRunner, JavascriptRunner, PythonRunner
│   │   ├── completion/    ProjectIndexer, MemberCatalog, CompletionItem
│   │   ├── model/         Project, FileNode, Diagnostic, OpenFile
│   │   ├── project/       ProjectManager (+templateFor), ProjectRecognizer
│   │   ├── run/           RunDispatcher, backend/{ExecutionBackend,ExecRequest,TermuxCommandBackend,TermuxEnvironment}
│   │   ├── runtime/       RuntimeRegistry
│   │   ├── session/       SessionManager
│   │   ├── terminal/      EmbeddedShellSession
│   │   ├── ui/adapter/    FileTreeAdapter, BottomPanelAdapter
│   │   ├── ui/fragment/   EditorFragment, OutputFragment, ErrorsFragment, PreviewFragment, TerminalFragment
│   │   └── ui/dialog/     SettingsDialog, LanguagesDialog
│   ├── jniLibs/arm64-v8a/ libpyrun.so + 62 libext_*.so + dep libs
│   ├── assets/            editor.html (+ 5 themes), runtimes/python/{python_std.zip, ext_manifest.json}, bootclasspath/android.jar
│   └── res/               themes.xml (6 accents), drawables (dialog/edittext/popup), layouts inc. fragment_terminal
├── tools/                 RuntimeProvisioner.java, runtime-manifest.json, packs/
└── app/src/test/          40 JUnit tests
```

## Target Audience

Students in resource-constrained environments (e.g., Gaza) who have Android phones but no laptops. The mission: real-world Java/Python coursework — not toy compilers — on low-end devices.

## License

**LarvIDE Educational Source-Available Licence v1.0** — see [`LICENSE`](LICENSE).

*   **Use & private study:** free for everyone, including Gaza students and universities — no permission needed.
*   **Share unmodified copies:** allowed with copyright & licence preserved.
*   **Publish a *modified* version (forks, Play Store builds, university distributions) — including by companies, universities, and schools — requires prior written permission.** See [`PERMISSIONS.md`](PERMISSIONS.md) for the 4-line email template. For branding rules see [`TRADEMARK.md`](TRADEMARK.md).

Contributions via Pull Requests to the official repository are welcome under the same licence.

Third-party packs retain their upstream licences (Python PSF, OpenJDK GPL+CP, clang).
