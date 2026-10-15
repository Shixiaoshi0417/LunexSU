# Built-in Zygisk in LunexSU

## Behavior

- Built-in Zygisk is off by default. Enabling selects **ReZygisk**; the backend selector also
  offers **NeoZygisk**. VexZygisk is no longer bundled.
- Directly below the selector, **Use ZygiskNextNext** adds Zygisk Next API support. This is an
  independent companion loader, not a third standard Zygisk backend. It defaults to off and is
  greyed out until built-in Zygisk is enabled. Switching Re/Neo preserves the companion selection;
  disabling built-in Zygisk also disables the companion. Re-enabling starts with the companion off.
- Every successful change prompts for a full reboot, with "Reboot later" and "Reboot now".
  Pending changes remain visible. No injector is started, killed, or hot-switched by these actions.
- The card, selector, companion switch (including its disabled state), and reboot dialog use the
  native Material/MIUIX UI or the FinaSU theme palette and wallpaper overlay.
- The card is hidden without Core and disabled in temporary root (late-load) mode.

## Installation and recovery

The payloads are bundled APK assets installed through ksud's normal module flow. This feature
does not change ksud, the kernel, or the supercall interface.

| Action | Next-boot configuration |
|---|---|
| Enable or switch backend | Stage the new module, then mark the previous backend for removal. |
| Enable ZygiskNextNext | Stage `zygisknextsu`, leaving the standard backend installed. |
| Disable ZygiskNextNext | Mark only the companion for removal. |
| Disable built-in Zygisk | Mark the companion first, then all owned backends for removal. |
| Re-enable before reboot | Restore existing modules with `undo-uninstall` and `enable`; do not rerun installers. |

Steps are rolled back on failure, in reverse order, including a partially staged companion.
Rollback failures are reported rather than silently claimed to have succeeded. Normal Zygisk
modules are not removed by backend changes. Upstream backend uninstall scripts may remove their
own private state; this is not a promise to preserve all backend-specific settings.

All three owned modules carry `.lunexsu_builtin_zygisk` in `modules_update/<id>` and `modules/<id>`.
They are hidden from the regular module list and managed through Settings. A matching module id
in either directory without that marker is external or a failed install: it is neither adopted
nor restored, and enabling is blocked until it is removed.

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

| Component | Module id | Release | Source commit | License at that commit |
|---|---|---|---|---|
| ReZygisk | `rezygisk` | [v1.0.0](https://github.com/PerformanC/ReZygisk/releases/tag/v1.0.0) | [`333d423c`](https://github.com/PerformanC/ReZygisk/tree/333d423cde1a959958d9fce380bd017cf6c1cf64) | GPL-3.0 |
| NeoZygisk | `zygisksu` | [v2.4](https://github.com/JingMatrix/NeoZygisk/releases/tag/v2.4) | [`08080ef7`](https://github.com/JingMatrix/NeoZygisk/tree/08080ef775c7fbbd1ad5c4a5d837f81933437abd) | GPL-3.0 |
| ZygiskNextNext | `zygisknextsu` | [v1.8.3](https://github.com/VeryBaaad/ZygiskNextNext/releases/tag/v1.8.3) | [`e4da0823`](https://github.com/VeryBaaad/ZygiskNextNext/tree/e4da08237aa4a37a7cd53fd3243315ef24954618) | GPL-3.0 |

SHA-256:

- Re `ReZygisk-v1.0.0-release.zip`: `7904649b8dcaf2b060c3432df4fee302aeb1258da199d2b425335e82d49510e6`
- Neo `NeoZygisk-v2.4-289-release.zip`: `93a1425c67bb89f58a0dcb9fc823ec93f6472214f221e667a70b67c6a6e061a1`
- NextNext upstream `Zygisk-Next-Next-v1.8.3-184-e4da082-release.zip`: `5413ab654c1c167d843d480e61fce3fb01c01a277c5e10a23cb68801076f9fa4`
- NextNext installation ZIP: `a9de2634f1688e8f3705513aafaffcc03fd69593db2ac31d2d8133b632d5fdf6`

Re and Neo archives are unmodified. NextNext's upstream ZIP contains XZ-compressed ZIP entries
(method 95). Its installer calls BusyBox unzip, which cannot decode those entries. After verifying
the upstream archive, `scripts/normalize_zygisk_zip.py` deterministically repacks it using stored
entries. File names and bytes, including all 36 upstream `.sha256` files, are unchanged; the outer
container, order, attributes and timestamps change. The normalized archive has its own pinned hash.

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
- Local JVM tests cover planning, dependency/disabled-state rules, reboot state, rollback and Vex
  migration. Python tests cover the ZIP conversion; no backend/device compatibility is implied.
- Before release, test Re/Neo with the companion both off/on; every enable/disable/switch followed
  by reboot; failed installs/rollback; safe mode; external conflicts; Vex removal and late-load-hook
  cleanup; 32/64-bit apps, LSPosed and actual Zygisk Next API modules on stock and HyperOS devices.
- No device testing or ADB was performed. Module compatibility is not guaranteed.
