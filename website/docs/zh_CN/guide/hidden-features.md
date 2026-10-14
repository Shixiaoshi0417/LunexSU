# 隐藏功能

> 本文档改编自 [KernelSU](https://github.com/tiann/KernelSU) 上游指南。LunexSU 的包名、授权有效期与品牌迁移说明见[项目 README](https://github.com/Shixiaoshi0417/LunexSU#readme)。

## ksurc

默认情况下，`/system/bin/sh` 会加载 `/system/etc/mkshrc`。

可以通过创建 `/data/adb/ksu/.ksurc` 文件来让 su 加载该文件而不是 `/system/etc/mkshrc`。