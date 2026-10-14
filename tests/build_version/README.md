# LKM build version regression tests

Run on Linux with Python 3, Git, GNU make, a C compiler, Bash and `strings`:

```sh
python3 tests/build_version/run_tests.py
```

The suite runs the production version rules from `kernel/Kbuild`, compiles their
version macro into a host object, and checks that object with the same artifact
gate used by the DDK workflow. It isolates Git configuration and commits in
temporary repositories; it does not change the user's Git configuration or
compile/load a kernel module.

Coverage includes full history, the original wrong fork `safe.directory`, the
correct workspace path, same-repository fallback, shallow or missing Git
metadata, missing artifacts, artifacts from a different revision, and large
`strings` output under `pipefail`.

The original Redmi K60 PR build displayed Core `16-6` while Manager displayed
`v3.3.0-79-g2e9122cc (32680-6)`. Core 16 was Kbuild's metadata fallback; 6 was
UAPI. The DDK workflow inherited `/__w/KernelSU/KernelSU` while this fork built in
`/__w/LunexSU/LunexSU`. The workflow now trusts `$GITHUB_WORKSPACE`, fetches full
history and verifies each stripped arm64/x86_64 module before uploading it.

The verifier requires the compiled welcome string to contain the expected
`30000 + git rev-list --count HEAD`, matching the existing Manager and ksud
calculation. It does not change the Core interface or its non-CI fallback.

For a real build, `build-lkm.yml` runs these tests before the DDK matrix. Each
artifact must pass `check-lkm-version.sh`. Verify that the loaded module reports
that build's Core version and unchanged UAPI 6 through `ksud debug info` on an
already authorized device terminal. A new Manager APK alone does not replace
the running old LKM; final device verification requires a matching installed
build and a separately authorized installation/reboot test.
