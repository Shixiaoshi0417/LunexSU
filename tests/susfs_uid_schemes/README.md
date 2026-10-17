# SUSFS UID editor regression

Run local pure tests using the existing cached Kotlin/JUnit/JSON dependencies:

```powershell
python tests/susfs_uid_schemes/run_host_tests.py --temp D:\WorkSpace\LunexSU-build-cache\tmp --aapt2 D:\Android\Sdk\build-tools\37.0.0\aapt2.exe
```

This compiles `SusfsPresentation.kt` and the complete fresh `SusfsStatus` model
from each formal/demo replacement source, then runs the existing SUSFS tests and
`SusfsRedirectUidTest`. No repository commands, JNI, root, SUSFS engine, network,
Gradle, device or ADB are used. XML/resource and shared-editor wiring checks are
included. Optional `--aapt2` compiles only the three affected SUSFS resource files;
this does not claim device rendering coverage for the three UI modes.

The regression covers built-in choices/input 0..4, LKM 0..2, unknown/read-only
states, and retention of imported 3/4 values during preview, validation and edits.
Imports remain daemon-validated saved configuration; the editor does not clamp
or reinterpret their scopes or apply them automatically.

## Pinned source checked before changing the selector

Existing D-drive cache:
`D:\WorkSpace\LunexSU\.claude\tmp\susfs-inspect\899976e9891fd91787df641c2ab0ba42c80d0ee7`.
These files were verified against their cached pinned Git-tree blob IDs:

- `kernel/susfs_abi.h:65-73`: scheme 3 is `UID_UMOUNTED_APP_PROC`;
  scheme 4 is `UID_UMOUNTED_PROC`.
- `kernel/susfs_open_redirect.c:207-229`: documents the built-in per-thread
  unmounted flag, the app UID condition for 3, and no UID condition for 4;
  the LKM substitutes UID-based answers and is not semantically identical.
- `PROC_INTERFACES.en.md:112-122`: numeric scopes and forward/reverse distinctions.

The Manager intentionally uses neutral numeric built-in labels for 3/4 rather
than implying equivalent LKM behavior or verified runtime redirect coverage.
The existing Rust `model.rs` accepts 0..4; `apply.rs` rejects values above 2
only for LKM. Neither Rust file nor its ABI is changed by this repair.
