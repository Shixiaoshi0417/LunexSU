[简体中文](README.md) | **English**

<p align="center">
  <img src="docs/assets/lunexsu-logo.png" width="160" alt="LunexSU icon">
</p>

# LunexSU

A kernel-based Root solution for Android devices.

LunexSU is based on [KernelSU](https://github.com/tiann/KernelSU). It adds authorization expiry to the existing superuser pre-management system and provides Material and MIUIX interfaces. This README follows KernelSU's introduction structure, with LunexSU features and usage added.

[![Builds](https://img.shields.io/github/actions/workflow/status/Shixiaoshi0417/LunexSU/build-manager.yml?label=Manager&logo=github)](https://github.com/Shixiaoshi0417/LunexSU/actions)
[![License: GPL v2](https://img.shields.io/badge/Kernel-GPL%20v2-orange.svg?logo=gnu)](kernel/LICENSE)
[![License: GPL v3](https://img.shields.io/badge/Manager%20%26%20Userspace-GPL%20v3-blue.svg?logo=gnu)](LICENSE)

[Releases](https://github.com/Shixiaoshi0417/LunexSU/releases) · [Builds](https://github.com/Shixiaoshi0417/LunexSU/actions) · [Issues](https://github.com/Shixiaoshi0417/LunexSU/issues)


The Manager package is `com.shixiaoshi0417.lunexsu` (`.pr` for PR builds). It installs independently; the previous Manager private settings are not automatically imported. Core must use the same package and matching signature. Module interfaces and persisted authorization paths remain compatible. See the [brand migration record](docs/lunexsu-brand-migration.md).

## Features

- Kernel-based `su` and Root access management, retaining KernelSU's superuser pre-management model.
- Module system based on [Metamodules](https://kernelsu.org/guide/metamodule.html): pluggable infrastructure for systemless modifications.
- [App Profile](https://kernelsu.org/guide/app-profile.html): configure each app's Root identity, capabilities and SELinux context to constrain Root privileges.
- Once, Timed and Permanent grants, enforced in the actual Root admission path.
- Material / MIUIX interfaces with FinaSU palettes, fonts, interaction effects and Material home layouts.
- FinaSU FPT theme import for colors, fonts, home layouts and image backgrounds.
- Shared LunexSU brand assets and an Android adaptive launcher icon.

## Compatibility state

Upstream KernelSU targets Android GKI 2.0 devices (kernel 5.10+), and its README also lists manually built support for older kernels (4.14+). LunexSU inherits the corresponding kernel integration approaches. Device compatibility also depends on kernel configuration, KMI, installation method and signing; the kernel version alone is insufficient.

Upstream lists `arm64-v8a` and `x86_64`, along with environments such as WSA, ChromeOS and container-based Android. These describe upstream compatibility; LunexSU authorization expiry still requires actual-device validation with the matching Core.

> [!CAUTION]
> Upstream KernelSU notes that changes in some newer kernels can break `x86_64` support and potentially cause a kernel panic. Read the [upstream x86_64 support guide](https://kernelsu.org/guide/x86_64-support.html) first. A standard Android Studio emulator can validate Manager UI, but Root admission tests require the matching Core.

Authorization expiry requires matching LunexSU Manager, ksud and Core builds (authorization UAPI v6). Replacing only Manager cannot make an older Core enforce temporary grants.

## Usage

1. Obtain a suitable build from [LunexSU Releases](https://github.com/Shixiaoshi0417/LunexSU/releases); development builds are available through [Actions](https://github.com/Shixiaoshi0417/LunexSU/actions).
2. Follow KernelSU's [installation guide](https://kernelsu.org/guide/installation.html) to check the device, KMI and installation method, then install LunexSU Core matching Manager.
3. Configure each app's profile and Once / Timed / Permanent grant mode in the superuser page.
4. Manage modules on the modules page. For `/system` modifications, configure mount support using the [Metamodule guide](https://kernelsu.org/guide/metamodule.html).
5. Select colors, fonts and layouts in theme settings, or import a FinaSU FPT theme pack.

Useful documentation:

- [KernelSU official website and guides](https://kernelsu.org/)
- [How to build KernelSU](https://kernelsu.org/guide/how-to-build.html)
- [Integration for non-GKI devices](https://kernelsu.org/guide/how-to-integrate-for-non-gki.html)
- [Module development](https://kernelsu.org/guide/module.html)
- [App Profile](https://kernelsu.org/guide/app-profile.html)
- [Module WebUI](https://kernelsu.org/guide/module-webui.html)
- [FAQ](https://kernelsu.org/guide/faq.html)

KernelSU documentation explains the underlying mechanisms. Use this repository's documentation for LunexSU additions.

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

**UI source: [FinaSU (formerly FolkSU) by LyraVoid](https://github.com/LyraVoid/FinaSU).** LunexSU adapts its palettes, font settings, press/motion effects and five Material home layouts: Circle, Grid, Focus, Dashboard and Stats. It retains KernelSU's Material / MIUIX architecture. The reference snapshot is `fa57d340`, with original GPL attribution preserved. See [the UI port inventory](docs/lunexsu-folksu-theme.md).

Theme settings include system/light/dark mode, AMOLED, dynamic colors, custom seed colors, palette styles and font import. FinaSU FPT import supports colors, fonts, home layouts and global/per-page image backgrounds, with metadata and compatibility notices before applying and a restore-default action. The online theme store, video/audio, per-card pictures and custom navigation icons are not ported yet. See [FinaSU FPT import](docs/lunexsu-fpt-import.md) for the workflow and compatibility scope.

The adaptive launcher icon has a filled background and safe padding; Android supplies the device mask. In-app, website and documentation marks use the same LunexSU artwork.

## Module system

KernelSU's modules, Metamodules, App Profiles and module WebUI remain available:

- Install, enable, disable and uninstall modules in Manager.
- Retain module boot scripts, `system.prop` and SELinux policy handling.
- Use a Metamodule for system file mounting, with one active Metamodule at a time.
- Configure modules through their module WebUI.
- Configure per-app Root privileges and non-Root behavior through App Profile.

Modules using only scripts, SELinux policy or `system.prop` do not require a Metamodule. Modules modifying `/system` need a matching mount implementation. See the [upstream installation guide](website/docs/guide/installation.md) and [Metamodule guide](https://kernelsu.org/guide/metamodule.html).

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

## Testing and validation

- Root authorization: host tests cover concurrent credit reservation, failure refunds, expiry checks and persistence faults. Device test entry points are documented in [authorization tests](tests/authorization/README.md).
- Manager: theme JVM unit tests, Android integration tests and Material / MIUIX emulator import flows have been run. See the [FPT import validation record](docs/lunexsu-fpt-import.md).
- Branding: launcher, splash, in-app, website and documentation assets are described in [icon adaptation](docs/lunexsu-brand-assets.md).
- Builds and overall integration are recorded in [integration validation](docs/lunexsu-theme-integration.md).

Actual-device `su`/SELinux, reboot, multiple users, reinstall and module regression still require validation with matching LunexSU Core. Theme tests on a standard emulator do not substitute for these checks.

## Development and translation

Report problems through [Issues](https://github.com/Shixiaoshi0417/LunexSU/issues), or contribute improvements through [Pull Requests](https://github.com/Shixiaoshi0417/LunexSU/pulls). For device-specific reports, include the device model, Android version, kernel version, installation method, Manager/Core versions and reproduction steps.

This project's README is available in Simplified Chinese and English only. Chinese is the default; select **English** at the top to switch. Manager retains its existing language resource structure. Keep new Chinese and English strings aligned; app localization is maintained separately from the bilingual README.

Kernel interface changes require matching ksud and Manager JNI updates. See [AGENTS.md](AGENTS.md) for development conventions and component checks.

## Discussion

- LunexSU feedback: [GitHub Issues](https://github.com/Shixiaoshi0417/LunexSU/issues).
- Upstream KernelSU discussion: Telegram [@KernelSU](https://t.me/KernelSU), maintained by upstream.
- FinaSU themes and UI source: [LyraVoid/FinaSU](https://github.com/LyraVoid/FinaSU).

## Security

See [SECURITY.md](SECURITY.md) for vulnerability reporting. It describes this project's reporting channels. Identify the branch and version when reporting issues involving LunexSU additions.

## License

- `kernel/` is [GPL-2.0-only](kernel/LICENSE). Other components retain [GPL-3.0-or-later](LICENSE) and their original file-level license notices.
- FinaSU ports retain original author and GPL license notices. See the [UI port inventory](docs/lunexsu-folksu-theme.md) for scope and the reference version.

## Credits

- [KernelSU / tiann](https://github.com/tiann/KernelSU): upstream kernel Root, Manager and module system.
- [FinaSU (formerly FolkSU) / LyraVoid](https://github.com/LyraVoid/FinaSU): UI themes, home layouts, interaction effects and FPT format.
- [Kernel-Assisted Superuser](https://git.zx2c4.com/kernel-assisted-superuser/about/): the KernelSU idea.
- [Magisk](https://github.com/topjohnwu/Magisk): the powerful Root tool.
- [genuine](https://github.com/brevent/genuine/): APK v2 signature validation.
- [Diamorphine](https://github.com/m0nad/Diamorphine): kernel implementation techniques referenced by upstream.
