# Hook lifecycle regressions (F03/F04)

Run on a POSIX host (including WSL), with Python 3 and a C compiler:

```sh
python3 tests/hooks/run_hook_tests.py
```

`CC` selects the host compiler. The runner compiles the production LSM source
and selected production SELinux lifecycle/open functions against deterministic
kernel mocks. It exercises both the pre-6.12 hlist and 6.12+ static-call branches.
It does not build or execute a kernel, access a device, or load a module.

Covered guarantees:

- Original handler and entry/static-call metadata precede replacement publication;
  a callback invoked inside the mock publication operation has a valid original.
- Empty target slots and overlapping tracked replacements are rejected.
- Failed installation restores the previous slot and descriptor, including when
  the failed patch was visible. Already-restored slots need no additional patch.
- Original metadata remains valid through a pre-entry Tasks grace period, the
  sleepable callback drain, and a final Tasks grace period for callback returns.
- Default-off exit, repeated disable/exit, and partial installation restore actual
  installed hooks, rather than depending on the requested/running feature value.
- Fake-status files acquire module-owned operations; acquisition during module
  removal falls back to the original. Existing fake files retain their pin across
  disable, keeping their page valid until file/mapping references are released.

Safety tradeoffs and target validation still required:

- Runtime hooks are refused when `synchronize_rcu_tasks` cannot be resolved. There
  is no unsafe fallback to ordinary RCU for sleepable callbacks.
- Restoration verifies the actual slot. If it cannot restore a live replacement,
  it retries rather than allowing module text/data to be freed. A permanent
  hardware/vendor protection error can therefore block disable/unload.
- Normal unload requires fake-status descriptors and mappings to be closed;
  forced unload is unsupported.
- Tasks grace periods assume these process-context call sites do not voluntarily
  schedule between fetching a callback pointer and entering it. Current VFS open,
  SELinux write dispatch, and setprocattr dispatch satisfy this assumption.
- Mocks do not establish hardware memory ordering, stop-machine patch atomicity,
  static-call instruction synchronization, SRCU/Tasks RCU implementation behavior,
  kernel configuration/symbol availability, or vendor backport compatibility.
  Validate those on supported real kernels, including concurrent enable/disable,
  open/read/mmap/close and setprocattr traffic, normal unload after closing pins,
  and fault injection with KASAN/KCSAN/lockdep where available.
