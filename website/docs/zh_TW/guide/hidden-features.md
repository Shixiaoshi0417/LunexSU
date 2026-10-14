# 隱藏功能 {#hidden-features}

> 本文档改编自 [KernelSU](https://github.com/tiann/KernelSU) 上游指南。LunexSU 的包名、授权有效期与品牌迁移说明见[项目 README](https://github.com/Shixiaoshi0417/LunexSU#readme)。

## .ksurc

預設狀況下，`/system/bin/sh` 會載入 `/system/etc/mkshrc`。

可以透過建立 `/data/adb/ksu/.ksurc` 檔案來讓 `su` 載入此檔案而非 `/system/etc/mkshrc`。