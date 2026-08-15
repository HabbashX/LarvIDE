# Larv IDE - Lightweight Java IDE for Android

A minimal, offline-first Java IDE designed for students with limited resources. Built for Android phones, enabling Java development without a laptop.

## Features

- **Zero-setup**: Install and start coding immediately
- **Offline compilation**: Uses ECJ (Eclipse Compiler for Java) + D8 for dexing
- **Real type checking**: Full Java 21 syntax + semantic error detection
- **Smart completions**: Stdlib (java.*, javax.*) + project symbols
- **One-tap run**: Compile → Dex → Execute in seconds
- **Tiny APK**: ~12MB, works on Android 5.0+ (API 21)
- **RTL support**: Arabic interface ready
- **File-based projects**: Simple folder structure, no Gradle/Maven config

## What Works

| Feature | Status |
|---------|--------|
| Java 21 syntax (records, patterns, sealed, var, etc.) | ✅ |
| Core APIs (java.lang, java.util, java.io, java.nio, java.net, java.time) | ✅ |
| File I/O (Files, FileWriter, BufferedReader, etc.) | ✅ |
| Networking (Socket, ServerSocket, HttpURLConnection) | ✅ |
| Collections, Streams, Concurrency basics | ✅ |
| JUnit 5 tests | ✅ |
| Syntax highlighting + error squiggles | ✅ |
| Auto-completion (stdlib + local symbols) | ✅ |
| Multi-file projects | ✅ |

## What Doesn't Work

- Swing/JavaFX/AWT (no GUI frameworks on Android)
- Enterprise APIs (JPA, Servlets, EJB, Spring)
- Native/JNI libraries
- Multi-module Gradle/Maven builds
- Debugger/step-through

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Larv IDE (Android App)                 │
├─────────────────────────────────────────────────────────────┤
│  UI Layer (Java + XML)                                      │
│  ├── MainActivity: Project management, file tree, tabs      │
│  ├── EditorFragment: Monaco Editor in WebView               │
│  └── BottomPanel: Output/Errors ViewPager                   │
├─────────────────────────────────────────────────────────────┤
│  Services                                                   │
│  ├── ProjectManager: File tree, CRUD operations             │
│  ├── JavaCompiler: ECJ wrapper for compile + type-check    │
│  ├── Dexer: D8/R8 for .class → .dex                         │
│  ├── JavaRunner: PathClassLoader execution                  │
│  └── ProjectIndexer: Tree-sitter-like symbol extraction     │
├─────────────────────────────────────────────────────────────┤
│  Editor (Monaco in WebView)                                 │
│  ├── Java language support (Monarch tokenizer)              │
│  ├── Completion provider (JS ↔ Java bridge)                 │
│  └── Diagnostic markers (red squiggles)                     │
└─────────────────────────────────────────────────────────────┘
```

## Dependencies

- **ECJ 3.40.0** - Pure Java compiler, ~2MB
- **R8/D8 8.3.0** - Dexer, ~1MB
- **Monaco Editor 0.45** - Loaded via CDN in WebView
- **Gson** - JSON parsing
- **AndroidX** - AppCompat, Material, RecyclerView, etc.

## Building

```bash
# Requires Android SDK + Gradle 8.3+
./gradlew assembleDebug
```

## Project Structure

```
LarvIDE/
├── app/
│   ├── src/main/
│   │   ├── java/com/larv/ide/
│   │   │   ├── compiler/       # ECJ, D8, Runner
│   │   │   ├── completion/     # ProjectIndexer, CompletionItem
│   │   │   ├── model/          # Project, FileNode, Diagnostic, OpenFile
│   │   │   ├── project/        # ProjectManager
│   │   │   ├── ui/
│   │   │   │   ├── adapter/    # RecyclerView adapters
│   │   │   │   └── fragment/   # EditorFragment, OutputFragment, ErrorsFragment
│   │   │   ├── LarvApplication.java
│   │   │   └── MainActivity.java
│   │   ├── assets/
│   │   │   └── editor.html     # Monaco Editor
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── gradle/wrapper/
```

## Target Audience

Students in resource-constrained environments (e.g., Gaza) who have Android phones but no laptops. Designed for learning Java fundamentals: OOP, data structures, algorithms, file I/O, networking basics.

## License

MIT License - Free for educational use.