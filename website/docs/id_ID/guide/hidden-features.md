# Fitur tersembunyi

> Adapted from the upstream [KernelSU](https://github.com/tiann/KernelSU) guides. See the [LunexSU README](https://github.com/Shixiaoshi0417/LunexSU#readme) for package identity, authorization expiry and migration details.

## .ksurc

Secara bawaan, `/system/bin/sh` akan memuat `/system/etc/mkshrc`.

Anda dapat membuat `su` memuat berkas rc khusus dengan membuat berkas `/data/adb/ksu/.ksurc`.
