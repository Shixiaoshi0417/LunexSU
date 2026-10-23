# Managed Sui integration

## Implementation steps

- [x] Vendor the fixed FinaSU-compatible Sui source and preserve its licenses.
- [x] Build deterministic, per-ABI payloads without changing global toolchains.
- [x] Add default-off ksud deployment, recovery, supervision and diagnostics.
- [x] Bind Manager authority to the trusted APK identity and separate Sui grants.
- [x] Add Material, MIUIX and Fina management and permission-request interfaces.
- [x] Keep No Root Demo simulated and test failure, restart and upgrade paths (host coverage; device acceptance below).
- [x] Run Android Rust checks and normal/Demo Manager builds.

## Boundaries

This is a userspace extension, not a kernel root-policy replacement. Sui and
LunexSU grants remain distinct. Enabling Sui must not enable ZNN, adopt another
Sui installation, enable root ADB, or silently grant applications root access.
The feature starts disabled. Unsupported architectures and unverifiable
dependencies fail closed. Real device, OTA and partition operations are outside
the implementation validation performed here.

The reference is LyraVoid/FinaSU commit
`40c97e24d155f23364d8ebfe4b247034dc6547ef`, with its documented Sui revision
`e9674238a0dcfe13d9ea231cbdf866144e660db8` and Shizuku API revision
`13aeedbc9d83e65b3a4c74b736ca54c768dcb819`. Do not replace these with a moving
nightly download. Local adaptations and dependency licenses must remain visible.

Deployment must retain LunexSU's durable transaction/recovery guarantees rather
than copying the reference's two-rename publication sequence. Manager identity
registration must verify the trusted APK certificate, not merely a package name.
No Root Demo must not include the real payload, privileged Binder bridge, or
daemon lifecycle calls.

## Current implementation evidence

The imported baseline has 365 Git-blob-verified source files. Local adaptations
use module ID `lunexsu-sui`; they remain distinguishable from that baseline.
The host packager rejects ambiguous ZIP entries, bad paths, size/mode/architecture
mismatches and unverified source artifacts. ARM64 and x86_64 each retain their
corresponding 32-bit injection library; no RISC-V payload is fabricated.

The Java Manager predicate and ksud registration use the same public certificate
hash/size supplied to Core. They require the primary-user Manager package,
matching UID and one matching APK signer; a reused UID, No Root Demo or invalid
PR binding fails closed. Registration is private, bounded and atomically
published. Shell and root Sui servers keep their separate permission domain.

Host checks: `python -m unittest discover -s tests/sui -v` (34 groups). They use
synthetic ELF/certificate data, execute the pure Java predicate and guard the
fixed baseline/local adaptations. Source guards are not Binder or Android
execution tests.

The fixed source compiled successfully with its separate Gradle 9.8.1 / NDK 30
toolchain. Both primary architectures and their corresponding 32-bit injection
libraries were packaged and read back. Hashes, ELF architectures, dependencies,
and load-segment alignment were checked without running the resulting binaries.
Deterministic packaging is checked; a byte-reproducible Gradle ZIP build is not
claimed. The ordinary LunexSU NDK 29 lane remains unchanged.

The `ksud sui prepare` path only stages a **disabled** module through
the ordinary durable module transaction. It refuses external ownership,
conflicting Sui IDs, symlinks, enabled modules and pending removals. Its compiled
bundle is captured and validated before mutation. The vendor ADB-root policy is
kept as provenance, not loaded as runtime policy; no boot script or enable intent
is written. Native POSIX tests cover staging, metadata-failure rollback, identity,
boot intent, actual bundle read-back and bounded helper processes. They do not
run the Android broker or prove injection health.

## Runtime and permission hosting

`ksud sui enable` requires the matching Core API, `u:r:ksu:s0`, an active ReZygisk
or NeoZygisk backend, owned module/data paths, and a trusted Manager. It never
enables a provider or ZNN. Explicit enable writes only the two broker transition
rules; the vendor ADB-root policy is retained as provenance, not applied. The
intent is committed after module flags/policy are durable. Boot reconciliation
runs before policy loading and module scripts, disabling owned candidates after
an interrupted or invalid activation. The supervisor starts only after a full
reboot, tracks the foreground root child and its parent-death guard, bounds
restart attempts, and stops on disabled/changed ownership or identity. It probes
both root and shell Binders every five seconds using the bounded diagnostics
helper. Each generation gets a 60-second initial readiness window; after both
Binders have been seen alive, loss of either (or failed diagnostics) kills and
reaps root so the existing bounded restart loop recreates both servers. Root's
death also stops shell through its parent-death guard. A background/disconnected
Manager permission callback is not a server-liveness failure. Host process tests
cover this monitor and cleanup; they do not execute Android Binder or prove
on-device recovery.

Status distinguishes bundled bytes, desired state and actual root/shell Binder
diagnostics. Manager separately tracks its live permission callback: readable
payloads, existing processes or healthy server Binders do not prove this host is
attached. Only the unlocked main Manager process may attach. Requests have a
bounded queue, connection generation and deadline; grants revalidate package UID
and signing identity at dispatch. Stale callbacks and unsent old-generation work
are rejected after reconnect. An already-sent Binder RPC cannot be cancelled by
the Manager; its result must be verified after a timeout.
The Sui protocol supports persistent Shell and Root, one-time Root and deny;
unsupported one-time Shell is rejected by both UI and server, not converted into
Root. SystemUI cannot replace the Manager's permission callback. Upstream legacy
SystemUI/Settings management APIs remain, so this is not an exclusive-admin claim.

Material, MIUIX and Fina use shared, keyboard-aware management/request screens.
Background notifications open the same global request host; denied notification
permission leaves requests available in-app. Keep Manager running for prompts:
there is no claim that Android will always allow background UI or process startup.
No Root Demo has a separate in-memory controller and excludes the privileged
controller, Sui AIDL, daemon calls and payload from its actual APK.

## Operating and validation limits

Completed local checks include the actual Sui source build, ARM64 Android Rust
check/clippy/format sequence, and normal/Demo clean release, unit-test and
instrumentation-compilation builds. JVM results are 482/499 cases, respectively,
with no failures/errors and one Windows POSIX-shell skip each, covered by the
independent 14-case Manager/argv host suite. Nine Sui model and six timeout/recovery
tests run per variant. All 547 Manager build-time source hashes were checked
against both outputs.

PR #12 follow-up guards the four public Shizuku AIDL interfaces by their exact
upstream Git-blob hashes instead of weakening the application namespace check.
A lifecycle-command timeout retains the original future and reports an unknown
outcome. New mutations remain blocked until that command finishes and a fresh,
successfully parsed daemon status reconciles the state; the mutation is never
automatically retried or cancelled through the shared root shell. Timed-out
Binder workers are retired with their generation, allowing a replacement worker
without an unbounded queue or thread count (at most three live workers). If all
remain stuck, further IPC fails explicitly until capacity recovers or Manager
restarts. Host tests use controllable futures and uninterruptible fake calls,
not real Android Binder services.

Native POSIX regression results are 30 Sui cases, 25 boot/Magica lifecycle cases,
9 installer cases and 33 existing module/OTA/configuration cases. The latter
suite has two ignored worker entrypoints explicitly spawned by its interruption
tests; it also passed ten further runs with 16 test threads. Lock-release tests
account for transient fork-inherited descriptors without weakening production
locking. Eleven input-layout guards and the six modified workflow lint checks
passed. These results do not substitute for the device acceptance below.

- The page's version is the **bundled** version, not proof an installed module
  was replaced. Updating Manager does not silently overwrite installed Sui.
  To restage an owned installation, disable and fully reboot first, then enable
  and fully reboot again. Prepared updates use the existing recovery journal.
- Disable stops requesting the broker and writes disabled flags. Reboot is still
  needed to remove injected hooks and end already-running privileged clients or
  user services. Shell means Android UID 2000, not a sandbox: independent kernel
  root grants to that UID can permit further escalation. Optional escape
  protection and ADB-root controls are not exposed by this first UI.
- External Sui modules/data are never adopted. The first version deliberately
  has no destructive purge/migration action; permissions in owned data survive
  ordinary module removal. Repair of unknown ownership is not guessed.
- Current primary architectures are ARM64 and x86_64; their 32-bit injection
  libraries do not imply every 32-bit app/ROM/backend combination was tested.
  RISC-V is unavailable. This does not lower LunexSU's Android support floor.
- Android Rust compilation/lints, source builds, normal/Demo builds and host
  regressions are local checks. Manager builds use known daemon compilation
  fixtures, not a newly linked installable Sui release pair. Instrumentation was
  compiled, not device-executed. No root/device/ADB/module/partition operation or
  remote commit, review or release is part of this implementation session.

The remaining acceptance work is real-device Binder attachment/restart, concurrent
requests and notification denial, UID/package changes and revocation, provider
injection/SELinux, safe mode/recovery, real reboot/upgrade behavior and the supported
Android/ROM matrix. In particular Android 17/HyperOS compatibility, power-loss
durability and termination of existing privileges have not been established by
host tests. Keep the feature default-off and experimental until that work passes.
