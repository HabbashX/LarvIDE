# LarvIDE — A Real IDE for Android, for Students Without a Laptop

A lightweight, **offline-first** Android IDE for students who have a phone but no laptop.
Built for low-end devices and for **university students in Gaza**, where a laptop is a luxury
and the network is unreliable.

LarvIDE is a **real editor with a real compiler**, not a toy interpreter: a full Monaco-based
code editor, a project file tree, Git-style session restore, and a genuine Linux terminal —
all running a real Linux userland on the phone.

> **One rule defines the whole project:** *no language runtime is ever bundled in the APK.*
> Every language runs on a **real Linux toolchain installed inside an embedded Linux prefix**
> (`javac` + a JRE for Java, `python3`, `node`, `clang++`). Nothing is faked, nothing is
> transpiled to a secret internal format, and the compiler output you see is the compiler's
> real output.

---

## Table of Contents

- [Highlights](#highlights)
- [How It Works: The Embedded Linux Runtime](#how-it-works-the-embedded-linux-runtime)
- [Feature Map](#feature-map)
- [Execution Model](#execution-model)
- [What Works / Limitations](#what-works--limitations)
- [Architecture](#architecture)
- [Performance](#performance)
- [Dependencies](#dependencies)
- [Building](#building)
- [Project Structure](#project-structure)
- [Target Audience](#target-audience)
- [License](#license)

---

## Highlights

- **Real toolchains, nothing bundled** — Java compiles with `javac` and runs on a **real JRE**,
  Python on `python3`, JavaScript on `node`, C/C++ on `clang++`. The APK contains **zero**
  language engines and **zero** `.so` runtimes.
- **Interactive programs** — stdin stays open, so `Scanner`, `input()` and `readline` work
  exactly as they do on a desktop.
- **Entry-point awareness** — Run is only enabled for a file that is genuinely runnable:
  Java needs a `static void main(String[])`, C/C++ needs an `int main(`, and a helper class
  automatically falls back to the project's real entry file.
- **Monaco editor** (the VS Code engine) with 5 themes, full language packs, member completion
  after `.`, and an auto-import lightbulb.
- **A true terminal** — VT100 emulation, cooked IME, and an extra-keys bar for phone keyboards.
- **Session restore** — tabs, order, active file, cursor positions and unsaved buffers survive
  process death.
- **Small APK** — ~10 MB, with no runtime payload.

---

## How It Works: The Embedded Linux Runtime

This is the architectural decision that shapes everything else.

Android apps cannot simply "run a compiler". LarvIDE instead provisions a **real Linux
userland** inside app-private storage and executes everything through it:

```
APK (UI + Monaco + terminal)
        │
        ▼
Termux bootstrap → proot loader → /data/.../usr/bin  (a real Linux filesystem)
        │
        ▼
pkg install openjdk-17 / python / nodejs / clang
        │
        ▼
javac → java   |   python3   |   node   |   clang++
```

- **First launch is gated** — a 3-step setup wizard asks what you work in, downloads the
  Linux environment, then installs the matching toolchains. Until the runtime is present, no
  language can run.
- **Toolchains are installed, never shipped** — `openjdk-17`, `python`, `nodejs`, `clang`,
  `rust`, `ruby`. The APK only contains the UI.
- **Everything is one execution path** — there is no built-in fallback engine. If a toolchain
  is missing, LarvIDE tells you and offers to install it.
- **arm64 is required** for the prefix; other devices run in a limited mode without execution.
- The workspace lives at `/sdcard/LarvIDE/projects` so projects are reachable from a file
  manager and survive app updates. Legacy internal projects are migrated once automatically.

---

## Feature Map

### Editor
- **Monaco 0.45** in a WebView — Java, Python, JavaScript, HTML, CSS, JSON, XML, Markdown,
  TypeScript and more, via Monarch tokenizers.
- **Member completion after `.`** — resolves `StringBuilder sb → append/reverse`,
  `System.out → println`, primitives via `int → Integer`, plus locals, params and fields.
- **Stdlib + project symbol completion** (`ProjectIndexer` + `MemberCatalog`).
- **Auto-import quick-fix** — the lightbulb (`Ctrl+.`) offers `import 'java.util.ArrayList'`
  from the stdlib and project indexes.
- **5 editor themes**: Islands Dark (default), VS Code Dark+, Monokai, Larv Light,
  Solarized Light.
- **Appearance**: font size (10–24 pt) + family (JetBrains Mono / Fira Code / Roboto Mono /
  System), word wrap, line numbers, minimap, indent guides, highlight line.
- **Phone-first editing** — a symbol bar for braces, tabs and operators, because typing
  `{}` on a phone keyboard is miserable.
- **Session restore** — tabs, order, active file, cursor positions and unsaved dirty buffers
  are written to `.larv/session.json` per project and restored after process death.

### Execution
- **`RunEligibility`** — a file runs only if its type is runnable *and* it has a real entry
  point (`static void main(String[])` for Java, `int main` for C/C++). Python/JS scripts just
  need content; HTML/CSS open the preview instead.
- **`RunDispatcher`** — a single router. There is exactly one execution path, and it is the
  embedded Linux prefix.
- **Java** — `javac` compiles every `*.java` under the project into `.larv-classes`, then the
  prefix **JRE** runs the fully-qualified main class. Package declarations are honoured
  (`package a.b;` → `a.b.Main`). **No dexing, no ART, no D8.**
- **Python → `python3`**, **JavaScript → `node`**, **C/C++ → `clang++`**, each as a live
  interactive session.
- **Interactive stdin** — the program's stdin stays open, so you can type into it live.
- **`larvbuild.json`** (fallback `larv.json`) — `language` / `entry` / `main` /
  `run{args,cmd,stdin}` / `repositories` for scripted builds. `run.args` are forwarded to
  C/C++ and script programs, and `run.stdin` pre-fills the program's input while stdin stays
  open for further typing.
- **HTML/CSS preview** in an in-app WebView, with the stylesheet applied live.

### Project System
- **Project home screen** — list, open or create projects from one place.
- **Project-wide search** — find text across every file in the workspace.
- **Full-page project creator** — name, language scaffold (a working `Main.java`, `main.py`,
  `index.js`, `index.html` or `main.cpp`), and a one-tap toolchain install in the same screen.
- **Auto-detected project languages** (`ProjectRecognizer`, bounded scan) for Java, Python,
  JavaScript, C/C++, HTML and CSS.
- **File tree** — create, rename, delete, move and drag-and-drop, with stable IDs and
  selective refreshes.
- **Shared workspace** — projects live in `/sdcard/LarvIDE/projects` and are reachable from
  any file manager.

### Terminal
- **Real VT100 terminal** — `TerminalFragment` + `SafeEmulatorView` over the embedded shell.
- **Cooked IME** and an **extra-keys bar** (Esc, Tab, arrows) built for phone keyboards.
- The same session powers both the standalone terminal and the output of a running program.

### UI / Design
- Dark IntelliJ-style shell (`Theme.LarvIDE`, NoActionBar) with **6 accent overlays**
  (Blue / Purple / Green / Orange / Pink / Cyan) plus a **custom accent color**, applied before
  `setContentView`.
- Resizable bottom tool window: **Output / Problems / Preview / Terminal** in a ViewPager2.
- **Offline help** dialog with quick-start guidance.
- **Accessibility** labels on toolbars and tree icons.
- A slim status bar showing file, language and run eligibility.

### Quality
- **45 JVM unit tests** (`testDebugUnitTest`) covering run eligibility, command building,
  recognizer, indexer/completion, templates, session and setup config.
- **Lint clean** — 0 errors.
- **Encoding-aware files** — UTF-8/UTF-16 BOM detection with a lossless fallback, and CRLF
  preservation.
- **Crash recovery** — sessions are written atomically so a killed process cannot corrupt them.
- **Large-file guard** — files over 2 MB are refused instead of freezing the editor.
- **No native language packs** — no `.so` engines, no bundled Python, no bundled JDK.

---

## Execution Model

How a file becomes a running program:

```
Open a file
   │
   ├─ extension recognised?  ── no ──▶ Run disabled, status explains why
   │
   ├─ entry point present?   ── no ──▶ Java: fall back to the project's entry file
   │                                   Others: refuse with a clear reason
   │
   ▼
javac  →  .larv-classes  →  java -cp .larv-classes <FQN>      (Java)
python3 file.py | node file.js | clang++ file.cpp && ./file   (others)
   │
   ▼
Live prefix process: stdout → Output tab, stdin ← keyboard
```

**Java has no special case.** It is compiled and run by the same mechanism as every other
language — there is no in-app compiler, no dexer, and no path that skips the real toolchain.

---

## What Works / Limitations

| Area | Status |
|------|--------|
| Java 17 (records, patterns, `var`) | ✅ via prefix `openjdk-17` (`javac` + JRE) |
| Python 3 / Node.js / C / C++ | ✅ via prefix toolchains installed on demand |
| Rust / Ruby | ✅ installable from the Languages panel |
| Real stdin in every language (`Scanner`, `input()`, readline) | ✅ live Linux sessions |
| Package-aware Java (`package a.b;`) | ✅ fully-qualified main class |
| HTML/CSS preview | ✅ in-app WebView |
| Session restore across process death | ✅ including unsaved buffers |
| Live type-check / squiggles while typing | ⚠️ not wired yet — the Problems tab is present but currently cleared on each run |
| Swing/AWT/JavaFX, enterprise stacks, debugger, multi-module Maven | ❌ intentional |
| Bundled language runtimes | ❌ removed — the Linux prefix is the only runtime |
| Non-arm64 devices | ⚠️ limited mode (editor works, execution does not) |

---

## Architecture

```
LarvIDE (Android App, minSdk 24 / target 34)
├─ UI
│  ├─ MainActivity ── RunDispatcher ── EmbeddedLinuxBackend ── prefix (proot)
│  ├─ EditorFragment (Monaco WebView + JS bridge + symbol bar)
│  ├─ BottomPanel: Output / Problems / Preview / Terminal
│  ├─ SetupWizardActivity / ProjectCreatorActivity
│  ├─ SessionManager (.larv/session.json) + SettingsDialog + LanguagesDialog
│  └─ FileTreeAdapter (stable IDs) + BottomPanelAdapter
├─ Services
│  ├─ ProjectManager (shared workspace + migration)  ProjectRecognizer
│  ├─ RunEligibility (type + entry point)  RunDispatcher (command builder)
│  ├─ ProjectIndexer → MemberCatalog + stdlib completion
│  └─ EmbeddedRuntime / PrefixInstaller / EmbeddedLinuxBackend
├─ Editor (Monaco)
│  ├─ 5 themes + font stacks + applyEditorSettings
│  ├─ Completion provider (dot-context → memberOf) + CodeAction import
│  └─ Diagnostic markers + positions{}
└─ Tests (app/src/test/java, 45)
```

### Key modules

| Module | Responsibility |
|--------|----------------|
| `run/RunEligibility` | Decides if a file is runnable and finds its entry point |
| `run/RunDispatcher` | Builds the prefix command and routes execution |
| `run/backend/embedded/*` | Prefix lifecycle, install and process execution |
| `completion/ProjectIndexer` | Indexes project symbols, resolves receiver types |
| `completion/MemberCatalog` | Cached stdlib member tables |
| `session/SessionManager` | Persists and restores the editor session |
| `project/ProjectManager` | Workspace, file operations, templates |

---

## Performance

The IDE is tuned for low-end hardware. Allocation in hot paths is treated as a bug:

- **Cached completion data** — member and type item lists are built once per type instead of
  on every keystroke.
- **Allocation-free string checks** — prefix/suffix matching uses `regionMatches` instead of
  allocating a lowercased copy on every call.
- **No `content.split("\n")`** — receiver-type resolution walks line offsets in place
  instead of allocating a `String` per line of the file.
- **Debounced, off-thread run-state checks** — run eligibility is re-evaluated 250 ms after
  typing pauses, on a background executor, with stale results discarded by a token.
- **Stable list listeners** — file-tree click handlers are installed once per view instead of
  being rebuilt on every rebind.
- **Single-pass file reads** — sources are decoded once, which also fixes multi-byte UTF-8
  characters being split at chunk boundaries.

---

## Dependencies

- `jackpal/Android-Terminal-Emulator:emulatorview:v1.0.70` — VT100 emulation
- `gson 2.10.1`, `kxml2 2.3.0` (POM parsing in tests), `androidx.webkit`, `material 1.11.0`
- Test: `junit 4.13.2`, `org.json:json`, `net.sf.kxml:kxml2`
- **Nothing else**: no ECJ, no R8/D8, no Rhino, no Python runtime, no bundled JDK

---

## Building

```bash
# Requires Android SDK + JDK 17 + Gradle 8.3+
./gradlew clean assembleDebug   # debug APK (~10 MB, no bundled runtimes)
./gradlew testDebugUnitTest     # 45 unit tests
./gradlew lintDebug             # Android Lint inspections
```

---

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
│   │   ├── setup/         SetupConfig
│   │   ├── terminal/      EmbeddedLinuxSession, EmbeddedShellSession
│   │   ├── ui/adapter/    FileTreeAdapter, BottomPanelAdapter, ErrorAdapter
│   │   ├── ui/fragment/   EditorFragment, OutputFragment, ErrorsFragment, PreviewFragment, TerminalFragment
│   │   ├── ui/dialog/     SettingsDialog, LanguagesDialog
│   │   └── ui/setup/      SetupWizardActivity, ProjectCreatorActivity
│   ├── assets/            editor.html (Monaco + 5 themes), fonts/
│   └── res/               themes.xml (6 accents), drawables, layouts
└── app/src/test/          45 JVM unit tests
```

---

## Target Audience

Students in resource-constrained environments — particularly students in Gaza — who have an
Android phone but no laptop. The mission is **real coursework with real tools** — the actual
`javac` and `python3`, not a reimplementation — on hardware that was never meant to compile
code.

---

## License

**LarvIDE Educational Source-Available Licence v1.0** — see [`LICENSE`](LICENSE).

- **Use & private study:** free for everyone, including Gaza students and universities — no
  permission needed.
- **Share unmodified copies:** allowed with copyright & licence preserved.
- **Publish a _modified_ version** (forks, Play Store builds, university distributions) —
  including by companies, universities and schools — **requires prior written permission.**
  See [`PERMISSIONS.md`](PERMISSIONS.md) for the short email template. Branding rules are in
  [`TRADEMARK.md`](TRADEMARK.md).

Contributions via Pull Requests to the official repository are welcome under the same licence.

Third-party components retain their upstream licences (OpenJDK GPLv2+CE, clang Apache-2.0 with
LLVM exceptions, Python PSF, Node.js MIT).
