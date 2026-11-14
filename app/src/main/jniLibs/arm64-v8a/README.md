# Native Libraries for arm64-v8a

This directory contains native libraries required for the embedded Linux runtime on Android 14+.

## Files

| File | Purpose |
|---|---|
| `libproot.so` | proot binary — provides chroot isolation for the Linux prefix |
| `libproot-loader.so` | proot loader — W^X-safe loader for Android 14+ (targetSdk 34) |

## How these work

- `libproot.so` is executed directly by `EmbeddedLinuxBackend` to run commands inside the proot prefix.
- `libproot-loader.so` is referenced via the `PROOT_LOADER` environment variable. Proot uses it to mmap+execute code in a way that bypasses Android 14's W^X memory restrictions.

## Obtaining the real libraries

The files currently in this directory are copies of the Termux `proot` binary (extracted from `proot_5.1.107.102_aarch64.deb`). They will work for basic execution, but for full Android 14 W^X bypass you should build proper shared libraries from the [proot source](https://github.com/proot-me/proot) using the Android NDK:

```bash
# Build proot for Android arm64
ndk-build NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk APP_ABI=arm64-v8a
```

Place the resulting `libproot.so` and `libproot-loader.so` here, replacing the placeholder copies.

## Fallback

If these libraries are missing, LarvIDE falls back to executing `/system/bin/linker64` + `bash` directly (works on most devices but lacks chroot isolation).
