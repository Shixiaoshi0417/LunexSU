# LunexSU Root authorization duration

## Review and implementation plan

Manager's pre-management AppProfile screen calls JNI `get/setAppProfile`.
The kernel's immutable, RCU-published `app_profile` is indexed by full Android
UID and includes the package key, `allow_su`, and root/non-root configuration.
It is persisted by Core in `/data/adb/ksu/.allowlist`. ksud stores custom
SELinux rules, not a second authorization database. Its `root_shell` already
enters with the profile applied by `sucompat`; `su::grant_root` separately uses
the GRANT_ROOT ioctl. No request dialog or daemon authorization broker is added.

The admission points are `feature/sucompat.c` and `supercall/dispatch.c`.
Both now obtain a lease from the existing allowlist before applying a copied
root profile. Copying the selected profile prevents concurrent deletion from
silently falling back to the more privileged default profile. The RCU lookup
and granted-UID queries also check runtime policy. Module mounting and
Metamodule code are unchanged.

Files: `uapi/authorization.h`, `uapi/supercall.h`,
`kernel/policy/authorization_state.h`, `kernel/policy/allowlist.{c,h}`,
`kernel/policy/app_profile.{c,h}`, `kernel/feature/sucompat.c`,
`kernel/supercall/dispatch.c`, Manager JNI/Natives and AppProfile screens,
`kernel/hook/{setuid_hook,tp_marker}.c`,
localized strings, and `tests/authorization`. Implementation is on
`codex/root-authorization-expiry`, not main.

## Contract

- Permanent preserves existing pre-authorization. Manager and already Root
  processes in the KSU domain retain upstream trusted behavior. Temporary
  grants are offered only to installed App UIDs, not the always-trusted Manager.
- Once reserves its one credit under the allowlist mutex. Success means
  **exec into ksud succeeded with the selected Root profile**, i.e. one new
  Root `su` process has started. A later invalid shell argument, shell exec
  error cannot refund already-obtained Root access. A required su-session fd
  allocation failure kills the new temporary task before any userspace
  instruction runs and refunds its reservation.
  GRANT_ROOT cannot consume/use Once: it is not an exec into su. Failed prepare
  or exec refunds; a failed temporary exec is SIGKILLed before it can return
  to userspace with changed credentials/seccomp/namespaces. Successful Root
  processes and their descendants are not terminated or stripped of privileges.
- Timed permits repeated admissions strictly before the CLOCK_BOOTTIME
  deadline. Sleep counts. Changing wall-clock time cannot extend a grant.
  Renewal starts a new duration from the current boottime (not from the old
  deadline). UI presets are 5/15/60 minutes; custom values are 1-525600 minutes.
- Once and Timed are **boot-scoped**. Reboot (or module unload/reload) retains
  the selected mode/duration but invalidates all deadlines and credits. Soft
  Android framework restart within the same kernel does not reset boottime.
- KEEP edits cannot rearm, renew, or change mode. Explicit GRANT is required.
  Revocation invalidates runtime policy. Stale generation tokens fail with
  EAGAIN; grant/profile changes while a lease is in flight fail with EBUSY.
  Revocation can replace a live entry with denied policy; outstanding leases
  retain their snapshot and cannot refund into the replacement. A request
  already admitted is an existing authorization, so revocation does not kill it.

## Identity and persistence

Runtime identity is full UID plus package and a pinned O_PATH reference to
`/data/user/<userId>/<package>`, owned by that UID. Each actual temporary
admission verifies the current directory is that same inode. Keeping the inode
pinned prevents immediate inode-number reuse from impersonating the old
installation. UID changes, directory replacement/data reset, reinstall, removal
of that Android user, or a missing/locked data directory fail closed. Shared UID
apps share one grant and one selected package anchor; removal of the anchor's
data directory or upstream package pruning invalidates their temporary grant.
Existing package observer pruning remains.
Data-preserving operations which keep the full UID, package and data inode
retain that identity; they never add credits or extend the deadline. In
particular, a per-user uninstall with retained data while the package remains
installed for another user may not be pruned by the upstream global package
observer. Ordinary uninstall/data replacement fails the inode check. This
data-retention case must be checked separately on the release test device.
UID-wide reads retain the saved anchor and generation, including after UID
reuse. An explicit grant binds the package currently selected in Manager;
ordinary edits preserve the existing anchor. Process hook eligibility uses
configured membership so a busy Once reservation cannot hide the check from
new App processes. Hook eligibility never bypasses actual admission checks.

The v4 app_profile/UAPI layout is unchanged. New sized GET/SET_AUTH_PROFILE
ioctls carry policy plus the complete profile in one transaction (UAPI 6).
Legacy SET_APP_PROFILE preserves temporary state and cannot turn an expired
grant permanent. New Managers reject temporary grants on old kernels.
Core+Manager+ksud must be deployed together to satisfy existing UAPI checks.

Disk format 5 is a v4 profile followed by u32 mode and u64 configured duration.
No deadline/credit/install handle is persisted. Formats 2-4 migrate as permanent.
Unknown modes, truncated records and future versions are rejected. An upstream
downgrade rejects format 5 rather than reading temporary grants as permanent.
Explicit policy writes are synchronous and fsynced **before** publishing the
new in-memory profile; all file writers hold the same mutex from open through
fsync. A failed write reports failure and does not publish the candidate.
The existing truncate-in-place storage strategy can lose entries on a crash;
it fails closed and does not recover a temporary grant as permanent. No backup
containing stale permanent grants is used.

## Validation

Host tests compile the production state machine, including 64-thread Once
contention, failure refund, exact expiry boundaries, renewal, reboot invalidation,
invalid durations/overflow and v4/new ioctl layout assertions:

```sh
python3 tests/authorization/run_host_tests.py
```

Device integration tests require a disposable **debuggable** fixture app, a
kernel built from this branch and a recognized matching Manager+ksud. They must
not be run against an app whose existing authorization should be retained.
See `tests/authorization/README.md`. Host state tests do not replace device
tests of exec/credential effects, SELinux, identity, persistence or either UI.

### Checks run on 2026-10-10

| Check | Result |
| --- | --- |
| Production C state machine, 64 threads / 1.28M attempts | Passed |
| Exact expiry, failure refund, revoke/refund, renewal/reboot, invalid inputs, ABI | Passed |
| Core changed objects with x86_64 Linux 7.0 headers | Passed |
| Kernel-wide clang-format check and git diff --check | Passed |
| Android NDK arm64 JNI syntax and exec/ioctl probe compilation | Passed |
| ksud cargo ndk arm64 check, clippy, cargo fmt --check | Passed, Android API 26 |
| ksud arm64 release build / Manager jniLibs prerequisite | Passed |
| Manager debug Kotlin and Android instrumentation runner compilation | Passed |
| Manager clean assembleRelease, JNI arm64/x86_64/riscv64 | Passed |
| Release DEX authorization JNI field names/types and packaged arm64 ksud | Passed |
| Actual device integration, Android GKI module build, reboot/time/identity/UI/module matrix | Not run |

Build tools were isolated in ignored `target/` directories and WSL. The local
Android Studio JDK was found, but its Gradle daemon also failed with a Windows
loopback connection error, so Manager validation used WSL JDK 21. No system
TLS validation was disabled, no device was flashed, and no app was installed.

The APK uses the configured upstream package/name and the build's debug-signing
fallback. It is a local validation artifact, with arm64 ksud included. A device
test needs Core configured to recognize that Manager signature plus matching
UAPI 6 components. Object compilation against desktop Linux headers is not an
Android GKI build or proof of runtime correctness. Device tests were unavailable
because there was no usable matching Android/Core/fixture environment.
