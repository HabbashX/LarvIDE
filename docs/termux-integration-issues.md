# LarvIDE × Termux Integration — Known Issues & Problems

> Status: **Open discussion** — each issue lists the consequence and the default mitigation direction.
> Goal of this document: agree on solutions (or alternatives) before implementation begins.

## Context

LarvIDE wants to use **Termux as its Linux execution environment**: the IDE edits projects,
Termux runs toolchains (`javac`, `python`, `cargo`, `gcc`, `node`, …). The IDE itself stays an
orchestration layer and does not bundle language runtimes.

---

## 🔴 Hard blockers

### 1. Scoped storage blocks file sharing
- **Problem:** Termux cannot read `Android/data/com.larv.ide/…` on Android 11+. Our projects
  currently live in private/internal storage.
- **Consequence:** "Same files, zero copying" between Monaco and Termux is impossible as-is.
- **Default mitigation:** move all projects to shared storage
  `/sdcard/LarvIDE/projects/<name>` + one-click import action for existing internal projects.

### 2. No way to embed Termux's terminal view
- **Problem:** Terminal sessions live in Termux's process. There is no public API to render
  another app's PTY/session inside our own View.
- **Consequence:** a true "integrated terminal" requires an extra component.
- **Default mitigation:** a small **bridge daemon installed inside Termux**
  (localhost socket + auth token; real PTY via `script -qfc`), with our existing
  `emulatorview` attached to it. Alternative: accept output appearing in Termux's own window.

### 3. `RUN_COMMAND` returns nothing
- **Problem:** the intent is fire-and-forget — no stdout/stderr/exit-code callback exists.
- **Consequence:** "execute command and return output/exit code" is not implementable via
  `RUN_COMMAND` alone.
- **Default mitigation:** same bridge socket used for one-shot commands, or write-output-to-file
  + polling (fragile).

### 4. W^X exec restriction (the root constraint)
- **Problem:** apps targeting SDK ≥ 29 cannot `exec()` binaries from writable storage.
  Targeting SDK ≥ 33 is mandatory on Play. SELinux enforces this at kernel level; no runtime
  permission exists to lift it.
- **Consequence:** embedding any Linux rootfs/toolchain ourselves is impossible in a
  Play-compliant app; total dependence on external Termux being present and healthy.
- **Notes:** engines shipped *inside* the APK (`jniLibs/*.so`) remain executable — that is why
  ECJ/D8/Rhino work today and how Chaquopy would work later.

---

## 🟠 Distribution & setup friction

### 5. Termux source fragmentation
- **Problem:** the Play Store Termux build has been dead/broken since 2020. Only F-Droid /
  GitHub builds work. API 30+ also needs `<queries>` for package visibility.
- **Consequence:** users installing the wrong Termux get a silent non-functional dependency.
- **Default mitigation:** detect variant; hard-block with an explainer + F-Droid link.

### 6. Three-step authorization chain
- User must: ① enable *Allow external apps* in Termux settings,
  ② add `allow-external-apps=true` to `~/.termux/termux.properties`,
  ③ grant the `com.termux.permission.RUN_COMMAND` permission to LarvIDE.
- **Consequence:** high setup drop-off; every skipped step fails silently.
- **Default mitigation:** guided setup wizard that detects exactly which step is missing.

### 7. Toolchains are not preinstalled
- **Problem:** `python`, `javac`, `cargo`, … do not exist until `pkg install …`
  (e.g. OpenJDK ≈ 500 MB).
- **Consequence:** students hit `javac: command not found` inside the IDE.
- **Default mitigation:** `RuntimeDetector` status panel (✅/❌ per language) +
  guided one-tap `pkg install` actions through the terminal backend.

---

## 🟡 Engineering risks

### 8. Bridge daemon lifecycle & security
- Version skew between app protocol and installed daemon; orphaned sessions after crashes;
  token storage; reconnect logic after IDE process death.
- **Mitigation direction:** protocol version handshake, token in private prefs,
  session-list/reconnect command, daemon auto-restart loop.

### 9. Shared-storage performance
- Compiling from `/sdcard` goes through FUSE → noticeably slower builds on low-end devices
  (our core audience).
- **Mitigation direction:** keep sources on shared storage, direct build outputs (`build/`,
  `*.o`, caches) to Termux-private `$TMPDIR`/`$HOME` where possible.

### 10. Stale-file race before Run
- Monaco saves are debounced (~300 ms). Pressing Run before flush executes old code.
- **Mitigation direction:** forced synchronous save of dirty buffers before dispatch
  (primitive already exists: `saveAllModifiedFilesSync()`).

### 11. Soft-keyboard key gaps
- GBoard et al. lack Ctrl / Esc / Tab / arrow keys needed by interactive terminals.
- **Mitigation direction:** extra-keys bar above the terminal (Termux-style:
  Ctrl, Esc, Tab, ↑↓←→, − / |).

### 12. Lifecycle & platform edge cases
- Backgrounding/rotation (view detach pattern already exists via `SafeEmulatorView`),
  work-profile/cloned-app intent failures, Android 14 background-activity-launch limits when
  opening Termux windows, orphan cleanup after unexpected daemon death.
- **Mitigation direction:** sessions owned by the daemon (survive IDE death) + explicit
  `TerminalManager` reconnection flow; feature-detect intent resolution failures.

---

## Summary table

| # | Issue | Severity | Default fix |
|---|-------|----------|-------------|
| 1 | Scoped storage file sharing | 🔴 blocker | Shared workspace `/sdcard/LarvIDE/projects` |
| 2 | Can't embed Termux view | 🔴 blocker | Bridge daemon + local emulatorview |
| 3 | RUN_COMMAND has no output | 🔴 blocker | Bridge socket for exec too |
| 4 | W^X exec ban | 🔴 constraint | External Termux (accepted) |
| 5 | Dead Play Termux build | 🟠 friction | Detect + block + F-Droid link |
| 6 | 3-step authorization | 🟠 friction | Setup wizard w/ step detection |
| 7 | Toolchains not preinstalled | 🟠 friction | RuntimeDetector + guided installs |
| 8 | Daemon lifecycle/security | 🟡 risk | Handshake, tokens, reconnect |
| 9 | FUSE compile performance | 🟡 risk | Outputs to Termux-private dirs |
| 10 | Stale-file race | 🟡 risk | Sync save before Run |
| 11 | Missing modifier keys | 🟡 risk | Extra-keys bar |
| 12 | Lifecycle edge cases | 🟡 risk | Daemon-owned sessions + manager |

---

## Decision needed

For any of the above, propose an alternative solution. Once alternatives are agreed,
they will be folded into the final implementation plan (phases mapped in
`docs`/plan discussion).
