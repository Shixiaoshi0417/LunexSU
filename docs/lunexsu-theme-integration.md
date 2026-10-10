# LunexSU Manager integration

## Plan and audit

- [x] Preserve the unfinished FolkSU theme work on `worktree-folksu-theme`.
- [x] Merge `codex/root-authorization-expiry`, including its persistence/KEEP review fixes.
- [x] Resolve the English/Chinese resources without removing either feature's strings.
- [x] Use LunexSU for the app name, APK prefix, messages, exported logs and fork update/source URLs.
- [x] Refresh effective authorization counts while the Manager is resumed.
- [x] Prevent a slow font import from overriding a newer font selection or reset.
- [ ] Run authorization host tests, Android unit tests, instrumentation compilation and a clean release build.
- [ ] Commit and submit a bilingual draft PR; leave `main` unchanged.

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
without duplicate names. Final Android/ksud build results are pending.

Device execution, reboot/time-change and injected kernel I/O failures
require a disposable device running the matching Core and Manager; APK compilation
and host tests do not claim those runtime checks passed.
