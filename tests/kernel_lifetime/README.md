# Kernel lifetime and filename-transition regressions

Run `python3 tests/kernel_lifetime/run_tests.py` with a host C compiler and its
AddressSanitizer/UndefinedBehaviorSanitizer runtimes. `CC` selects the compiler;
temporary output defaults to `/tmp/opencode` and respects `TMPDIR`.

The runner extracts production functions and compiles them against counted
objects and deterministic kernel-primitive mocks. Coverage includes:

- Heap-owned default credential groups, allocation failure, and invalid groups.
- Wrapper reopen reference balance, installation allocation failures, and an
  O_PATH-style path surviving the last ordinary wrapper file. Extracted wrapper
  functions are host-compiled through the 5.10, 6.1, 6.6, and 6.12 conditionals.
- All three task_work callbacks: queued/immediate completion, enqueue failure,
  module-going refusal, and the exiting-task branches.
- Each of arm64/x86_64/riscv64: invalid dispatcher calls, original fallback,
  sleeping handler versus teardown, fetched-pointer and return windows, and
  abandoned nested guards on fatal task exit. Both sched_process_exit callback
  signatures are compiled. The tests also check publication/teardown wiring.
- Filename transition shared bitmaps, source migration in both chain directions,
  count/idempotence, target bitmap, and all allocation-failure rollback points.
- Init.rc proxy ownership of both the original fops owner and LunexSU; failed
  owner acquisition, immutable publication under concurrent first reads, copied
  read/read_iter/ioctl calls, absent original owner/release, and sleeping release
  plus the return window before VFS drops the proxy owner.
- Input-hook work initialization before publication, early work during probe
  registration, registration failure, concurrent one-shot requests, queued work
  cancellation, running unregister/worker return, enqueue versus exit, and late
  requests after the admission gate closes. These use actual production
  init/worker/exit functions with deterministic pthread interleavings.

These are host tests, **not** architecture cross-builds or kernel runtime tests.
They do not validate real SRCU/Tasks RCU, scheduler ordering, instruction patching,
VFS behavior, or hardware memory ordering. Use matching target kernels for those.

Normal syscall unload requires Tasks RCU and the sched_process_exit tracepoint;
initialization fails if lifecycle protection is unavailable. The x86 global
dynamic syscall-entry compatibility mode deliberately pins the LKM until reboot:
arbitrary sleeping syscalls (including delete_module) can retain its C frames.
Unloadable x86 LKMs require the kernel's indirect syscall bypass patch instead.
Pending task_work, live wrapper dentries, or an open init.rc proxy file also keep
normal unload busy. Input-hook work is synchronously canceled after its sources
are stopped and before module state is freed. Forced
module unload is outside the supported lifecycle contract.
