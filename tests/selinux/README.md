# SELinux transaction host tests

Run `python3 tests/selinux/run_transaction_tests.py` with Linux GCC and ASan/UBSan.
Run `python3 tests/authorization/run_persistence_tests.py` for pending-marker
recovery. Set `TMPDIR` to a disposable directory on the desired drive; by default
these independent runners create and clean their output under the checkout.

The runner extracts the actual `add_type`, parser, batch handler and builtin-rule
functions from production sources. It does not keep a parallel implementation.
Kernel allocation, bitmap, policy operation and RCU primitives are mocked:

- Every allocation in type preparation, bitmap copying/growth and final symbol
  insertion is faulted. Failure must leave old pointers, symbol table, bitmaps and
  counts unchanged, leak nothing, and permit retry. Success transfers new
  key/type ownership to the symbol table, old attribute-bitmap nodes to the new
  array, and copied role-bitmap nodes to the roles.
- A command that mutates the private policy then fails, including the first,
  middle and last command, must destroy that copy without publication/cache
  reset or destruction of the live policy. Parser/copy/duplication failures have
  the same publication guarantee. Missing required builtin types also abort.
- A normal policy duplicate borrows the live sidtab; discard destroys its own
  policydb and wrapper only, not that borrowed sidtab. Backup sidtab ownership is
  unchanged. Optional platform-specific builtin rules remain best effort.

Host tests cannot validate actual kernel allocator behavior, SELinux bitmap and
symbol-table layouts across kernel versions, RCU readers, policy serialization,
SID translation or policy installation. No kernel build, device, root access,
ADB, real authorization files, CI, commit or push is involved in these tests.
