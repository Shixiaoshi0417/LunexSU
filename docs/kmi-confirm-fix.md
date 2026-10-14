# KMI 确认按钮与完整安装产物 / KMI confirmation and complete install artifacts

## 中文

根因是之前交付的本地签名验证 APK 缺少 arm64 内嵌 LKM，且安装资产中
没有静态 `ksuinit`。`ksud boot-info supported-kmis` 从 RustEmbed 的
`*_kernelsu.ko` 文件名生成列表；识别到设备 `android12-5.10` 不代表
对应模块已内嵌。Material / MIUIX 只允许确认实际在列表中的 KMI，
所以截图中的列表为空，确认禁用。旧 UI 没有解释空列表原因。

修复包括：

- `KmiDialogState` 与共享加载路径区分加载、读取失败、空列表和可用模块。
  两种 UI 展示对应提示，保持真实模块检查，并重新打开时重新读取。
- `KsuCli.getSupportedKmis()` 检查 daemon 返回码，防止命令失败被当作
  “没有模块”；保留取消和使用本地匹配 LKM 的路径。
- `ksud/build.rs` 跟踪 `bin` 目录变化，避免资源更新没有触发嵌入重编译。
- Actions 在 daemon 编译前检查 LKM ELF 架构和静态 `ksuinit`，重打包
  后检查最终 daemon 的必需模块名称。签名密码只走子进程环境。

本次用现有 `ghcr.io/ylarod/ddk-min:android12-5.10-20260828` 构建完整
arm64 LKM，符号检查通过。Git 提交计数为 2682，Core 版本为 32682，
UAPI 保持 6。包名为 `com.shixiaoshi0417.lunexsu`，证书长度 `0x0524`、
SHA256 与已配置的发布密钥匹配，未采用版本 16 的元数据回退。

静态 arm64 `ksuinit` 和 LKM 都已嵌入新 ksud。最终 APK 是针对本次
设备 KMI 的 arm64 构建，仅内嵌 `android12-5.10`；没有声称支持完整
Actions KMI 矩阵。旧下载链接对应的本地文件也替换为这份完整产物。

验证结果：

- Manager Release / Debug / AndroidTest 构建与 57 项 JVM 测试通过。
- 模拟器实际 Material / MIUIX 对话框共 10 项加载、空列表、错误、
  自动匹配和手动选择测试通过；没有刷写分区。
- Android arm64 Rust check、clippy、fmt 和 release 构建通过。
- 9 项跨组件 / 签名回归、3 项缺失模块拒绝回归和 4 项 ELF / 静态
  init 检查回归通过；修改的工作流 YAML 解析通过。
- 使用 RustEmbed 相同版本的 Deflate 实现确认最终 APK daemon 包含
  两个完整压缩载荷；解压后逐字匹配实际构建的 LKM 与 `ksuinit`。
- 最终 APK 发布证书及 16KB zipalign 校验通过。

最终产物：`dist/LunexSU-32682-arm64-android12-5.10.apk`（工作区根目录）。
SHA256：

```text
40eb68c49b821e10b36ace8e07cc0661133706254cc1101cbe8aa237627ea426
```

需要用户继续在真机验证实际修补、安装后的开机和 Root 授权保存。
可用模拟器没有运行这个 arm64 Core，不能代替真机加载验证。
未合并 main；Actions 工作流改动仍在本地分支。

## English

The previous locally signed validation APK omitted its arm64 embedded LKM
and static `ksuinit`. Detecting the device KMI does not populate the daemon's
embedded-module inventory. Both dialogs correctly refused confirmation,
but failed to explain the empty list.

The shared state now distinguishes loading, errors, missing modules and
available KMIs. Daemon failures are checked, resource changes trigger ksud
rebuilds, and release checks reject missing boot assets or incorrect ELF
architectures. Signing passwords use child-process environment variables.

A complete arm64 android12-5.10 LKM was built with the existing DDK image
and passed its symbol check. Core version 32682 comes from the actual Git
count, with UAPI 6 and the correct LunexSU package/signing identity. Static
`ksuinit` and the LKM are embedded in the newly built daemon. The delivered
arm64 APK includes this KMI, not the complete CI KMI matrix.

Manager builds, 57 JVM tests, 10 real Material/MIUIX dialog cases, required
Android Rust checks, 9 identity/signature regressions, 3 missing-module
regressions and 4 ELF/static-init regressions passed. The final APK contains
the exact compressed module and init payloads; decompression matches the
built files byte for byte. Its release signature and 16KB alignment pass.

Real-device patching, boot and Root authorization persistence still need
verification. The emulator does not run this arm64 Core. No main merge or
hosted workflow execution was performed.
