# APK 发布签名 / APK release signing

## 中文

`Shixiaoshi0417/LunexSU` 使用固定发布签名，保存在以下
GitHub Actions Repository Secrets：

| Secret | 用途 |
| --- | --- |
| `KEYSTORE` | 发布 JKS 的 Base64 内容 |
| `KEYSTORE_PASSWORD` | JKS 密码 |
| `KEY_ALIAS` | 签名别名 `lunexsu` |
| `KEY_PASSWORD` | 私钥密码 |

当前证书（2026-10-11 轮换）使用 RSA 2048 / SHA256withRSA，有效期
10000 天，DER 长度 803 字节（`0x0323`）。公开证书 SHA256：

```text
907ee678290b157d477340dc498446e777f2aa9d3ee96689598f154b0175d284
```

### 证书长度限制

Core 的 `kernel/manager/apk_sign.c` 最多读取 1024 字节的签名证书
（`CERT_MAX_LENGTH`，与上游 KernelSU 一致）。最初的 RSA 4096 证书为
1316 字节：LKM 可以正常编译和加载，但证书长度检查永远失败，内核无法
识别 Manager，刷入后 Manager 拿不到 Root。因此发布密钥改为 RSA 2048。
现在 `build-manager.yml` 在证书超过 1024 字节时直接失败，`apk_sign.c`
也有编译期断言，防止再次出现能编译但无法识别的组合。

旧 RSA 4096 密钥（SHA256 `57238d4c…81f1`）不再用于发布，备份在
`target/signing/legacy-rsa4096/`。用旧密钥签名的 APK 不能覆盖安装新签名
的 APK，需要先卸载；Core 必须使用新证书参数重新构建。

私钥及密码备份位于本机工作区根目录的 `target/signing/`，只允许当前
Windows 用户及 SYSTEM 访问；该目录被 Git 忽略。备份不进入源码、
Actions 产物或日志。后续发布应继续复用这把密钥，并妥善离线备份。

`build-manager.yml` 的正式构建、重打包签名和 Core 证书提取统一使用这
四项 Secrets；PR 构建仍使用临时 RSA 2048 密钥及 `.pr` 包名。证书提取生成
`KSU_EXPECTED_SIZE` / `KSU_EXPECTED_HASH`，传给 LKM 构建，LKM 再由
Actions 内嵌进 ksud 与 APK。缺少配置时失败。重签名完成后，再用
`apksigner verify --print-certs` 校验每个 APK 的证书 SHA256 与 Core 参数
相同，然后上传产物。

`kernel/Kbuild` 的 `KSU_EXPECTED_SIZE` / `KSU_EXPECTED_HASH` 默认值就是
上面的发布证书，供 `setup.sh` 集成等不经 CI 的 Core 构建使用。正式构建会
比对这两个默认值与 Secrets 中的证书，不一致即失败；轮换密钥时必须同时
更新 Kbuild。

工作流支持手动 `workflow_dispatch`，可在分支代码推送至 GitHub 后
运行 Build Manager。运行它只构建和上传 Actions 产物，不自动合并 main
或发布 Release；需要发布时另用现有 Release 工作流。

## English

The release key is stored in four repository Actions secrets: `KEYSTORE`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` (alias `lunexsu`).
The current certificate (rotated 2026-10-11) is RSA 2048 / SHA256withRSA,
valid for 10000 days, 803 DER bytes (`0x0323`), SHA256 shown above.

Core's `apk_sign.c` reads at most 1024 certificate bytes (`CERT_MAX_LENGTH`,
same as upstream KernelSU). The original RSA 4096 certificate was 1316 bytes,
so the LKM built and loaded but could never recognize the Manager, which then
had no Root after flashing. The key was therefore rotated to RSA 2048.
`build-manager.yml` now fails for certificates over 1024 bytes and
`apk_sign.c` has a compile-time assertion for the same limit.

The retired RSA 4096 key (SHA256 `57238d4c…81f1`) is backed up under
`target/signing/legacy-rsa4096/` and is no longer used. APKs signed with it
cannot update the newly signed APK without uninstalling first, and Core must
be rebuilt with the new certificate parameters.

The private local backup is under the workspace root's ignored
`target/signing/` directory, restricted to the current Windows user and
SYSTEM. Keep an offline backup and reuse this key for subsequent releases.

Production APK signing and Core certificate extraction use the same secrets;
Actions builds the LKMs with the extracted size/hash and embeds them in ksud
and the APK. PR builds retain temporary RSA 2048 keys and the `.pr`
application ID. A post-signing gate verifies all APK certificate hashes
against Core before uploading artifacts. Build Manager can be run manually
with `workflow_dispatch`; it does not merge main or publish a Release.

The `KSU_EXPECTED_SIZE` / `KSU_EXPECTED_HASH` defaults in `kernel/Kbuild` are
this release certificate, for Core builds outside CI such as `setup.sh`
integrations. Production builds fail if those defaults differ from the
certificate in the secrets, so a key rotation must update Kbuild as well.
