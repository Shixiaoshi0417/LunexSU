# Built-in Zygisk in LunexSU

## Behavior

- Settings has a built-in Zygisk card. It is off by default.
- Turning it on selects VexZygisk. NeoZygisk can be chosen instead from the same card.
- Every change takes effect only after a full reboot. Nothing is loaded or unloaded at runtime.
- After a successful change, the card shows a reboot dialog with "Reboot later" and "Reboot now".
  If you choose "Reboot later", the card keeps showing the pending state until the device
  reboots.
- The card and the dialog are drawn in all three UIs:
  - native Material
  - native MIUIX
  - the FinaSU theme UI, which uses the pack palette and the wallpaper overlay
- The card is hidden when Core is unavailable. It is disabled in temporary root (late-load)
  mode, where a reboot would drop root.

## Implementation

The backends are the unmodified upstream release zips. They are bundled as APK assets and
installed through ksud's regular module commands. ksud, the kernel and the supercall interface
are unchanged.

| Action | Effect |
|---|---|
| Enable or switch to a backend | `ksud module install` stages its zip. |
| Disable, or leave the previous backend | `ksud module uninstall` marks the module for removal. Its own `uninstall.sh` then runs on the next boot. |
| Re-enable a backend before rebooting | `undo-uninstall` and `enable` restore it. |

Re-enabling never reinstalls the backend. Reinstalling would rerun the installer, and VexZygisk's
installer stops the running monitor.

When switching, the new backend is staged before the old one is marked for removal. If the
install fails, the current setup stays in place. If a later step fails, the steps that already
succeeded are rolled back, so the next boot never starts both backends.

Installed backends carry a `.lunexsu_builtin_zygisk` marker in both `modules_update/<id>` and
`modules/<id>`. The marker has two uses:

- The module list hides marked modules, so they are managed only from Settings.
- A `rezygisk` or `zygisksu` module without the marker was installed by the user. It is treated
  as an external Zygisk, and enabling the built-in Zygisk is refused until it is removed, so
  Zygote is never injected twice.

The card reports state as follows:

- **Next boot:** an owned module that is not marked `remove` or `disable`.
- **This boot:** an owned module that is not `update` or `disable`.
- **Running:** a live `zygisk-ptrace` monitor process.

Safe mode disables every module. A safe-mode-disabled backend therefore shows as off, and turning
the switch on restores it.

## Bundled versions

The Gradle task `fetchZygiskAssets` downloads each file once into `manager/.gradle/pinned-assets`.
It verifies the SHA-256 before staging the file as an asset. The Manager checks the same hash
again before installing.

| Backend | Module id | Release | SHA-256 | Source | License |
|---|---|---|---|---|---|
| VexZygisk | `rezygisk` | [v2.2.0](https://github.com/Lxiaoyao077/VexZygisk/releases/tag/v2.2.0) `VexZygisk-v2.2.0-770-1d35d50-release.zip` | `9656568bdc8755941eb62eadde1acc14e6a9fec32da3a84282e061ca1b2c1b04` | [`1d35d502`](https://github.com/Lxiaoyao077/VexZygisk/tree/1d35d502ab2495d08b40bb77a5c4defb5bf64342) (build commit, two commits after the tag) | AGPL-3.0 |
| NeoZygisk | `zygisksu` | [v2.4](https://github.com/JingMatrix/NeoZygisk/releases/tag/v2.4) `NeoZygisk-v2.4-289-release.zip` | `93a1425c67bb89f58a0dcb9fc823ec93f6472214f221e667a70b67c6a6e061a1` | [`08080ef7`](https://github.com/JingMatrix/NeoZygisk/tree/08080ef775c7fbbd1ad5c4a5d837f81933437abd) | GPL-3.0 |

The license texts from those commits ship next to the zips as `assets/zygisk/vex-LICENSE` and
`assets/zygisk/neo-LICENSE`.

## Known limits

- **Architecture:**
  - VexZygisk installs only on arm64.
  - NeoZygisk supports arm, arm64, x86 and x86_64. On 64-bit devices it does not inject 32-bit
    apps.
  - Devices without a supported backend (for example riscv64) cannot enable the switch.
- **Kernel umount:** both modules declare `managedFeatures=kernel_umount` and handle unmounting
  themselves. While a backend is active, the Kernel umount setting is shown as managed.
- **Switching from NeoZygisk to VexZygisk:** VexZygisk's installer stops the running
  `zygisk-ptrace64` monitor. Processes that were already injected stay injected, but newly
  started apps are not injected until the reboot.
- **NeoZygisk on LunexSU:** its installer shows a warning, but still installs, when the kernel
  version code is 30000 or higher.
- **Testing:** none of this has been tested on a device yet. Module compatibility depends on the
  chosen backend.
