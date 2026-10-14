# Hidden features

> Adapted from the upstream [KernelSU](https://github.com/tiann/KernelSU) guides. See the [LunexSU README](https://github.com/Shixiaoshi0417/LunexSU#readme) for package identity, authorization expiry and migration details.

## .ksurc

By default, `/system/bin/sh` loads `/system/etc/mkshrc`.

You can make su load customized rc file by creating a `/data/adb/ksu/.ksurc` file.
