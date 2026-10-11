# LunexSU 品牌与包名迁移 / Brand and package migration

## 实施计划

1. 已完成：同步迁移 Manager 应用 ID、源码命名空间、JNI、AIDL、Manifest 和测试。
   正式包名为 `com.shixiaoshi0417.lunexsu`，PR 包名增加 `.pr`。
2. 已完成：对齐 Core、ksud、CI 的 Manager 识别和启动路径；更换项目入口、
   更新检测、日志、安装反馈、公开产物和网站品牌。
3. 已完成：保留上游版权和 FolkSU UI 来源，保留模块 / 内核兼容契约，
   并扫描剩余旧名称，逐项确认用途。
4. 已完成可用验证：Android NDK Rust 检查、Clippy、格式化、Manager 干净构建、
   网站构建、跨组件识别回归和模拟器测试；完整 Android LKM 与真机验证待匹配环境。

## 迁移边界

新包名是独立安装的 Android App，不能覆盖原 `me.weishu.kernelsu` 的安装。
Android 私有设置不会自动迁移。Core 与 Manager 的包名和签名配置必须匹配。
本次不修改签名校验强度，不自动授予新 Manager Root，不合并 main。

联合 Manager 构建现在从实际 APK 签名证书生成 Core 的证书长度与 SHA256。
PR 构建使用临时 PR 证书，正式构建统一使用 `KEYSTORE`、
`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` Secrets，缺失时构建失败。
后续按用户要求创建固定发布签名并配置到 Actions，见 [APK 签名](apk-signing.md)。
手工构建 Core 时仍须显式提供与 APK 匹配的
`KSU_EXPECTED_SIZE` / `KSU_EXPECTED_HASH`；独立 Kbuild 的上游签名默认值
不会自动变成 LunexSU 的生产证书。

保留 `KSU_*` / `CONFIG_KSU`、内核 IOCTL、`[ksu_driver]`、`su`、
`ksud` / `ksuinit` 的运行时文件名、`/data/adb/ksu`、模块环境变量、
WebUI 的 `ksu` 桥接对象和 `ksu://` 协议，以及启动镜像内部 `kernelsu.ko`。
这些名称是与现有内核、模块、启动恢复和授权持久化相关的兼容接口。
公开下载名称和产品界面使用 LunexSU。

README 保持中文默认、英文独立切换。上游文档、版权、历史 issue 链接和
FolkSU / MIUIX 来源不冒充 LunexSU 的原创内容或服务。

## English

The Android application and source namespace migrate to
`com.shixiaoshi0417.lunexsu`, with `.pr` for PR builds. Core recognition,
daemon launch, JNI, AIDL, manifests and tests must use the same identity.
The new package installs independently and does not import another app's
private settings. Matching Core package/signature configuration is required.
Combined Manager CI derives Core certificate metadata from the actual PR or
production signing certificate and fails if production credentials are missing.
Manual Core builds still require matching `KSU_EXPECTED_SIZE` and
`KSU_EXPECTED_HASH`; standalone upstream signer defaults are not a LunexSU key.

Product names, project/update links, logs, public release filenames and the
documentation site use LunexSU. Upstream copyrights, UI attribution and
compatibility contracts remain intact. No signature check is weakened and
no main branch is merged.

## 验证记录 / Validation

- GitHub 仓库描述已实际更新并读回确认，注明本次 / 限时 / 永久授权、
  Material / MIUIX 双 UI 和 FolkSU 主题导入。
- Android arm64：依次完成 `cargo ndk check`、`cargo ndk clippy`、
  `cargo fmt` / `--check` 和 ksud Release 构建，并将新二进制放入 Manager。
- 9 项跨组件品牌 / 包名 / 签名回归、54 项新命名空间 JVM 测试、
  2 项 JavaScript 桥接兼容测试通过。
- 网站依赖检查和完整 VitePress 构建通过；保留原有 `yarn.lock`，
  不新增竞争锁文件。安装时部分可选下载存在本地证书链警告，构建成功。
- Manager 正式包干净 Release / Debug / AndroidTest 构建成功。
  APK 的应用 ID、启动 Activity、标题及三种 ABI 的 `liblunexsu.so`
  均核对为新身份；Release 不包含授权测试宿主或旧 `libkernelsu.so`。
- PR 包 Debug / AndroidTest 构建成功，应用 ID 为
  `com.shixiaoshi0417.lunexsu.pr`，与正式包独立安装。
- 新正式包和 PR 包分别在 Android Studio 模拟器通过 18 项授权 UI
  保存 / 读取测试、13 项主题与字体测试；不触发 Root 授权。
  JNI 与 Magica 的 Zygote 预加载均使用 `lunexsu` 原生库名。
- 原有授权状态 / 持久化 / ABI 测试继续通过，包含 128 万次并发尝试
  和 10 类持久化 I/O 故障。内核 clang-format 检查通过。

早期 Core 相关 6 个对象在可用 Linux 7.0 头文件下编译通过；追加检查
`core/init.o` 时，本地通用 x86 头文件缺少项目要求的间接 syscall 补丁
（`X86_FEATURE_INDIRECT_SAFE`），被原有编译期安全检查阻挡。此次没有
删除或绕过该检查。后续为修复 KMI 空列表，使用匹配 Android DDK 完整
构建 arm64 Android 12 / 5.10 LKM 并通过符号检查，详见
[完整安装产物验证](kmi-confirm-fix.md)。

真机必须配套按新包名和匹配签名构建的 LKM / Manager，才能验证 Manager
识别、Magica 加载、重启恢复及 Root 启动。单独换 APK 不能让旧 Core 识别
新 Manager。未部署网站、发布 npm 包或合并 main。

The GitHub description was updated and verified. Android arm64 Rust check,
Clippy, formatting and release build passed. Cross-component identity,
54 JVM tests, two JS bridge tests, the website build and regular Manager
build passed. Both regular and PR packages passed 18 authorization UI cases
and 13 theme/font cases on the Core-less Android Studio emulator. JNI and
the Magica Zygote preloader use the renamed `lunexsu` native library.

Six Core objects compiled against the available Linux 7.0 headers. The
additional init-object check was blocked by missing required x86 indirect
syscall patches; the existing safety guard remains intact. Matching Android
Core/package/signature LKM was subsequently built successfully for arm64
android12-5.10; see the KMI fix report. Real-device recognition, Magica and
Root behavior still require validation. No website/npm deployment or main merge
was performed.
