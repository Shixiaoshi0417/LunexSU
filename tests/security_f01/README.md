# F01 host regressions

These tests do not invoke root, ksud, modules, ADB, or a device.

Run the isolated Manager checks without a Gradle build (using cached Kotlin,
coroutines, JSON and JUnit jars):

```powershell
python tests/security_f01/run_manager_host_tests.py --temp D:\WorkSpace\LunexSU-build-cache\tmp
```

The host runner compiles the production repository, JSON model and command
helper, plus the fresh production CLI method bodies and editor validator. Only
Android annotations, native metadata constants/enums, HTTP plumbing and the
shell receiver are stubbed. The extra host-only tests assert invalid CLI input
does not even acquire a shell, and each affected CLI method's exact argv. Fake
in-memory HTTP responses also cover remote-index validation before fetching,
index/template ID mismatch, valid remote storage, and stored-key/ID mismatch.

Manager unit tests:

- `ProfileCommandTest`: matching ID restrictions, NUL rejection, and a harmless
  POSIX-shell function that records the exact received argv.
- `TemplateImportSecurityTest`: rejects an invalid batch before any storage
  callback, preserves JSON data, and returns parse/validation/write errors.

`F01_TEST_SH` may point to Git for Windows' `bin/sh.exe` for the receiver test.
On Unix the test uses `/bin/sh`. Without a POSIX shell only that test is skipped.

Rust storage tests are embedded in the production `profile.rs` and can be run
independently of the Android daemon on a Unix host:

```sh
CARGO_TARGET_DIR=/path/to/local/temp/f01-target \
  cargo test --manifest-path tests/security_f01/profile_host/Cargo.toml
```

The harness links production storage with fail-fast policy stubs. It tests
invalid IDs before directory creation, exact content round trips, truncation,
deletion, directory/symlink/hardlink rejection, and trailing-slash directory
symlink rejection. It is not a substitute for the required Android `cargo ndk`
checks or Manager build, which must be coordinated with other audit agents.
