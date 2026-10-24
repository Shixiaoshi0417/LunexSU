# Managed Sui payload host checks

This standalone crate compiles the actual manifest validator and, on Unix, the
managed staging code and ordinary-module transaction implementation. It does not
run `ksud`, Android, embedded Sui executables or device commands. Child-process
cases use only local host helpers (`/bin/sh`, synthetic output and `sleep`) in
disposable directories, never an Android root shell or installed boot helper.

```sh
cargo test --locked --manifest-path userspace/ksud/tests/sui_safety/Cargo.toml
```

Metadata is validated before member names reach a reader. Every expected native,
DEX, policy and license member must be present, have its fixed mode and match its
digest. Unsupported architectures, extra/duplicate paths and malformed metadata
fail closed. A valid payload is not evidence of installation or server health.

To additionally validate locally built ARM64 and x86_64 payload bytes, set
`LUNEXSU_SUI_PAYLOADS` to the daemon's `bin` directory and pass
`-- --include-ignored` to `cargo test`. This only reads files; it never executes
the payload. The optional real-file check is explicitly ignored by the default
suite, rather than silently counting absent build inputs as a passed check.
Synthetic boundary cases always run.

Unix publication cases require a native filesystem with `RENAME_EXCHANGE` (not a
Windows-mounted WSL drive). They use disposable module directories, verify a
disabled-only publication and ABI layout, reject external directories/symlinks,
and retain the preceding pending candidate when metadata publication fails. The
vendor ADB-root policy is retained as provenance, not installed as runtime policy.
No boot scripts or desired-enabled state are written. These are staging tests,
not evidence of server, injection, Binder, SELinux or permission-host health.

Identity/state cases cover the Core-bound Manager predicate, primary-user UID
and package parsing, previous-UID retention, reboot-only activation intent and
default-off recovery. Helper cases exercise stdout/stderr limits, nonzero exit,
timeouts, spawn failure and temporary-file cleanup. These execute the same pure
helpers used by the daemon; Android-only supervision and real Binder behavior
remain separate device acceptance requirements.
