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
`kernel/policy/authorization_persistence.h`,
`kernel/policy/app_profile.{c,h}`, `kernel/feature/sucompat.c`,
`kernel/supercall/dispatch.c`, Manager JNI/Natives and AppProfile screens,
`kernel/hook/{setuid_hook,tp_marker}.c`,
localized strings, and `tests/authorization`. Implementation is on
`codex/root-authorization-expiry`, not main.

## Contract

- Permanent preserves existing pre-authorization. Manager and already Root
  processes in the KSU domain retain upstream trusted behavior. Temporary
  grants are offered only to installed App UIDs, not the always-trusted Manager.
- Once now means **one App run**, not one `su` exec. Preauthorization while the
  App is stopped binds exactly its next run; authorization while running binds
  the current run. All ordinary tasks of the full Android UID (including threads
  and shared-UID packages) are tracked through specialization, fork and exit.
  Repeated and concurrent `su` calls remain allowed during that run. The last
  ordinary App task exiting ends admission immediately, without Manager polling;
  a new run has a different epoch and cannot inherit an old grant. Swiping away
  a recent task does not necessarily kill background services, so a surviving
  ordinary App process keeps the run active.
  Root tasks admitted through su leave the ordinary-task count before returning
  to userspace; they and their descendants do not extend the App run, but are not
  killed when the App exits. Missing lifecycle hooks or tracking allocation failure
  fail closed for Once (Permanent and Timed remain available).
  GRANT_ROOT still cannot use Once: it is not an exec into su. A failed temporary
  exec is SIGKILLed before it can return
  to userspace with changed credentials/seccomp/namespaces. A refused escape
  (for example a process that disabled re-escalation) changes nothing, so the
  App only receives the error and is not killed. Successful Root
  processes and their descendants are not terminated or stripped of privileges.
- Timed permits repeated admissions strictly before the CLOCK_BOOTTIME
  deadline. Sleep counts. Changing wall-clock time cannot extend a grant.
  Renewal starts a new duration from the current boottime (not from the old
  deadline). UI presets are 5/15/60 minutes; custom values are 1-525600 minutes.
- Once is **App-run scoped** and additionally boot-scoped; Timed is boot-scoped.
  Reboot (or module unload/reload) ends both: the App restores as not authorized
  with the default non-root profile and needs a new grant. Soft Android framework
  restart within the same kernel does not reset boottime, but it does end every
  App run, so a Once grant then needs a new grant as well.
- KEEP edits cannot rearm an ended App run, renew, or change mode. Creating an authorized entry
  or changing `allow_su` from false to true requires explicit GRANT, including
  Permanent. Legacy SET_APP_PROFILE keeps its existing Permanent semantics.
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
Module umount, by contrast, follows the active grant: an App whose temporary
grant expired, was spent or did not survive a reboot is unmounted like an
ordinary App (default non-root policy), while an active grant keeps modules.

The v4 app_profile/UAPI layout is unchanged. New sized GET/SET_AUTH_PROFILE
ioctls carry policy plus the complete profile in one transaction (UAPI 6).
Legacy SET_APP_PROFILE preserves temporary state and cannot turn an expired
grant permanent. New Managers reject temporary grants on old kernels.
Core+Manager+ksud must be deployed together to satisfy existing UAPI checks.

The allowlist stays the upstream v4 format. A temporary grant is written as a
record without Root (default non-root profile), so no reader, including an
upstream KernelSU Core after a downgrade, can restore it as Permanent, and a
downgrade keeps every Permanent grant and profile. No mode, deadline, credit or
install handle is persisted. Formats 2-4 migrate as permanent. Format 5 files
from earlier LunexSU builds (mode and duration after each profile) are read the
same way and rewritten as v4; unknown modes, truncated records and future
versions are rejected.
Explicit policy writes are synchronous and durable **before** publishing the
new in-memory profile; all file writers hold the same mutex throughout the
transaction. A complete snapshot is written to a newly created `.allowlist.tmp`,
fsynced, atomically renamed to `.allowlist`, then its parent directory is fsynced.
An old staging pathname is unlinked before exclusive creation, never truncated.

Before any replacement, the transaction's UID is appended to
`.allowlist.pending`, which is fsynced with its directory. The loader never
loads staging files. A failed transaction leaves the marker intact, including if
rename succeeded but the directory fsync failed; the candidate is not published
in memory. Every snapshot is the committed in-memory policy plus one replacement,
so only the UIDs in the marker can differ from acknowledged state. On reboot the
loader therefore revokes Root from exactly those UIDs (their profile is reset to
the default non-root policy) and loads every other grant and profile unchanged,
then persists the result, which removes the marker. A marker that cannot be read
or lists more than 64 UIDs revokes Root from every record but keeps the
profiles. A rejected candidate or a stale Permanent record for the affected UID
can therefore never grant Root, and one interrupted save never erases the rest
of the allowlist.

Only after the replacement and directory are durable is the marker removed.
Marker cleanup errors are logged, not returned as a failed grant after commit;
the new policy is published, but a retained/reappearing marker revokes the listed
UIDs after reboot. This is an availability cost rather than unintended Root.

## Validation

Host tests compile the production state machine, including 64-thread repeated
run-scoped admission and App-run epoch transitions, exact expiry boundaries, renewal, reboot invalidation,
invalid durations/overflow and v4/new ioctl layout assertions:

```sh
python3 tests/authorization/run_host_tests.py
```

The same command also compiles the production persistence transaction driver
against real temporary files. Fault injection covers partial and later-record
writes, staging fsync, rename, directory fsync and marker cleanup, plus interrupted
transaction stages, stale staging hard links and safe retry. Rejected Permanent
candidates never load; interrupted changes from Permanent to Timed cannot restore
the old Permanent snapshot. KEEP validation tests cover denied/new and authorized
entries across all modes. Device tests exercise denied/new and revoked Permanent
KEEP writes through the actual Manager JNI path.

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

### Review fixes on 2026-10-11

The P1 rejected-candidate persistence finding is addressed by staged atomic
replacement and the durable pending marker. The P2 implicit Permanent grant
finding is addressed by requiring GRANT for new/false-to-true authorizations.
Host regression tests, Core object compilation, kernel formatting and Python
harness syntax passed. The new device regression cases still require a matching
device environment; compiling the instrumentation runner does not execute them.
