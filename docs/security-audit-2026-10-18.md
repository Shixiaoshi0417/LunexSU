# Security hardening review (2026-10-18)

This change addresses the fifteen findings supplied for the `develop` review.
Host regression tests exercise production parsers and transaction code with
disposable files or mocked kernel/Android interfaces. They do not establish
device-specific kernel, boot, OTA, WebView, or module compatibility.

## Change map

| Finding | Boundary changed | Regression coverage |
| --- | --- | --- |
| F01 | Validate profile/template identifiers before shell or storage access; quote every shell argument; use no-follow, regular single-link profile files | Manager import/argv tests and Unix profile storage tests |
| F02 | Snapshot temporary ADB state before changes; restore on normal error paths and unwinding; require explicit network-risk acceptance | Fake property, permission, dispatch, and child-completion failures |
| F03-F04 | Track installed hooks independently of feature enablement; publish originals before replacements; restore and drain callbacks before retiring metadata | Hook publication/rollback tests for hlist and static-call layouts |
| F05 | Stage SELinux allocations before publishing type metadata; discard a private policy if any command in its batch fails | Allocation fault injection and first/middle/last batch failures |
| F06 | Remove private PR signing keys and cross-job secret outputs; make PR artifacts compile-only | PR signing workflow fixtures and identity checks |
| F07 | Journal publication identities and live metadata before changes; exclude provisional updates until durable commit; retain recovery material until both promotion/rollback parents are synced | Isolated installer failures, repeated directory-sync faults, filesystem rollback, and process death at six publication boundaries with/without an older pending update |
| F08 | Lock the complete configuration read-modify-write; use unique temporary files and atomic publication | Multiple writers/readers and killed lock holders |
| F09 | Restore and verify boot backups before deleting the working/backup directory | Fake restore and cleanup order/failure tests |
| F10 | Validate slots before flashing; pass bootctl arguments separately and check its status | Fake bootctl argument and failure tests |
| F11 | Check unsigned payload lengths, offsets and output extents before narrowing or allocation; bound and stream decoding | Small malformed inputs, short reads, range/overflow and extraction tests |
| F12 | Require a requested setup ref to fetch and check out successfully before kernel integration | Disposable Git repositories with bad refs, checkout failures and dirty work |
| F13 | Treat torn/oversized/short-read authorization markers conservatively; never append across an incomplete record | Actual marker reader/writer fault injection and recovery tests |
| F14 | Restrict privileged WebUI content to a validated local origin and isolated profile; deny remote subframes/resources/redirects and service-worker networking | URL policy tests and compiled Android WebView regressions |
| F15 | Validate bounded strict JSON before Android's permissive JSON parser | Grammar/depth/size tests and compiled Android parser regressions |

## Intentional behavior changes

- PR builds no longer produce installable Manager/Core signing pairs. See
  [PR build signing policy](pr-build-security.md). Old exposed PR keys are not
  revoked by rebuilding or deleting logs. No production-key exposure is claimed.
- Magica cannot guarantee that vendor `adbd` binds only to loopback. It refuses
  before changing properties unless `--magica-allow-network` explicitly accepts
  that limitation. The existing automatic Manager path does not supply this
  flag. Unsupported appcompat property overrides also fail before mutation.
  In-process cleanup cannot recover from `SIGKILL`, abort or power loss.
- Runtime hooks are refused if the required callback-drain primitive is absent.
  A slot that cannot be restored prevents unsafe teardown; forced module unload
  is not made safe by this change.
- Atomic module-directory exchange requires filesystem/kernel support. An
  unsupported exchange fails without deleting the previous working payload.
- Privileged WebUIs must bundle their executable content locally. External links
  opened by a direct user action go to the system handler, without the root
  bridge. Missing WebView profile-isolation support fails closed. Installed root
  modules and their local scripts remain trusted code, not sandboxed plugins.
- Theme metadata must be standard JSON. Comments, single quotes and other
  Android-parser extensions are deliberately rejected.

## Local validation

- Android arm64 workspace `cargo ndk` check and Clippy passed without warnings,
  followed by workspace formatting and a clean formatting check.
- Normal and No Root Manager builds passed release assembly, JVM tests and
  Android test-APK compilation. The suites contain 460 and 477 cases respectively,
  with no failures/errors and one Windows-only POSIX-shell skip each. That argv
  case passed separately against a real POSIX shell.
- Host authorization/sanitizer, SELinux allocation/batch, hook protocol, profile,
  boot/Magica, build-reference, signing and workflow checks passed. Native POSIX
  module tests passed all 9 installer fixtures and 22 Rust tests; the ignored
  Rust entry point is a worker explicitly invoked by the process tests. Atomic
  exchange assertions were executed, not skipped on an unsupported filesystem.
- Both Manager variants' build-time source hashes and APK/native isolation were
  checked. Local daemon inputs were restored after compilation.

The first GitHub review identified two additional boot-promotion durability
windows: cleanup after a failed live-parent sync, and cleanup before a rollback's
parent syncs. Both now retain recovery material, retry syncs on recovery, and
require durable garbage classification before recursive cleanup. Fault injection
covers both parents, existing/absent live modules, repeated failures and recovery,
and interrupted garbage deletion. It does not emulate physical power loss.

## Validation limits

Android instrumentation is compiled, not run on a connected device. Host hook
tests cannot replace builds across the supported kernel matrix or actual unload
stress tests. The normal Manager APK built locally uses known daemon compilation
fixtures; it is not an installable security-release package for the modified
daemon. No ADB, partition operation, live module installation or kernel loading
is used by these checks. Main merging and Beta4 publication remain separate
decisions.
