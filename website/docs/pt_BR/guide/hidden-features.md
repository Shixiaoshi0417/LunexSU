# Recursos ocultos

> Adapted from the upstream [KernelSU](https://github.com/tiann/KernelSU) guides. See the [LunexSU README](https://github.com/Shixiaoshi0417/LunexSU#readme) for package identity, authorization expiry and migration details.

## .ksurc

Por padrão, `/system/bin/sh` carrega `/system/etc/mkshrc`.

Você pode fazer su carregar um arquivo rc personalizado criando um arquivo `/data/adb/ksu/.ksurc`.
