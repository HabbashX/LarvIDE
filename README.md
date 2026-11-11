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
- **`RunEligibility`** — a file runs only if its type is runnable *and* it has a real entry point (`static void main(String[])` for Java, `int main` for C/C++); scripts (`.py`/`.js`) just need content
- **RunDispatcher** — one path only: every language is compiled/executed by toolchains inside the embedded Linux prefix
- **Java**: prefix `javac` → `.larv-classes` → prefix **JRE** `java` (package-aware fully-qualified main class, stdin open). No D8, no ART dexing
- **Python** → `python3`, **JavaScript** → `node`, **C/C++** → `clang++`, all as live interactive sessions
- **`run.args` + `run.stdin`** wired through the interactive session; HTML/CSS open the in-app preview

### Embedded Linux Runtime
- **One execution environment**: Termux bootstrap + proot in app-private storage (`EmbeddedRuntime`, `PrefixInstaller`, `EmbeddedLinuxBackend`)
- **First launch is gated**: Download the Linux environment or exit; no language runs without it
- **Toolchains are `pkg install` only** — `openjdk-17`, `python`, `nodejs`, `clang`, `rust`, `ruby`; nothing bundled in the APK
- **Real terminal**: `TerminalFragment` + `SafeEmulatorView` with cooked IME and extra-keys bar; programs keep stdin open (`Scanner`, `input()`, readline)
- **Workspace** is the contract: default project root → `/sdcard/LarvIDE/projects` (MANAGE_EXTERNAL_STORAGE prompt, legacy internal `getExternalFilesDir/JavaProjects` auto-migrated once)

### Project System
- Auto-discovered languages (`ProjectRecognizer` scan ≤6 depth, 400-file cap): Java, Python, JS, C/C++, HTML/CSS
- Multi-language file templates (`ProjectManager.templateFor`): `.java` / `.py` / `.js` / `.html` / `.css` / `.json`
- Drag-and-drop file tree, overflow menus, selective `notifyItemChanged` + stable IDs

### UI / Design
- Always-dark `Theme.LarvIDE` (NoActionBar) + 6 accent overlays (Blue/Purple/Green/Orange/Pink/Cyan) applied before `setContentView` + `recreate()` on change
- IntelliJ-style dialogs (`dialog_background` 12dp, `edittext_ide` focused `?attr/colorPrimary`), popup menus (`popup_menu_background`), safe bottom panel `Output / Problems / Preview / Terminal` (ViewPager2, offscreen 4)
- Accent-aware switch/seekbar tints via `themeColor(R.attr.colorPrimary)`

### Quality
- **JVM unit tests** (`testDebugUnitTest`) covering run eligibility, run-command building, recognizer, indexer/completion, templates, session, setup config
- **No native language packs**: no `.so` engines, no bundled Python, no bundled JDK — the APK only contains UI, Monaco, and the terminal

## What Works / Limitations

| Area | Status |
|------|--------|
| Java 17 (records, patterns, `var`) | ✅ via prefix `openjdk-17` (javac + JRE) |
| Python 3 / Node.js / C / C++ | ✅ via prefix toolchains installed on demand |
| Real stdin in every language (`Scanner`, `input()`, readline) | ✅ live Linux sessions |
| HTML/CSS preview | ✅ in-app WebView |
| Swing/AWT/JavaFX, enterprise stacks, debugger, multi-module Maven | ❌ intentional |
| Bundled language runtimes | ❌ removed — Linux prefix is the only runtime |

## Architecture

```
LarvIDE (Android App, API 21 / target 34)
├─ UI
│  ├─ MainActivity ── RunDispatcher ── EmbeddedLinuxBackend ── prefix (proot)
│  ├─ EditorFragment (Monaco WebView + JS bridge + symbol bar)
│  ├─ BottomPanel: Output / Errors / Preview / Terminal
│  ├─ SessionManager (.larv/session.json) + SettingsDialog + LanguagesDialog
│  └─ FileTreeAdapter (stable IDs) + BottomPanelAdapter
├─ Services
│  ├─ ProjectManager (shared workspace + migration)  ProjectRecognizer
│  ├─ RunEligibility (type + entry point)  RunDispatcher (command builder)
│  ├─ ProjectIndexer → MemberCatalog + Stdlib completion
│  └─ EmbeddedRuntime / PrefixInstaller / EmbeddedLinuxBackend
├─ Editor (Monaco)
│  ├─ 5 themes + font stacks + applyEditorSettings (UI-thread posted)
│  ├─ Completion provider (dot-context → memberOf) + CodeAction import
│  └─ Diagnostic markers + positions{}
└─ Tests (app/src/test/java)
```

## Dependencies

- `jackpal/Android-Terminal-Emulator:emulatorview:v1.0.70`
- `gson 2.10.1`, `kxml2 2.3.0` (POM parsing in tests), `androidx.webkit`, `material 1.11.0`
- Test: `junit 4.13.2`, `org.json:json`, `net.sf.kxml:kxml2`
- **Nothing else**: no ECJ, no R8/D8, no Rhino, no Python runtime, no bundled JDK

## Building

```bash
# Requires Android SDK + JDK 17 + Gradle 8.3+
./gradlew clean assembleDebug   # debug APK, no bundled runtimes
./gradlew testDebugUnitTest     # unit tests
./gradlew lintDebug             # Android Lint inspections
```

## Project Structure

```
LarvIDE/
├── app/src/main/
│   ├── java/com/larv/ide/
│   │   ├── build/         LarvBuildParser
│   │   ├── completion/    ProjectIndexer, MemberCatalog, CompletionItem
│   │   ├── model/         Project, FileNode, Diagnostic, OpenFile
│   │   ├── project/       ProjectManager (+templateFor), ProjectRecognizer
│   │   ├── run/           RunDispatcher, RunEligibility, backend/{ExecutionBackend,ExecRequest} + embedded/
│   │   ├── session/       SessionManager
│   │   ├── terminal/      EmbeddedLinuxSession, EmbeddedShellSession
│   │   ├── ui/adapter/    FileTreeAdapter, BottomPanelAdapter
│   │   ├── ui/fragment/   EditorFragment, OutputFragment, ErrorsFragment, PreviewFragment, TerminalFragment
│   │   └── ui/dialog/     SettingsDialog, LanguagesDialog
│   ├── assets/            editor.html (+ 5 themes)
│   └── res/               themes.xml (6 accents), drawables, layouts
└── app/src/test/          JVM unit tests
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
