# LunexSU SUSFS management

## Scope

The Settings > SUSFS page manages an existing SUSFS engine. It does not bundle,
install, load, or unload an engine and does not modify boot images. The engine's
installation, architecture, KMI, and vendor-kernel compatibility remain separate.

The six sections cover overview, settings, paths, spoofing, redirection, and LKM
extensions. Material and Fina theme modes use themed segmented components and
dialogs; MIUIX uses its own cards, switches, text fields, scaffold, and dialogs.
The entry remains visible without root so the unavailable/read-only reason is
shown rather than hiding the feature.

## Protocol and configuration

- Only the audited SUSFS `v2.3.0` ABI with acknowledged version, feature, and
  variant queries is writable. Unknown protocols remain visible but read-only.
- Features and LKM proc extensions are gated separately. LKM-specific controls
  require the `SUSFS_LKM_MODULES` marker and their corresponding proc node.
- `ksud susfs status`, `export`, `import`, and `apply` require root. Import reads
  bounded JSON from stdin, validates it, and saves it; it does not apply rules.
- Configuration belongs to LunexSU at `/data/adb/ksu/lunexsu-susfs/`. Other
  managers' configuration and module files are not edited. Directories are
  root-owned/private, files are bounded root-owned regular files, symlinks are
  rejected, updates are locked and atomic, and kernel receipts reset each boot.
- Automatic application is off by default and is blocked in safe mode. Saving
  and acknowledged kernel application are different states. Failed operations
  and changes that cannot be undone live are reported separately.
- An acknowledged operation is not proof that all hiding hooks work or that a
  particular application cannot detect root. There is no complete kernel-rule
  readback in this protocol.

## Boot timing and safety

Dynamic kstat needs a real pre-mount capture and a matching post-mount update.
The pre-mount checkpoint runs before common and module scripts; it only works
if the SUSFS engine is already available then. This implementation does not
move loaders earlier. An engine loaded by a later module script, or a late-loaded
Core, cannot retroactively capture the original metadata. Such operations remain
pending; rebooting alone does not repair an unsuitable engine-loading order.

Ordinary automatic rules can apply during service/boot-completed. Changed or
removed append-only rules may require a reboot; disabling automatic application
does not undo rules already active in the kernel.

An independent SUSFS LKM can cache a function pointer into the LunexSU Core.
Core hot-unload is therefore refused while the known LKM module or its protocol
marker is detected, before stopping services or killing processes. Disable the
SUSFS module and reboot instead. This is not an atomic module-reference protocol;
do not concurrently load an external LKM while attempting to unload Core.

## Provenance

The management model, Rust protocol/storage/application implementation, Manager
editors, and tests are adapted from [LyraVoid/FinaSU](https://github.com/LyraVoid/FinaSU)
at `f80852b9300aba6e9b468fbc212e7e76416751d0` (GPL-3.0). Its independently
implemented ABI definitions retain their GPL-2.0-only notice and reference to
`inforcqb/susfs4ksu-lkm`'s `susfs_abi.h`. LunexSU adaptations include its namespace,
owned storage, native MIUIX/themed presentation, lifecycle integration, and Core
unload guard. Original notices are retained in the corresponding sources.

## Validation and device checklist

Manager compilation and JVM tests cover parsing, read-only capability gating,
import previews, stale edits, and operation reports. Rust tests cover wire
layouts, validation, protocol classification, application receipts, and kstat
phase requirements. These tests do not execute SUSFS kernel mutations.

Before relying on this feature on a device, verify without ADB:

- No root, no engine, and unknown protocol stay read-only.
- Material, MIUIX, and imported themes render all six sections and dialogs.
- Save/export/import preserve rules without silently applying them.
- Apply reports actual acknowledgements and individual errors.
- Automatic apply is opt-in, safe mode skips it, and boot receipts reset.
- Late-loaded engines do not falsely report dynamic kstat as applied.
- Core hot-unload is refused while the independent LKM is active.

No new device validation has been performed for this implementation.
