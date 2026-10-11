[简体中文](README.md) | **English**

<p align="center">
  <img src="docs/assets/lunexsu-logo.png" width="160" alt="LunexSU icon">
</p>

# LunexSU

LunexSU is an Android kernel Root project based on [KernelSU](https://github.com/tiann/KernelSU). It adds authorization expiry to the existing superuser pre-management system and provides Material and MIUIX interfaces.

[Releases](https://github.com/Shixiaoshi0417/LunexSU/releases) · [Builds](https://github.com/Shixiaoshi0417/LunexSU/actions) · [Issues](https://github.com/Shixiaoshi0417/LunexSU/issues)

## Root authorization expiry

Configure an app's profile and grant mode in the superuser page. Core enforces the policy in the actual Root admission path.

| Mode | Behavior |
| --- | --- |
| Once | Admits one new successful `su` session. Concurrent requests reserve the credit atomically, and failed admission refunds it. Unused credit expires on reboot. |
| Timed | Supports 5 minutes, 15 minutes, 1 hour and custom duration, with repeated `su` during the interval, a remaining-time display, renewal and early revocation. |
| Permanent | Preserves KernelSU's permanent pre-authorization behavior. |

Temporary grants use kernel boot time, including suspend time. Changing the wall clock cannot extend them. Once and Timed grants must be granted again after reboot. Expiry and revocation reject new admissions; already admitted Root processes keep running.

Generation checks, full UID/package/data-directory identity and fail-closed persistence protect the policy. See [authorization design and edge cases](docs/lunexsu-authorization.md) and [authorization tests](tests/authorization/README.md).

## Interface and themes

**UI source: [FolkSU by LyraVoid](https://github.com/LyraVoid/FolkSU).** LunexSU adapts its palettes, font settings, press/motion effects and five Material home layouts: Circle, Grid, Focus, Dashboard and Stats. It retains KernelSU's Material / MIUIX architecture. The reference snapshot is `fa57d340`, with original GPL attribution preserved. See [the UI port inventory](docs/lunexsu-folksu-theme.md).

Theme settings include system/light/dark mode, AMOLED, dynamic colors, custom seed colors, palette styles and font import. FolkSU FPT import supports colors, fonts, home layouts and global/per-page image backgrounds, with metadata and compatibility notices before applying and a restore-default action. The online theme store, video/audio, per-card pictures and custom navigation icons are not ported yet. See [FolkSU FPT import](docs/lunexsu-fpt-import.md) for the workflow and compatibility scope.

The adaptive launcher icon has a filled background and safe padding; Android supplies the device mask. In-app, website and documentation marks use the same LunexSU artwork.

## Modules and compatibility

KernelSU's modules, Metamodules, App Profiles and module WebUI remain available. Device compatibility depends on the kernel, architecture, installation method and signing configuration. Consult the [upstream installation guide](website/docs/guide/installation.md).

Authorization expiry requires matching LunexSU Manager, ksud and Core builds (authorization UAPI v6). Replacing only Manager cannot make an older Core enforce temporary grants. Host tests and builds cover admission races and persistence faults; actual-device `su`/SELinux, reboot, multiple users, reinstall and module regression still need validation.

## Building and development

| Directory | Contents |
| --- | --- |
| `kernel/` | Core, Root admission policy and kernel interface |
| `userspace/ksud/` | Root sessions and userspace daemon |
| `manager/` | Kotlin / Compose Manager |
| `userspace/meta-overlayfs/` | Metamodule mount implementation |
| `tests/authorization/` | Authorization unit/device integration tests |
| `website/`, `js/` | Documentation website and module WebUI library |

Configure the Android SDK/NDK, Rust Android target and `cargo-ndk`, then build ksud:

```sh
cd userspace/ksud
cargo ndk -t arm64-v8a check
cargo ndk -t arm64-v8a clippy
cargo fmt
cargo ndk -t arm64-v8a build --release
```

Bundle ksud before building Manager:

```sh
cd ../../manager
mkdir -p app/src/main/jniLibs/arm64-v8a
cp ../target/aarch64-linux-android/release/ksud app/src/main/jniLibs/arm64-v8a/libksud.so
./gradlew clean assembleRelease :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
```

Use the [build workflows](.github/workflows) for kernel and signing configuration. Android Studio can configure the Windows toolchain; local validation in this repository uses WSL. Build the website with `bun install` and `bun run docs:build`.

## License and credits

- `kernel/` is [GPL-2.0-only](kernel/LICENSE). Other components retain [GPL-3.0-or-later](LICENSE) and their original file-level license notices.
- [KernelSU / tiann](https://github.com/tiann/KernelSU): upstream kernel Root, Manager and module system.
- [FolkSU / LyraVoid](https://github.com/LyraVoid/FolkSU): UI themes, home layouts, interaction effects and FPT format.
- [Kernel-Assisted Superuser](https://git.zx2c4.com/kernel-assisted-superuser/about/), [Magisk](https://github.com/topjohnwu/Magisk), [genuine](https://github.com/brevent/genuine/) and [Diamorphine](https://github.com/m0nad/Diamorphine): retained upstream credits.

See [SECURITY.md](SECURITY.md) for security reporting.
