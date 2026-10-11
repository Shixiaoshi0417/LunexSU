# Difference with Magisk

> Adapted from the upstream [KernelSU](https://github.com/tiann/KernelSU) guides. See the [LunexSU README](https://github.com/Shixiaoshi0417/LunexSU#readme) for package identity, authorization expiry and migration details.

Although LunexSU and Magisk modules have many similarities, there are inevitably some differences due to their completely different implementation mechanisms. If you want your module to work on both Magisk and LunexSU, it's essential to understand these differences.

## Similarities

- Module file format: Both use the ZIP format to organize modules, and the module format is practically the same.
- Module installation directory: Both are located at `/data/adb/modules`.
- Systemless: Both support modifying `/system` in a systemless way through modules.
- post-fs-data.sh: Execution time and semantics are exactly the same.
- service.sh: Execution time and semantics are exactly the same.
- system.prop: Completely the same.
- sepolicy.rule: Completely the same.
- BusyBox: Scripts are run in BusyBox with "Standalone Mode" enabled in both cases.

## Differences

Before understanding the differences, it's important to know how to identify whether your module is running in LunexSU or Magisk. You can use the environment variable `KSU` to differentiate it in all places where you can run module scripts (`customize.sh`, `post-fs-data.sh`, `service.sh`). In LunexSU, this environment variable will be set to `true`.

Here are some differences:

- LunexSU modules cannot be installed in Recovery mode.
- LunexSU modules don't have built-in support for Zygisk, but you can use Zygisk modules through [ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext).
- **Module mounting architecture**: LunexSU uses a [metamodule system](metamodule.md) where mounting is delegated to pluggable metamodules (e.g., `meta-overlayfs`), while Magisk has mounting built into its core. LunexSU requires installing a metamodule to enable module mounting.
- The method for replacing or deleting files in LunexSU modules is completely different from Magisk. LunexSU doesn't support the `.replace` method. Instead, you need to create a same-named file with `mknod filename c 0 0` to delete the corresponding file.
- The directories for BusyBox are different. The built-in BusyBox in LunexSU is located at `/data/adb/ksu/bin/busybox`, while in Magisk it is at `/data/adb/magisk/busybox`. **Note that this is an internal behavior of LunexSU and may change in the future!**
- LunexSU doesn't support `.replace` files, but it supports the `REMOVE` and `REPLACE` variables to remove or replace files and folders.
- LunexSU adds the `boot-completed` stage to run scripts after the boot process is finished.
- LunexSU adds the `post-mount` stage to run scripts after module mounting is complete.
