# Security lifecycle host tests (F02, F09, F10)

These tests compile the exact platform-independent transaction helpers used by
ksud. All Android state and bootctl processes are fakes; read-back uses memory.
They never run ADB, root, Android property commands, boot actions or partitions.

Run with an existing host Rust toolchain, writing the executable into a dedicated
D-drive cache directory, not the source tree:

```sh
rustc --edition=2024 --test tests/security_lifecycle/host.rs -o /mnt/d/WorkSpace/LunexSU-audit-cache/security-lifecycle-host-tests
/mnt/d/WorkSpace/LunexSU-audit-cache/security-lifecycle-host-tests
```

Coverage: original values/absent versus empty properties and permissions; every
snapshot/partial-enable error; connect/dispatch/child failure; aggregate cleanup
errors and unwind fallback; missing/nonzero shell status and argument quoting;
both A/B slots, malformed/inconsistent slot output, separate bootctl argv and
each spawn/status failure; restore-before-cleanup and verified read-back.

OTA activation now prepares the completion hook before selecting the new slot.
Preparation errors must never activate it; an activation error restores the old
slot before removing the hook. An ambiguous rollback retains the recovery hook.
The module-safety host fixture additionally exercises the real hook filesystem
implementation, durability failures and abrupt process exits using fake bootctl
responses only. These checks do not establish physical power-loss or bootloader
behavior on a device.

Magica explicitly requires `--magica-allow-network`: connecting to localhost
does not prove that vendor adbd binds only localhost. Normal exits restore the
initiator's snapshot independently of the child. SIGKILL, abort, power loss, or
permissions denied by the platform cannot be made rollback-safe by an in-process
guard; restoration failures are reported. A private atomic completion report
allows the initiating process to observe daemonized late-load failures and await
completion before restoration. A missing report times out and restores state.

The current Manager JNI launcher does not pass this acknowledgement, so its
Magica path now refuses before making changes. A future explicit consent flow or
a demonstrably loopback-only backend is needed; do not silently add the flag.

Magica also fails closed before mutation when the appcompat override property
area exists: the pinned resetprop dependency dual-writes that area but does not
expose its independent original values for an exact per-property snapshot.
