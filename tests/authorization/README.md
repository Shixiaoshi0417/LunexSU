# Authorization tests

See [SAVE_FIX.md](SAVE_FIX.md) for the save-path repair, ABI checks, shared
Material/MIUIX regression suite, and its real-device validation boundary.

Host: `python3 tests/authorization/run_host_tests.py` (Linux GCC + pthread).
It compiles the real policy header, not a Python/Rust reimplementation.
It also compiles the production persistence transaction driver and exercises
real files with partial/later-record write, fsync, rename and cleanup failures,
interrupted transactions, stale staging hard links and safe retry. Reboot
revokes Root from the UIDs of unfinished transactions rather than restoring
rejected candidates or a stale Permanent snapshot, while an unrelated Permanent
grant survives a well-formed UID-scoped marker. A torn, unreadable or overflowing
marker instead revokes Root conservatively for every saved record. KEEP
validation includes new/denied entries.

Independent marker regressions: `python3 tests/authorization/run_persistence_tests.py`.
This uses ASan/UBSan, production pure helpers and extracted production guard I/O
functions. It checks 1..7-byte tails, aligned short reads, partial writes,
repeated transactions without losing earlier complete guard records, restart
parsing, later-stage failures and clean restore. Appending to a torn marker is
rejected without changing it; a full safe recovery snapshot may remove that
marker only after durable commit. No real authorization paths are accessed.

Device prerequisites:

1. Build/deploy this branch's Core, ksud and recognized Manager together.
2. Build `:app:assembleDebugAndroidTest` and install the test APK matching the
   recognized Manager's application ID/signature. The custom runner exists
   only in androidTest sources and uses the existing Manager JNI API.
3. Install a disposable debuggable fixture app in the tested Android user.
   Its data directory must exist/unlocked, and it must initially be denied.
4. Run `python3 tests/authorization/run_device_tests.py --fixture your.test.app
   --serial <device>`. The harness refuses existing grants and revokes on exit.
   Add `--user <id>` to test an existing secondary user with Manager, its test
   APK and fixture installed in that user. The returned full UID is checked.

For failure/ioctl coverage compile `exec_probe.c` with the NDK clang and `-I.`.
Copy it into a location executable by `run-as your.test.app` (the fixture's own
data directory is preferable); pass `--probe /data/user/0/your.test.app/exec_probe`.
`fault` forces exec argv-copy failure after Root profile application. Verify the
process restarts (a new App run needs a new grant); `ioctl` verifies Once
cannot be obtained through GRANT_ROOT. Skipped probe tests are reported.

Additional required release/device cases (manual to avoid destructive harness
operations on real devices):

- Grant Once while the App is stopped, start it, run several `su` commands, then stop
  every one of its processes: the grant works for the whole run and is reverted at the
  latest exit without any Manager polling.
- Grant unused Once (App never started) / active Timed, hard reboot, reopen the page: the App shows
  as not authorized, and new su is denied. Permanent survives. Also unload/reload
  the module if supported; framework soft reboot preserves kernel boottime.
- While Timed is active, move wall clock forward/backward and suspend; duration
  follows boottime. Restore the device's original time/sync settings afterward.
- Keep one granted su shell running across expiry/revocation. It remains usable,
  but a new su from the original fixture UID is denied.
- Uninstall the disposable fixture and reinstall its APK, including immediate
  same-UID reuse. Old temporary grants are denied. Directory/data reset likewise
  invalidates. An APK update retaining its directory does not invalidate.
  Also check `uninstall -k` and per-user uninstall while another user retains
  the package: retained UID/package/data inode can retain the original grant
  until its existing deadline/App run ends if global pruning does not
  remove the entry. No new duration or credit should appear.
- Use another Android user with the same package/appId: grants and quotas are
  separate. Remove that user: its directory identity fails closed. Shared UID
  apps share one quota; ordinary uninstall of the selected package anchor
  invalidates it through data removal or package pruning.
- Race save/revoke/renew with exec, use stale generation tokens, and inject
  allocation/storage/exec failures on a debug kernel. Updates return busy/stale
  errors rather than overwriting live state; revocation still blocks subsequent
  admissions while an exec is pending, with no duplicate consumption or revival.
  In particular, inject a later-record write failure after a complete Permanent
  candidate, staging fsync failure, rename failure and directory fsync failure
  after rename. The ioctl must fail without publishing the candidate; reboot
  must not restore Root for that UID while `.allowlist.pending` lists it, and
  other apps' grants must survive. After a successful retry, verify the marker is
  removed and only committed policy is restored.
  Guard cleanup errors after durable replacement must not report a rejected
  grant; a retained marker revokes only the listed UIDs after reboot.
- Check both Material and MIUIX modes, presets/custom duration validation,
  countdown after background/resume, renewal/revoke, and editing Root templates
  without resetting remaining time. Verify module + Metamodule regression flows.
- Load legacy v2/v3/v4 allowlists; validate Permanent migration. Load a v5 file
  from an earlier build: temporary records restore without Root and the file is
  rewritten as v4. Inject truncated v5 record/unknown mode/future version: no
  temporary profile becomes permanent. Flash an upstream KernelSU LKM: Permanent
  grants and profiles load, temporary ones stay revoked.

The harness never flashes a kernel, installs a fixture, reboots, changes the
wall clock, or uninstalls apps automatically. Those operations need a dedicated
test device. Host tests alone are not sufficient for a release claim.

Multi-user CLI syntax follows AOSP's
[run-as](https://github.com/aosp-mirror/platform_system_core/blob/master/run-as/run-as.cpp)
and [am instrument](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/cmds/am/src/com/android/commands/am/Am.java).
