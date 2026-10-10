# Authorization tests

Host: `python3 tests/authorization/run_host_tests.py` (Linux GCC + pthread).
It compiles the real policy header, not a Python/Rust reimplementation.
It also compiles the production persistence transaction driver and exercises
real files with partial/later-record write, fsync, rename and cleanup failures,
interrupted transactions, stale staging hard links and safe retry. Pending
transactions are rejected on reboot rather than restoring rejected candidates
or a stale Permanent snapshot. KEEP validation includes new/denied entries.

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
process dies and the next request still has its credit; `ioctl` verifies Once
cannot be obtained through GRANT_ROOT. Skipped probe tests are reported.

Additional required release/device cases (manual to avoid destructive harness
operations on real devices):

- Grant unused Once / active Timed, hard reboot, reopen the page: original mode
  remains inactive, and new su is denied. Permanent survives. Also unload/reload
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
  until its existing deadline/credit is exhausted if global pruning does not
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
  must refuse saved grants while `.allowlist.pending` remains. After a successful
  retry, verify the marker is removed and only committed policy is restored.
  Guard cleanup errors after durable replacement must not report a rejected
  grant; a retained marker may conservatively deny saved grants after reboot.
- Check both Material and MIUIX modes, presets/custom duration validation,
  countdown after background/resume, renewal/revoke, and editing Root templates
  without resetting remaining time. Verify module + Metamodule regression flows.
- Load legacy v2/v3/v4 allowlists; validate Permanent migration. Inject truncated
  v5 record/unknown mode/future version: no temporary profile becomes permanent.

The harness never flashes a kernel, installs a fixture, reboots, changes the
wall clock, or uninstalls apps automatically. Those operations need a dedicated
test device. Host tests alone are not sufficient for a release claim.

Multi-user CLI syntax follows AOSP's
[run-as](https://github.com/aosp-mirror/platform_system_core/blob/master/run-as/run-as.cpp)
and [am instrument](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/cmds/am/src/com/android/commands/am/Am.java).
