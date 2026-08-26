# LarvIDE × Termux — Issue Board v2 (post-decisions)

> Supersedes the open items in `termux-integration-issues.md`.
> Architecture decisions locked: external Termux backend, NO bridge daemon in v1,
> `ExecutionBackend` abstraction, shared workspace `/sdcard/LarvIDE/projects`,
> setup wizard as first-class UX.

## ✅ Resolved by architecture decisions

| Old # | Issue | Resolution |
|---|---|---|
| 2 | Can't embed Termux terminal view | Deferred — interactive runs open a real session in Termux's own window |
| 3 | RUN_COMMAND returns no output | Handled by capture spike + accepted fallback (see Open #2) |
| 8 | Bridge daemon security/lifecycle | Daemon removed from v1 entirely |
| 11 | Soft-keyboard modifier keys | Moot while interactive terminal lives in Termux's UI |

---

## 🔴 Open — decisions required before implementation

### 1. Shared-storage write strategy for LarvIDE itself
Our app targets SDK 34 → scoped storage applies to us too. Two options:

| Option | Pros | Cons |
|---|---|---|
| **(a) `MANAGE_EXTERNAL_STORAGE`** ("All files access") | Simplest code; direct File I/O to `/sdcard/LarvIDE/projects` | Sensitive permission; Play review needs justification video; user must flip it in system settings |
| **(b) SAF folder-picker** (persistable URI grant) | Fully Play-friendly; no sensitive permission | Clunkier first run (system picker); path handling via DocumentFile is slower/uglier |

**Decision needed:** (a) or (b)?

### 2. Cross-process output capture (spike outcome unknown)
Mechanism: wrap Run commands as
`… > <shared>/runs/<id>.log 2>&1; echo exit:$? >> …`, poll the log from the IDE.
Risk: cross-app read/write ownership rules on Android 11+ may block this depending on
which app creates the file and which storage permissions each side holds.

- **If spike passes:** Output panel + `RuntimeDetector` work through Termux.
- **If spike fails (accepted fallback):** program output visible only in Termux's session;
  our Output panel keeps serving the offline Java pipeline; detection deferred.

**Decision needed:** confirm fallback is acceptable for v1.

---

## 🟠 Accepted friction (mitigated, not eliminated)

### 3. Setup burden
Students still must: install F-Droid Termux → enable *Allow external apps* → add
`allow-external-apps=true` to termux.properties → grant RUN_COMMAND permission →
`pkg install` toolchains (OpenJDK ≈ 500 MB).
Mitigation: setup wizard detects exactly which step is missing; per-language
[Install] buttons via RuntimeDetector.

### 4. Split execution UX
Java compiles/runs inside LarvIDE panels (offline pipeline); other languages execute in
Termux's window. Mitigation: status banner ("Running via Termux") + consistent visual cues.

### 5. FUSE build performance
Compiling from `/sdcard` is slower than private storage on low-end devices.
Mitigation direction: keep sources shared, push build outputs to Termux-private dirs.

---

## 🟡 Minor, planned fixes

6. Stale-file race → forced synchronous save of dirty buffers before dispatch.
7. Work-profile / cloned-app intent failures → detect and show clear message.
8. Existing internal-storage projects → one-click import into shared workspace.

---

## Locked implementation phases

0. `ExecutionBackend` interface + `TermuxEnvironment` checks (no behavior change)
1. Setup wizard (diagnoses every step; F-Droid deep-link when Play variant detected)
2. Shared workspace migration + capture spike (`echo hello` round-trip)
3. `TermuxExecutionBackend` + RunDispatcher backend selection +
   `larvbuild.json` `"run": {"cmd": [...]}` passthrough (unlocks Rust/C/Go with zero code)
4. "Open Terminal Here" menu action (real bash session at project workdir in Termux)
5. `RuntimeDetector` per-language probes feeding wizard + status panel
