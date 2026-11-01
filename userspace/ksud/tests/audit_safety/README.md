# ksud audit regressions

Host-only tests compile the production boot patch, feature persistence, su argument,
sepolicy serialization, restart sequencing, BTF and file-lock code. Android kernel
and daemon entrypoints are unreachable stubs. Every runtime file is in a temporary
directory; no device, root, boot partition or real module installation is used.

```sh
cargo test --locked --manifest-path tests/audit_safety/Cargo.toml --target-dir /tmp/opencode/ksud-audit-tests
KSUD_MODULE_FIXTURE_ROOT=/tmp/opencode/ksud-installer-tests \
KSUD_TEST_SHELL="$PWD/bin/x86_64/busybox sh" ASH_STANDALONE=1 \
python3 tests/module_safety/test_installer.py
```

The host needs libclang for generating SELinux command constants from the actual
UAPI header. Set `LIBCLANG_PATH` if libclang is outside the standard search path.

Covered: ramdisk debug-file restoration (including original metadata and repeated
patching), rejection of untracked legacy debug files without stock backup, explicit
input KMI precedence, GKI version boundaries, legacy installer errexit and REPLACE
failure propagation, separate su option values, genfscon paths/MLS contexts through
binary serialization, feature atomic replacement/publication failure, restart after
post-fs-data failure, and current BTF ABI validation. Refresh-lock placement,
pending-module uninstall cleanup and the late-load safe-mode gate additionally
require source review and the Android compile/lint checks; host tests do not emulate
an Android boot lifecycle.
