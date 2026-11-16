# Native Libraries for arm64-v8a

These files are the real Termux binaries required by the embedded Linux runtime.
They are not compiled by this project — they are extracted from the Termux
`proot` package (`proot_5.1.107.102_aarch64.deb`) and shipped as JNI libraries so
Android extracts them into `nativeLibraryDir` at install time.

| File | Size (bytes) | Purpose |
|---|---|---|
| `libproot.so` | 259832 | Termux `proot` binary (`usr/bin/proot`). Intercepts syscalls so guest binaries can be mapped without `execve`. |
| `libproot-loader.so` | 18136 | Real proot loader (`usr/libexec/proot/loader`), pointed to by `PROOT_LOADER`. |

## Why they must be extracted

Android denies `execve()` of files inside an app's data directory (targetSdk >= 29),
so the prefix binaries in `<filesDir>/usr/bin` can only run through proot. Proot
itself must be a *real executable file* on disk:

- `app/build.gradle` sets `packagingOptions.jniLibs.useLegacyPackaging = true`
  (merged manifest `extractNativeLibs="true"`), which makes Android extract these
  libs into `applicationInfo.nativeLibraryDir` with mode 0755.
- With the AGP 8 default (`extractNativeLibs="false"`) they stay inside the APK
  and `new File(nativeLibraryDir, "libproot.so").exists()` is false — every command
  then failed with "Permission denied".

`libproot.so` has `DT_NEEDED` entries for `libtalloc.so.2` and
`libandroid-shmem.so`; those are installed into `<filesDir>/usr/lib` by
`PrefixInstaller` from the Termux package index.

## Refreshing these files

```bash
curl -LO https://packages.termux.org/apt/termux-main/pool/main/p/proot/proot_5.1.107.102_aarch64.deb
ar x proot_5.1.107.102_aarch64.deb data.tar.xz
tar xJf data.tar.xz ./data/data/com.termux/files/usr/bin/proot
tar xJf data.tar.xz ./data/data/com.termux/files/usr/libexec/proot/loader
cp data/data/com.termux/files/usr/bin/proot                   libproot.so
cp data/data/com.termux/files/usr/libexec/proot/loader        libproot-loader.so
```

Always verify `libproot-loader.so` is ~18 KB and a static ELF (`e_type=2`), not a
second copy of proot (259 KB).
