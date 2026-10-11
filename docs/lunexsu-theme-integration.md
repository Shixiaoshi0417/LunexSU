# LunexSU Manager integration

## Plan and audit

- [x] Preserve the unfinished FolkSU theme work on `worktree-folksu-theme`.
- [x] Merge `codex/root-authorization-expiry`, including its persistence/KEEP review fixes.
- [x] Resolve the English/Chinese resources without removing either feature's strings.
- [x] Use LunexSU for the app name, APK prefix, messages, exported logs and fork update/source URLs.
- [x] Refresh effective authorization counts while the Manager is resumed.
- [x] Prevent a slow font import from overriding a newer font selection or reset.
- [x] Run authorization host tests, Android unit tests, instrumentation compilation and a clean release build.
- [x] Commit and submit a bilingual draft PR; leave `main` unchanged.

## Contracts and affected files

The Root feature follows the existing pre-management path: the Manager's Material
and MIUIX app-profile pages call the JNI authorization-profile API, mirrored by
ksud's shared UAPI, and the kernel performs admission and quota accounting.
`docs/lunexsu-authorization.md` describes the Core policy and persistence contract.
The theme merge adds no second Root request UI or userspace policy engine.

Integration changes are confined to Manager build settings, localized resources,
About/update/log UI, `ui/MainActivity.kt`, `ui/theme/FontConfig.kt` and font tests.
ksud help, diagnostics and installer output also use LunexSU; the `su -v`
KernelSU identity remains compatible with existing module detection. The Manager
CI build now runs unit tests and compiles its authorization instrumentation runner.
Existing package IDs, JNI names, module protocols, stored layout tokens and
KernelSU/FolkSU copyright attribution are retained for compatibility. Donation
and upstream documentation destinations remain explicitly attributed to upstream.

Once and Timed are scoped to the current boot. Once consumes one successful new
su admission; Timed uses kernel boottime and ignores wall-clock changes. Reboot
retains their configured mode but invalidates the grant. Permanent retains the
existing persisted behavior. A combined build must use UAPI 6 for Core, Manager
and ksud; the original theme checkout was UAPI 5 with a UAPI 6 bundled ksud.

## Validation

On 2026-10-11 the merged production authorization state tests passed with 64
threads and 1.28M attempts. The persistence driver tests passed all 10 I/O faults,
interruption, reboot rejection and safe retry cases. Android resources parsed
without duplicate names. Code commit `c49ac85a` passed:

| Check | Result |
| --- | --- |
| Android arm64 cargo ndk check, clippy, cargo fmt and release build | Passed (API 26) |
| Manager clean assembleRelease, unit tests and instrumentation compilation | Passed (110 tasks) |
| Theme/home/font-ordering unit tests | 15 passed |
| Release application label in every packaged locale | LunexSU |
| Release JNI authorization fields and packaged ksud executable/data sections | Passed |
| GitHub checks, including eight Android GKI versions and multi-platform ksud | All 27 reported checks passed |
| CI APK repack with arm64-v8a, x86_64 and riscv64 ksud | Passed |

The local release uses the existing `com.shixiaoshi0417.lunexsu` application ID and the
build's debug-signing fallback, with arm64 ksud. The downloaded CI test APK uses
`LunexSU PR` / `com.shixiaoshi0417.lunexsu.pr` and contains all three ksud architectures
and the CI-built modules. Its temporary certificate matches that CI's Core
builds. A device needs the matching Core/UAPI/signature; compilation does not
verify Root operation on an arbitrary installed kernel. See
[PR #2](https://github.com/Shixiaoshi0417/LunexSU/pull/2) for current CI results.

Both artifacts are generated files, excluded from Git. Build tools/caches are
isolated in ignored `target/` and WSL build directories. Those earlier checks did
not flash a device or install an app. The subsequent
[FPT/brand follow-up](lunexsu-fpt-import.md#validation) installed debug/test APKs
only on the dedicated Android Studio test emulator and recorded its UI/runtime checks.

Device execution, reboot/time-change and injected kernel I/O failures
require a disposable device running the matching Core and Manager; APK compilation
and host tests do not claim those runtime checks passed.
