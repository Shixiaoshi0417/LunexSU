# Built-in Zygisk in LunexSU

## Behavior

- Built-in Zygisk is off by default. Enabling selects **ReZygisk**; the backend selector also
  offers **NeoZygisk**. VexZygisk is no longer bundled.
- Every off-to-on request first opens a confirmation in Material, native MIUIX or Fina. It
  explains the boot-time external-provider policy, persistent disable marks and full reboot.
  Cancelling, leaving Settings or backgrounding the app discards this confirmation.
- Directly below the selector, **Use ZygiskNextNext** adds Zygisk Next API support. This is an
  independent companion loader, not a third standard Zygisk backend. It defaults to off and is
  greyed out until built-in Zygisk is enabled. Switching Re/Neo preserves the companion selection;
  disabling built-in Zygisk also disables the companion. Re-enabling starts with the companion off.
- **ZygiskNextNext is high risk:** boot failures have been reported. Each new enable request
  requires an explicit recovery-capability acknowledgement and a fresh ten-second wait before the
  red Continue button becomes available. Cancelling, leaving Settings or backgrounding the app
  discards the confirmation. Disabling the companion does not require this wait. The warning uses
  native MIUIX or Material/Fina dialogs; it is not a fix for the boot failure or a recovery guarantee.
- Every successful change prompts for a full reboot, with "Reboot later" and "Reboot now".
  Pending changes remain visible. No injector is started, killed, or hot-switched by these actions.
- The card, selector, companion switch (including its disabled state), and reboot dialog use the
  native Material/MIUIX UI or the FinaSU theme palette and wallpaper overlay.
- The card is hidden without Core and disabled in temporary root (late-load) mode.

### Homepage overview

A separate, read-only Zygisk status card sits after the homepage device information. It starts
collapsed and expands on tap, with height/fade, arrow and press animations in Material, native
MIUIX and Fina. It shows next-boot backend configuration, pending changes, the observed
`zygisk-ptrace` monitor and installed/staged module versions read from their respective directories.
It never enables a backend or ZNN, and it does not bypass the high-risk confirmation.

The overview refreshes every five seconds only while an authorized homepage is visible. Failed or
incomplete queries are unavailable, not disabled. Configuration and process observations do not
prove injection succeeded; missing ptrace processes cannot determine NeoZygisk or ZNN health.
Versions are not inferred from the APK's bundled payloads. Safe mode and external/legacy module
warnings take precedence over optimistic status labels.

## Installation and recovery

The payloads are bundled APK assets installed through ksud's normal module flow. ksud also
enforces the coexistence policy below; the kernel and supercall interface are unchanged.

### External standalone injectors

Only the exact known provider IDs `rezygisk` (Re/Vex) and `zygisksu` (Neo/Zygisk Next) are
recognized. A `zygisk/` plugin directory or a matching display name is **not** a provider signal.
LSPosed, Sui, ordinary Zygisk plugins and the optional `zygisknextsu` companion are not external
injectors to auto-disable. Unknown providers and global hooks outside module directories still
require manual removal. In particular, disable marks cannot remove Vex's global hooks.

When an enabled owned backend is configured, next-boot init.rc/preinit snapshots omit the other
provider. After module promotion at post-fs-data, ksud creates `disable` markers for the other
known provider before module policy and startup scripts. The per-module gate also applies to later
stages, and fails closed for injectors on unsafe filesystem entries without blocking unrelated
modules. No running process is killed or hot-switched. Safe mode retains its disable-all behavior.
Staging the setting does not write external disable marks in the current boot. These guards require
the matching updated ksud; they cannot undo hooks already executed earlier in an existing boot.

Disable marks persist. Turning the built-in off never automatically re-enables an external
provider. Disable the built-in and fully reboot before manually restoring an external injector.
No automatic backup, adoption, migration or uninstall of external providers is performed.

| Action | Next-boot configuration |
|---|---|
| Enable or switch backend | Stage the new module, then mark the previous backend for removal. |
| Enable ZygiskNextNext | Stage `zygisknextsu`, leaving the standard backend installed. |
| Disable ZygiskNextNext | Mark only the companion for removal. |
| Disable built-in Zygisk | Mark the companion first, then all owned backends for removal. |
| Re-enable before reboot | Restore existing modules with `undo-uninstall` and `enable`; do not rerun installers. |

An APK update does not overwrite an already installed companion. To upgrade an owned NextNext
installation to the newly bundled release, disable the companion and reboot, then enable it again
and reboot. Do not toggle it back on before the first reboot; that restores the existing payload.
Do not attempt this on an affected device without a verified recovery path. Safe mode is not
guaranteed to recover every boot failure.

Steps are rolled back on failure, in reverse order, including a partially staged companion.
Rollback failures are reported rather than silently claimed to have succeeded. Normal Zygisk
modules are not removed by backend changes. Upstream backend uninstall scripts may remove their
own private state; this is not a promise to preserve all backend-specific settings.

All three owned modules carry `.lunexsu_builtin_zygisk` in `modules_update/<id>` and `modules/<id>`.
They are hidden from the regular module list and managed through Settings. A matching module id
in either directory without that marker is external or a failed install: it is neither adopted
nor restored. Only an external copy occupying the **requested backend or requested companion ID**
blocks installation, even if disabled or pending removal. The user must uninstall it and reboot
first. A different-ID known injector can remain installed and will be disabled on boot. Ownership
must be consistent across live and staged copies; an owned live copy never authorizes overwriting
an unowned staged copy (or vice versa).

When switching, the new backend is staged before the old one is marked for removal. If the
install fails, the old backend is not marked for removal. Rollback checks both directories even
if the installer failed before creating `modules/<id>`. The new payload is marked `remove` and
`disable`, and its ownership markers are cleared so it cannot be restored as a successful install.
At the next boot, ksud promotes the marked staging directory and runs its `uninstall.sh` before
module scripts start. The payload is retained until then so its uninstaller can clean up hooks.
Reboot before trying to enable it again; remaining staging is treated as a conflict.

If a later step fails, completed steps are also rolled back. Cleanup errors are reported alongside
the original error; a filesystem or shell failure can still prevent cleanup and requires recovery.

**Old built-in Vex migration:** Vex and Re share `rezygisk`. The card detects Vex by its module
name and requires disabling built-in Zygisk and rebooting before enabling Re. This lets Vex's
own uninstall script remove its global boot/late-load hooks; the Manager does not overwrite an
active Vex directory or run its uninstall script live. External Vex must be uninstalled manually.

Status is inferred from ksud's flags: configured means owned and not `remove`/`disable`; active
means owned and not `update`/`disable`. A pending removal continues until reboot; a staged install
does not run yet. A `zygisk-ptrace32/64` process indicates the standard backend's monitor, not
proof that every module or the companion initialized correctly. Safe mode disables all modules.

## Pinned payloads and licenses

`fetchZygiskAssets` caches downloads in `manager/.gradle/pinned-assets`, verifies SHA-256, and stages
the assets. Manager verifies the installation archive again. Python 3 with its standard `lzma`
module is required at build time for the companion archive conversion described below.

Before a release, check KernelSU upstream and each bundled component's latest non-prerelease
GitHub Release. Update the fixed release URL, source commit, license and pinned archive hashes
together; do not resolve a moving `latest` URL during a build. On 2026-10-18, the releases below
were the latest stable releases of all three bundled components.

| Component | Module id | Release | Source commit | License at that commit |
|---|---|---|---|---|
| ReZygisk | `rezygisk` | [v1.0.0](https://github.com/PerformanC/ReZygisk/releases/tag/v1.0.0) | [`333d423c`](https://github.com/PerformanC/ReZygisk/tree/333d423cde1a959958d9fce380bd017cf6c1cf64) | GPL-3.0 |
| NeoZygisk | `zygisksu` | [v2.4](https://github.com/JingMatrix/NeoZygisk/releases/tag/v2.4) | [`08080ef7`](https://github.com/JingMatrix/NeoZygisk/tree/08080ef775c7fbbd1ad5c4a5d837f81933437abd) | GPL-3.0 |
| ZygiskNextNext | `zygisknextsu` | [v1.12.0](https://github.com/VeryBaaad/ZygiskNextNext/releases/tag/v1.12.0) | [`ca4d4b7a`](https://github.com/VeryBaaad/ZygiskNextNext/tree/ca4d4b7a5dd06f83c228ec23cb25ea49dfe5c8f2) | GPL-3.0 |

SHA-256:

- Re `ReZygisk-v1.0.0-release.zip`: `7904649b8dcaf2b060c3432df4fee302aeb1258da199d2b425335e82d49510e6`
- Neo `NeoZygisk-v2.4-289-release.zip`: `93a1425c67bb89f58a0dcb9fc823ec93f6472214f221e667a70b67c6a6e061a1`
- NextNext upstream `Zygisk-Next-Next-v1.12.0-202-ca4d4b7-release.zip`: `d7bf2cc7eda22fa2e918029eddf71f2b368c3613f6cbf671acb21e38af498d74`
- NextNext installation ZIP: `e818e03c522a6c1e5e513c76aa8e365589b2f17d87c0085dc5b348dee1f7330e`

Re and Neo archives are unmodified. NextNext's upstream ZIP contains XZ-compressed ZIP entries
(method 95). Its installer calls BusyBox unzip, which cannot decode those entries. After verifying
the upstream archive, `scripts/normalize_zygisk_zip.py` deterministically repacks it using stored
entries. File names and bytes, including all 37 upstream `.sha256` files, are unchanged; the outer
container, order, attributes and timestamps change. The normalized archive has its own pinned hash.

The v1.12.0 upgrade includes upstream's optional ZygiskNext takeover control in its WebUI.
Upstream checks for the original Zygisk Next identity before invoking that module's controller;
this does not turn NeoZygisk into Zygisk Next. LunexSU's companion remains off by default, with
the same recovery acknowledgement and wait. No boot-failure fix or device compatibility is
claimed by this payload upgrade; already installed companions still require the upgrade sequence
above.

Pinned license texts ship as `assets/zygisk/{re,neo,nextnext}-LICENSE`. Re's README historically
mentions AGPL, but the selected commit's actual LICENSE is GPLv3. Preserve upstream and dependency
notices and provide the corresponding source/build materials when redistributing. ZygiskNextNext
is an open-source API loader, **not** the closed-source ZygiskNext distribution.

## Limits and device validation

- Re and Neo accept arm, arm64, x86 and x86_64; unsupported devices (including riscv64) cannot
  enable built-in Zygisk even though NextNext itself has additional architectures.
- Neo does not inject 32-bit apps on 64-bit devices. A companion API loader does not remove this
  limitation for standard Zygisk modules.
- Both backends declare `managedFeatures=kernel_umount`; refresh the managed Kernel umount setting
  after any change. NextNext can also affect namespaces and mounts, so metamodule compatibility
  and per-app umount behavior require device testing.
- Neo warns but still installs when the kernel version code is at least 30000.
- Local JVM tests cover planning, explicit enable consent, same-ID ownership conflicts,
  dependency/disabled-state rules, reboot state, rollback and Vex migration. Rust host tests use
  temporary module trees to exercise boot reconciliation, next-boot filtering, persistent marks
  and unsafe filesystem entries. Python fixtures execute the actual read-only ownership script
  and check lifecycle wiring, rollback and ZIP conversion. These are not Android boot or injection
  tests; no backend/device compatibility is implied.
- Before release, test Re/Neo with the companion both off/on; every enable/disable/switch followed
  by reboot; failed installs/rollback; safe mode; external conflicts; Vex removal and late-load-hook
  cleanup; 32/64-bit apps, LSPosed and actual Zygisk Next API modules on stock and HyperOS devices.
- No device testing or ADB was performed. Module compatibility is not guaranteed.
