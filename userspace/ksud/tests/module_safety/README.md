# Local module transaction tests

Run the file/mock-only Rust harness on a Linux host. The checkout/build cache may
stay on D:, but the publication death tests require **native POSIX fixtures**
(not WSL DrvFS, which rejects RENAME_EXCHANGE):

```sh
KSUD_MODULE_FIXTURE_ROOT="$HOME/.cache/lunexsu-audit/f07-publication" \
  cargo test --offline --manifest-path userspace/ksud/tests/module_safety/Cargo.toml \
  --lib -- --test-threads=1
```

The standalone Rust harness compiles the production config and filesystem
transaction helpers, without ksud's Android build script/assets. Transaction
fixtures use `KSUD_MODULE_FIXTURE_ROOT` (otherwise ignored `fixtures/`); config
fixtures use ignored `fixtures/configs/`. It never accesses `/data/adb`, a device,
root, mounts or real modules. No real installer or module script is invoked by
the Rust harness; OTA hook checks use only a disposable fake bootctl shell script.
The separate Python installer-stub harness was also run for
the boot-promotion durability follow-up.

## Publication protocol and recovery

- Before the atomic publication rename/exchange: sync the new payload, write a
  synced started marker and versioned journal containing new/previous directory
  identities, and sync their directory entries.
- A private publication token keeps the candidate out of both update enumeration
  and next-boot/preinit selection until durable commit and successful recovery.
- Before live bookkeeping edits: sync backups of module.prop/update/disable/remove,
  including absence and modes, plus a manifest binding them to the live directory.
- `Publication::commit` syncs bookkeeping and writes/syncs a transaction-bound
  commit record. **Hot install is attempted only after this durable commit and
  finalization**, never while it could consume an uncommitted recovery candidate.
- `Publication::commit_then` publishes the global metamodule alias only after
  commit succeeds. Commit write/sync failures cannot change a prior alias or
  expose a first-install empty live directory. If alias creation fails after
  commit, the committed module.prop remains available to normal discovery.
- Without commit: identity-checked recovery restores live bookkeeping and exchanges
  back the previous pending directory (or withdraws the candidate if none existed).
  With commit: it retains the new candidate and discards only the recorded,
  superseded pending directory. Working live payloads are never replaced by this
  recovery path.
- Only a synced `gc-install-v2-*` directory rename authorizes recursive cleanup.
  Install staging uses an explicitly managed PathBuf, not TempDir Drop. Failed
  rollback, invalid/missing journals, unexpected identities and legacy unjournaled
  install directories retain their recovery material and fail closed for inspection.

The native POSIX tests SIGKILL separate processes (no unwinding) after durable
journal creation, immediately after the publication swap, after live metadata
edits, after the durable commit, after rollback but before directory sync/cleanup,
and after the synced garbage classification. Every boundary is tested with and
without a previous pending update, followed by recovery twice. They check original
directory inodes, payload contents, metadata modes/flags and promotion exclusion.
Additional cases inject rollback failure through both guard/install Drop, replace
the candidate with an unrelated directory, corrupt/remove journals or commit
records, and retain legacy backups.

Checkpoint signals are atomically published after their contents are complete,
so a parent observing the signal cannot read a partially written checkpoint name.

## Boot-promotion durability and recovery

- Promotion journals use an explicitly managed PathBuf. Failed syncs cannot
  trigger unconditional TempDir cleanup of an old backup or the journal.
- Once the new payload is visible, promotion still reports success. Recovery
  confirms both the journal and live parent before discarding the old payload.
- A failed exchange restores the pending update, then syncs both rename parents.
  Recovery repeats those syncs even when a previous rollback already moved the
  payload out of the journal; another sync failure retains the journal again.
- Recursive cleanup requires a synced `gc-promote-*` rename. Recovery re-syncs
  garbage classification before deleting, including after partial cleanup has
  removed the module ID. A leftover live token after interrupted garbage cleanup
  is harmless because its uniquely named promotion journal is no longer active.
- Missing/invalid journal records, unreadable live tokens and unidentified staged
  payloads fail closed. Only an entirely empty journal is disposable without an ID.

Thread-local directory-sync fault injection exercises both parents after promotion
and rollback, with and without an old live payload. Each case verifies directory
identities, contents and retained journals through two failing recovery retries,
then successful recovery twice. Additional cases cover garbage-root sync failures,
partial garbage deletion, missing/corrupt records and tokens, and the existing
exchange-failure rollback path. The production source is compiled directly by this
harness; sync faults are test-only and do not affect other test threads.

Coverage: failed extraction/customization/legacy callbacks, explicit nonzero
exit and abort, successful private hot-install requests, killed installer
recovery, atomic update publication, failed boot exchange rollback, interrupted
boot promotion recovery, disable/remove propagation, two cross-process config
writers with lock-free readers, killed lock-owner recovery, private temporary
files and permissions/validation preservation.

These do not validate physical power loss/storage fault durability, Android
SELinux labeling, Android filesystem/kernel support, real metamodules/hot installs,
preinit on device, or Android-target compilation. Recovery intentionally retains
rather than guesses if stored inode/device identities no longer match (including
a filesystem replacement/device-number change). The parent coordinates Android
NDK check/clippy/fmt separately.

The live snapshot covers the four per-module bookkeeping files listed above, not
the metamodule's global symlink. Metamodule cases inject metadata/commit sync and
commit-record write failures with and without a prior alias, followed by recovery
twice. Separate processes are killed before commit, after durable commit but
before alias creation, and after alias creation; no active alias may target an
uncommitted empty directory. The existing hot_install.rs stash/swap lifecycle is
unchanged and is not crash-tested here; it cannot run before durable publication
commit/finalization.

WSL's D: DrvFS returns EINVAL for RENAME_EXCHANGE. Older success assertions report
that limitation there; the new process-death tests instead require a native POSIX
fixture filesystem and assert exchange support, so they cannot silently pass by
skipping the previous-pending cases.
