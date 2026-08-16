# Larv IDE — Engineering Specification & Session Summary

**Project:** Larv IDE (Lightweight Java IDE for Android)
**Package / namespace:** `com.larv.ide`
**Application ID:** `com.larv.ide`
**Repo:** https://github.com/HabbashX/LarvIDE
**Owner / Maintainer:** HabbashX
**License:** Apache License 2.0 (code) + proprietary trademark/branding policy (see `LICENSE`, `TRADEMARK.md`)

This document is a complete specification of the Larv IDE application as built and
modified during the working session, including the UI redesign, the API-level bug fix,
and the licensing work. It describes architecture, module-by-module behavior, resources,
resolved issues, and the build/tooling story.

---

## 1. Session Overview

The session covered four main deliverables in order:

1. **Initial project inspection & build verification**
   - Verified the project builds with Gradle on Windows (JDK 17 + Android Gradle Plugin).
   - Fixed a compilation error in `FileTreeAdapter.java:169` (`R.color.accent_blue` did not
     exist after the color refactor; replaced with `R.color.accent_variable`).
   - Result: `BUILD SUCCESSFUL`, debug APK produced at
     `app/build/outputs/apk/debug/app-debug.apk`.

2. **IntelliJ-like UI redesign (commit `4c2d54e`)**
   - Re-worked the entire main screen into an IntelliJ IDEA–style dark IDE layout:
     left tool window bar, project view with toolbar, editor tab bar, bottom tool window,
     and status bar.

3. **API-level robustness fix (commit `de0de43`)**
   - `projectManager` and related services were only initialized on API 24+; this left
     lower API levels broken.
   - Fix: always call `initServices()`; removed now-unnecessary `@RequiresApi` annotations
     from `initServices()`, `showTabCloseMenu()`, and tab-closing helpers.

4. **Licensing (commit `c66d9a4`)**
   - Added `LICENSE` (Apache License, Version 2.0).
   - Added `TRADEMARK.md` — the "LARV IDE — TRADEMARK AND BRANDING POLICY", 18 sections,
     effective August 16, 2026.

All work was committed and pushed to `master`.

---

## 2. Tech Stack Summary

| Concern              | Technology                                                                 |
|----------------------|-----------------------------------------------------------------------------|
| Language             | Java 17 (source/target compatibility) in `compileOptions`                    |
| UI                   | XML layouts + Material Components + ConstraintLayout + RecyclerView          |
| Editor engine        | Monaco Editor running inside a `WebView` (`android_asset/editor.html`, JS bridge) |
| Compiler backend     | ECJ — `org.eclipse.jdt:ecj:3.40.0` (Eclipse Compiler for Java, in-process)   |
| Dexing               | D8/R8 via reflection on `com.android.tools.r8.D8` (from Android Gradle Plugin) |
| Execution            | `dalvik.system.PathClassLoader` loads `classes.dex` and invokes `main()`      |
| Async                | Single-thread `ExecutorService` (compiler) + lifecycle callbacks, coroutines dep present |
| JSON                 | Gson 2.10.1 (diagnostics <-> Monaco, completions <-> Monaco)                 |
| Project SDK          | compileSdk 34, targetSdk 34, minSdk 21, multidex enabled                     |

Key dependencies (`app/build.gradle`):
`appcompat:1.6.1`, `material:1.11.0`, `constraintlayout:2.1.4`, `recyclerview:1.3.2`,
`activity:1.8.2`, `multidex:2.0.1`, `androidx.webkit:webkit:1.8.0`,
`kotlinx-coroutines-android:1.7.3`, `gson:2.10.1`, `ecj:3.40.0`, `commons-io:2.15.1`.

---

## 3. Application Architecture

```
MainActivity (single-activity shell)
 ├── toolbar (MaterialToolbar) + overflow menu
 ├── leftToolWindowBar (vertical TabLayout, 36dp)
 ├── leftToolWindowContent (FrameLayout, 280dp, resizable)
 │    └── projectToolWindow
 │         ├── projectToolbar (NewFile, NewFolder, Refresh, CollapseAll, Settings)
 │         └── fileTreeRecyclerView (RecyclerView + FileTreeAdapter)
 ├── leftResizer (drag handle, 2dp)
 ├── editorArea
 │    ├── tabLayout (editor tabs) + editorTabActions (Split, New tab)
 │    └── editorContainer (FrameLayout hosting EditorFragment(s))
 ├── rightResizer / rightToolWindowContent (reserved, currently hidden)
 ├── bottomResizer (drag handle, 2dp)
 ├── bottomToolWindow (200dp, resizable)
 │    ├── bottomTabLayout (Run / Problems)
 │    └── bottomViewPager (ViewPager2 + BottomPanelAdapter)
 └── statusBar (24dp, IntelliJ blue #007ACC)
```

### Services initialized by `MainActivity.initServices()`
- `ProjectManager` — file/project operations on a background executor.
- `JavaCompiler` — ECJ wrapper (compile + type-check + diagnostics parsing).
- `Dexer` — D8 via reflection, JAR fallback.
- `JavaRunner` — loads dex and invokes the discovered `main`.

### Async model
- **Project operations:** single-thread executor in `ProjectManager`.
- **Compile/run pipeline:** single-thread `compilerExecutor` in `MainActivity`.
- **UI updates:** always marshalled via `runOnUiThread(...)`.

---

## 4. Module-by-Module Specification

### 4.1 `MainActivity` — UI shell & orchestration (1024 lines)

**Interfaces implemented:**
- `ProjectManager.OnProjectChangeListener`
- `FileTreeAdapter.OnFileClickListener`
- `EditorFragment.EditorListener`

**Responsibilities:**
- View wiring, listeners, resizers, permissions.
- Project lifecycle (open/close) and window title/subtitle updates.
- Open/close editor tabs (`openFiles` list + `editorFragments` map keyed by path).
- Compile → dex → run orchestration.
- All dialogs: New Project, Open Project, New File, New Folder, Rename, Delete confirm,
  Settings (stub), tab close menus, file context menus.

**State fields:** `currentProject`, `openFiles`, `currentEditorFile`, `selectedDirectory`,
`isCompiling`, `leftWindowVisible`, `bottomWindowVisible`.

**Permissions flow (`checkPermissions()`):**
- Android 11+ (R): launches the "All files access" settings screen for the app.
- Below R: requests `WRITE_EXTERNAL_STORAGE` at runtime (request code 1001).

**Intent handling:** a `content://` intent whose decoded path ends in `.java` opens that
file in the editor (`onNewIntent` also handled).

**Editor tab mechanics:**
- Each open file gets a `TabLayout.Tab` and a hidden/shown `EditorFragment`.
- Long-press a tab → popup with **Close / Close Others / Close All**.
- Modified detection: `* ` prefix on the tab title + status text.
- Closing removes the fragment, cleans the index entry, and reselects a neighbor.

**File targets:** new files/folders are created inside the currently selected directory
(`resolveTargetDirectory()`), defaulting to the project root. Tapping a folder in the tree
makes it the target. Folder context menu offers **New Java File / New Folder / Open**.

**Toolbar menu behavior (`onOptionsItemSelected`):**
- New Project, Open Project, Save, Save All → respective dialogs/actions.
- Compile / Run / Build / Rebuild → `compileAndRun()`.
- Project / Terminal toggles → show/hide left and bottom tool windows.
- (Structure / Logcat / Debug / Stop / Clean menu items exist in the menu but are stubs.)

**Status bar updates:** "Ready", "Compiling…", "Compilation failed", "Dex error",
"No main class", "Runtime error", "Done", "Saved: x", "All files saved", and
`Ln <line>, Col <col>` cursor position.

### 4.2 `ProjectManager` — project/file storage operations

- Projects live under `context.getExternalFilesDir(null)/JavaProjects`.
- Single-thread executor; all callbacks fired on that thread (caller must `runOnUiThread`).

**Operations (each `OnFileOperationCallback`-based):**
- `getProjects()` — lists project directories as `Project` objects.
- `createProject(name)` — sanitizes the name (`[^a-zA-Z0-9_.-]` → `_`), avoids collisions
  by appending `_1`, `_2`, …; creates `Main.java` from the `java_file_template` string.
- `openProject(project)` — builds the file tree, calls `onProjectOpened` + `onFileTreeUpdated`.
- `closeProject()` — clears state, notifies listener.
- `createFile(parent, name)` — auto-appends `.java`; rejects duplicates; writes templated content.
- `createFolder(parent, name)` — `mkdirs()`, rejects duplicates.
- `deleteFile(file)` — recursive delete for directories.
- `renameFile(file, newName)` — auto-appends `.java` for files; rejects duplicates.
- `readFile(file)` / `writeFile(file, content)` — UTF-8.
- `refreshFileTree()` — rebuilds node list for `onFileTreeUpdated`.

**File tree building:** hidden files/dirs (leading `.`) are skipped; directories sort
before files; both sort case-insensitively; recursive `FileNode` build with depth tracking.

**Callbacks:** `OnProjectChangeListener`, `OnProjectCreatedCallback`,
`OnFileOperationCallback`, `OnFileReadCallback`.

### 4.3 Editor pipeline (`EditorFragment`, Monaco WebView bridge)

`EditorFragment` hosts a `WebView` loading `file:///android_asset/editor.html` (Monaco).
Web settings: JavaScript on, DOM storage, universal/regular file access enabled, no cache,
zoom disabled.

**JS bridge (`LarvIDE`):**
- `onContentChange(file, content)` → `EditorListener.onContentChange`.
- `onCursorChange(line, column)` → `EditorListener.onCursorChange` (status bar position).
- `requestCompletions(file, line, column)` → indexer query; async reply injected into
  `window.monacoCompletionCallback(...)` as JSON.
- `onEditorReady()` → lets `MainActivity` push buffered content.

**Host → JS calls:** `window.setContent(file, content)`, `window.showDiagnostics(json)`,
`window.clearDiagnostics()`, `window.focus()`.

**Escaping:** all JS-embedded strings pass through `escapeForJs()` (backslash, quotes,
newlines, `</`).

### 4.4 Code analysis pipeline

**Indexer (`ProjectIndexer`)** — regex-based symbol extraction on file open:
- Classes/interfaces/enums/records/annotations, methods, fields, imports, package name.
- `getCompletions(...)` merges: current-file symbols → symbols of other open files
  (respecting import accessibility) → stdlib entries (filtered by imports; `java.lang`
  always available) → keyword/snippet list.
- Stdlib index covers `java.lang`, `java.util`, `java.io`, `java.nio`, `java.net`,
  `java.time`, `java.util.concurrent`, `java.util.function` with snippets.
- Snippet set includes `class/interface/enum/record`, modifiers, `if/else/for/foreach/
  while/try/switch/return/new/var`, `System.out.println`, and `main`.
- `CompletionItem` kinds: CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, METHOD, FIELD,
  PARAMETER, LOCAL_VARIABLE, PACKAGE, KEYWORD, SNIPPET — each with a `getKindOrder()` used
  in final sorting.

**Compiler (`JavaCompiler`, ECJ):**
- Writes each open file to `cache/javacache`, compiles with `-source/-target 21`,
  `-encoding UTF-8`, `-proceedOnError`, `-nowarn`.
- `compile()` full pipeline; `typeCheck()` lightweight variant used for live error
  reporting (debounced ~400 ms after edits via `scheduleTypeCheck()`).
- `parseDiagnostics()` parses ECJ output (`file:line: severity: message`) into `Diagnostic`
  with `error/warning/info` severities (column parsed as 0).

**Dexer (`Dexer`):**
- Primary: reflection into `com.android.tools.r8.D8` (DEBUG mode, DexIndexed,
  minApiLevel 21, thread count = CPU count) → `classes.dex`.
- Fallback: rolls class files into `classes.jar` via `JarOutputStream` when D8 is
  unavailable or reflection fails.

**Runner (`JavaRunner`):**
- Captures `System.out`/`System.err` into byte streams, loads
  `dalvik.system.PathClassLoader` on the dex/JAR, reflects `main(String[])`,
  measures duration, restores original streams in `finally`.
- `RunResult` exposes stdout, stderr, error message, duration, and a combined string.

### 4.5 `MainActivity.compileAndRun()` pipeline

```
clear output + errors → switch bottom panel to Run → show bottom window
         ↓ (compilerExecutor)
1. JavaCompiler.compile(openFiles)
         ↓ success? no → "Compilation failed", show Problems tab, stop
2. Dexer.dex(classFiles)            fail → "Dex error", stop
3. findMainClass()                  none → "No main class", stop
4. JavaRunner.run(dex, mainClass)   → stream stdout/stderr into Run tab
5. status "Done"
```

`findMainClass()` scans open files for `public static void main(String[] args)` or
`public static void main(String args[])` (class name = file name minus `.java`).

### 4.6 Models

| Model        | Purpose                                                                 |
|--------------|-------------------------------------------------------------------------|
| `Project`    | name, path, lastModified, openFiles list, `getRootDir()`, `getFile(name)`; Serializable |
| `FileNode`   | name/path/type(FILE|DIRECTORY), children, `expanded`, `depth`; `isJavaFile()`; recursive sort (dirs first, case-insensitive); Serializable |
| `OpenFile`   | path, name, content, `modified`, cursor line/column                        |
| `Diagnostic` | filePath, line, column, endLine/endColumn, message, severity (ERROR/WARNING/INFO), code; `getShortLocation()` |

### 4.7 Adapters & list fragments

- **`FileTreeAdapter`** (RecyclerView): flattened visible-nodes list; expand/collapse,
  `collapseAll()`, `expandAll()`, `expandPath(path)` (auto-expansion after operations),
  `findNodeByPath(path)`; indentation = `depth * 24px`; icons tinted by type
  (folder arrows white, Java files `accent_variable`, others `text_secondary`);
  single = click, long-press = context menu.
- **`OutputAdapter`** — line-by-line console output, auto-scroll.
- **`ErrorAdapter`** — list of diagnostics (Problems view).
- **`BottomPanelAdapter`** (FragmentStateAdapter): index 0 = Run (`OutputFragment`),
  index 1 = Problems (`ErrorsFragment`); exposes both fragments for direct calls.
- **`OutputFragment`** — `addLine()` / `clear()` with empty-state view.
- **`ErrorsFragment`** — `setErrors(List<Diagnostic>)` with empty-state view.

---

## 5. UI / Theme Specification

### 5.1 Palette (`res/values/colors.xml`) — IntelliJ/Darcula-inspired

| Group              | Values (hex)                                                              |
|--------------------|---------------------------------------------------------------------------|
| Primary            | primary `#2196F3`, primary_dark `#1976D2`, primary_light `#64B5F6`, secondary `#FF9800` |
| Backgrounds        | background `#1F1F1F`, editor `#1E1E1E`, surface `#2D2D2D`, surface_variant `#333333`, sidebar `#252526`, toolbar `#2D2D2D`, tab bar `#2D2D2D`, status bar `#007ACC` |
| Text               | primary `#CCCCCC`, secondary `#9E9E9E`, disabled `#6A6A6A`, on-primary `#FFFFFF` |
| Semantic           | error `#F44747`, success `#4CAF50`, warning `#FFCA28`, info `#2196F3`       |
| UI                 | divider/border `#3C3C3C`, selection `#264F78`, hover `#2A2D2E`              |
| Editor             | line number `#606366`, line highlight `#282B2E`, selection `#264F78`, search match `#515D3E`, bracket match `#FFD700` |
| Tabs               | active `#1E1E1E`, inactive `#2D2D2D`, hover `#333333`, selected text `#CCCCCC`, unselected text `#9E9E9E` |
| Syntax (Darcula)   | keyword `#CC7832`, string `#6A8759`, comment `#808080`, number `#6897BB`, type `#4EC9B0`, function `#DCDCAA`, variable `#9CDCFE`, constant `#4FC1FF`, annotation `#C586C0`, punctuation `#CCCCCC` |
| Tool window        | header `#2D2D2D`, button `#333333`, button hover `#3C3C3C`                  |

### 5.2 Styles (`res/values/themes.xml`)
- `Theme.LarvIDE` — dark, no action bar, monospace font, status bar `#007ACC`,
  navigation bar `#1F1F1F`.
- `Theme.LarvIDE.Dialog` — dark dialog variant.
- `Widget.LarvIDE.EditorTabLayout` — scrollable, 80–240dp tabs, 12sp monospace,
  `editor_tab_background` drawable.
- `Widget.LarvIDE.BottomTabLayout` — scrollable, 72–160dp tabs, 11sp caption.
- `Widget.LarvIDE.ToolWindowTabLayout` — fixed, no indicator, `tool_window_tab_background`.
- `Widget.LarvIDE.Toolbar`, `.ToolbarButton`, `.FabRun`, `.Card`, `.SidebarCard`, `.Divider`.

### 5.3 Layout (`activity_main.xml`)
- Top `MaterialToolbar` (menu `toolbar_menu.xml`).
- Left vertical tool window bar (`36dp`) + resizable content (`280dp` default, clamped
  between 180px and screenWidth−400).
- Project toolbar row with 5 `ImageButton`s.
- Editor tab bar (`36dp`) + split/new-tab actions; `editorContainer` + placeholder.
- Hidden right tool window + resizer (reserved for Structure/TODO/Database).
- Resizable bottom tool window (`200dp` default; height clamped 100px ↔ screenHeight/2
  via `bottomResizer` drag).
- `ViewPager2` bottom panel with Run / Problems tabs.
- 24dp status bar: status text, UTF-8, LF, `Ln X, Col Y`.

### 5.4 New drawables
- `editor_tab_background.xml` — rounded active/inactive tab states.
- `tool_window_tab_background.xml` — selected tool-window tab highlight.
- `ic_folder.xml` — vector folder icon (used by New Folder button).

---

## 6. Files & Resources Inventory

```
Root:
  LICENSE                  Apache License 2.0
  TRADEMARK.md             Trademark & Branding Policy (18 sections)
  README.md
  build.gradle / settings.gradle / gradle.properties

Java (app/src/main/java/com/larv/ide/):
  MainActivity.java
  LarvApplication.java
  compiler/    JavaCompiler, Dexer, JavaRunner
  completion/  ProjectIndexer, CompletionItem
  model/       Project, OpenFile, FileNode, Diagnostic
  project/     ProjectManager
  ui/adapter/  FileTreeAdapter, OutputAdapter, ErrorAdapter, BottomPanelAdapter
  ui/fragment/ EditorFragment, OutputFragment, ErrorsFragment

Res (app/src/main/res/):
  layout/    activity_main.xml, fragment_editor.xml, fragment_output.xml,
             fragment_errors.xml, item_file_tree.xml
  values/    colors.xml, themes.xml, strings.xml (app_name "Larv IDE",
             java_file_template with "public class %s { ... }" Hello-world template)
  menu/      toolbar_menu.xml
  drawable/  editor_tab_background.xml, tool_window_tab_background.xml, ic_folder.xml
  AndroidManifest.xml (permissions, exported activity flags)
  assets/    editor.html (Monaco editor bundle)
  mipmap*/   launcher icons
```

---

## 7. Known Issues / Placeholders

- Split editor button (`btnSplitEditor`) shows a "not yet available" toast.
- Settings dialog offers Java 21/17/11 but "coming soon".
- Toolbar menu items Debug, Stop, Clean, Structure, Logcat exist but are not wired.
- Right tool window is reserved but hidden.
- Diagnostics column is always 0 (ECJ output lacks columns).
- Type check is debounced but runs on the same single compiler executor as full compiles.

---

## 8. Build & Verification

```
JAVA_HOME = <JDK 17>
./gradlew :app:compileDebugJavaWithJavac --no-daemon      # isolate compile errors
./gradlew assembleDebug --no-daemon                       # full debug build
APK output: app/build/outputs/apk/debug/app-debug.apk
```

The only compile blocker during the session was `FileTreeAdapter.java:169`
(`cannot find symbol: R.color.accent_blue`), fixed by using `R.color.accent_variable`.
Final result: **BUILD SUCCESSFUL**.

---

## 9. Repository History (master)

| Commit   | Message                                                                 |
|----------|-------------------------------------------------------------------------|
| `0656326` | Initial commit: Larv IDE - Lightweight Java IDE for Android            |
| `de0de43` | Fix: Initialize projectManager on all API levels (was only API 24+). Remove unnecessary @RequiresApi annotations. |
| `4c2d54e` | Enhance UI to IntelliJ-like design with tool windows, status bar, and folder creation support |
| `c66d9a4` | Add Apache 2.0 license and trademark & branding policy                |

---

## 10. Roadmap / Suggested Next Steps

- Colored file-tree icons (Java class/interface/enum icons like IntelliJ).
- Wire the Structure tool window to the project index.
- Wire Debug, Stop, Clean, Structure, Logcat menu items.
- Multi-thread or debounce-queue the compiler executor to separate type checks from builds.
- Column/range mapping for diagnostics (today column = 0).
- Persist the list of open files/state per project (`Project.openFiles`).