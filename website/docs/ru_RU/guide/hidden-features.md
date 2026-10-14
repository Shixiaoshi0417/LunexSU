# Скрытые возможности

> Adapted from the upstream [KernelSU](https://github.com/tiann/KernelSU) guides. See the [LunexSU README](https://github.com/Shixiaoshi0417/LunexSU#readme) for package identity, authorization expiry and migration details.

## .ksurc

По умолчанию `/system/bin/sh` загружает `/system/etc/mkshrc`.

Вы можете заставить su загружать пользовательский rc-файл, создав файл `/data/adb/ksu/.ksurc`.