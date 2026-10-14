# 隠し機能

> Adapted from the upstream [KernelSU](https://github.com/tiann/KernelSU) guides. See the [LunexSU README](https://github.com/Shixiaoshi0417/LunexSU#readme) for package identity, authorization expiry and migration details.

## .ksurc

デフォルトでは `/system/bin/sh` は `/system/etc/mkshrc` を読み込みます。

`/data/adb/ksu/.ksurc` ファイルを作成することで、カスタマイズした rc ファイルを su に読み込ませられます。