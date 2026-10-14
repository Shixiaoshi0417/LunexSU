**简体中文** | [English](README_EN.md)

<p align="center">
  <img src="docs/assets/lunexsu-logo.png" width="160" alt="LunexSU 图标">
</p>

# LunexSU

一个 Android 上基于内核的 Root 方案。

LunexSU 基于 [KernelSU](https://github.com/tiann/KernelSU)，在现有超级用户预管理机制中加入授权有效期，并提供 Material / MIUIX 两套界面。本文沿用 KernelSU README 的介绍结构，补充 LunexSU 的功能与使用说明。

[![Builds](https://img.shields.io/github/actions/workflow/status/Shixiaoshi0417/LunexSU/build-manager.yml?label=Manager&logo=github)](https://github.com/Shixiaoshi0417/LunexSU/actions)
[![License: GPL v2](https://img.shields.io/badge/Kernel-GPL%20v2-orange.svg?logo=gnu)](kernel/LICENSE)
[![License: GPL v3](https://img.shields.io/badge/Manager%20%26%20Userspace-GPL%20v3-blue.svg?logo=gnu)](LICENSE)

[下载发布版本](https://github.com/Shixiaoshi0417/LunexSU/releases) · [构建记录](https://github.com/Shixiaoshi0417/LunexSU/actions) · [问题反馈](https://github.com/Shixiaoshi0417/LunexSU/issues)


正式 Manager 包名为 `com.shixiaoshi0417.lunexsu`，PR 构建为 `com.shixiaoshi0417.lunexsu.pr`。新包名独立安装，原 Manager 私有设置不会自动迁移；Core 必须使用相同包名及匹配签名。模块接口和授权持久化路径继续兼容上游。详见[品牌迁移记录](docs/lunexsu-brand-migration.md)。

## 特性

- 基于内核的 `su` 和权限管理，沿用 KernelSU 的超级用户预管理方式。
- 基于 [Metamodule](https://kernelsu.org/zh_CN/guide/metamodule.html) 的模块系统：可插拔的模块架构，支持无系统修改。
- [App Profile](https://kernelsu.org/zh_CN/guide/app-profile.html)：为每个应用配置 Root 身份、能力和 SELinux 上下文，把 Root 权限关进笼子里。
- 本次、限时、永久三种授权模式，在真正的 Root 授权路径检查有效期。
- Material / MIUIX 双 UI，以及来自 FinaSU 的主题配色、字体、交互动效和 Material 首页布局。
- FinaSU FPT 主题包导入，支持配色、字体、首页布局和图片背景。
- FinaSU 在线主题商店：浏览、搜索、筛选、下载（可暂停／续传）并应用在线主题。
- 统一的 LunexSU 品牌资源与 Android 自适应启动器图标。

## 兼容状态

KernelSU 上游面向 Android GKI 2.0 设备（内核 5.10 及以上），上游 README 也列出旧内核（最低 4.14+）的自行编译支持。LunexSU 继承相应的内核集成方式；具体设备还需确认内核配置、KMI、安装方式及签名，不能仅凭内核版本判断兼容性。

上游列出的架构为 `arm64-v8a` 和 `x86_64`，并包含 WSA、ChromeOS 和容器 Android 等运行环境。这些是上游的兼容范围，LunexSU 的 Root 有效期仍需在对应 Core 的实际设备上验证。

> [!CAUTION]
> KernelSU 上游指出，部分新内核的变更可能导致 `x86_64` 无法运行，甚至发生 kernel panic。请先阅读 [上游 x86_64 支持说明](https://kernelsu.org/zh_CN/guide/x86_64-support.html)。普通 Android Studio 模拟器可以验证 Manager 界面，但需要配套 Core 才能测试 Root 授权。

Root 有效期需要配套的 LunexSU Manager、ksud 和 Core（授权 UAPI v6）；单独替换 Manager 不能让旧 Core 执行临时授权策略。

## 使用方法

1. 从 [LunexSU Releases](https://github.com/Shixiaoshi0417/LunexSU/releases) 获取适合设备的版本；开发构建可查看 [Actions](https://github.com/Shixiaoshi0417/LunexSU/actions)。
2. 参考 KernelSU 的 [安装教程](https://kernelsu.org/zh_CN/guide/installation.html)，确认设备、KMI 和安装方式，安装与 Manager 配套的 LunexSU Core。
3. 打开超级用户页面，为应用配置权限及「本次 / 限时 / 永久」授权模式。
4. 在模块页面管理模块；需要修改 `/system` 文件时，按 [Metamodule 指南](https://kernelsu.org/zh_CN/guide/metamodule.html) 配置挂载支持。
5. 在主题设置中选择配色、字体和布局，或导入 FinaSU FPT 主题包。

常用文档：

- [KernelSU 官方网站与指南](https://kernelsu.org/zh_CN/)
- [如何构建 KernelSU](https://kernelsu.org/zh_CN/guide/how-to-build.html)
- [非 GKI 设备集成](https://kernelsu.org/zh_CN/guide/how-to-integrate-for-non-gki.html)
- [模块开发指南](https://kernelsu.org/zh_CN/guide/module.html)
- [App Profile](https://kernelsu.org/zh_CN/guide/app-profile.html)
- [模块 WebUI](https://kernelsu.org/zh_CN/guide/module-webui.html)
- [常见问题](https://kernelsu.org/zh_CN/guide/faq.html)

以上 KernelSU 文档用于说明基础机制；LunexSU 新增功能以本仓库的文档为准。

## Root 授权有效期

在超级用户页面提前配置 App 的权限及授权模式，由 Core 在实际授权路径执行检查。

| 模式 | 行为 |
| --- | --- |
| 本次 | 仅允许一个新的 `su` 成功授权；并发请求原子预留同一额度，授权失败退还额度。未使用的授权重启后失效。 |
| 限时 | 支持 5 分钟、15 分钟、1 小时及自定义时长，有效期内允许多次 `su`；可查看剩余时间、续期或提前撤销。 |
| 永久 | 沿用 KernelSU 的永久预授权行为。 |

临时授权使用内核启动时间计时，休眠时间计入有效期，修改系统日期不会延长授权；重启后本次和限时授权均需重新授予。到期或撤销会拒绝新的授权，已成功启动的 Root 进程继续运行。

实现包含代际校验、完整 UID/包名/数据目录身份检查，以及持久化失败时的拒绝策略。详见 [授权设计与边界行为](docs/lunexsu-authorization.md) 和 [授权测试](tests/authorization/README.md)。

## 界面与主题

**UI 来源：[FinaSU（原 FolkSU），由 LyraVoid 开发](https://github.com/LyraVoid/FinaSU)。** 本项目移植其主题配色、字体设置、交互动效及 Circle、Grid、Focus、Dashboard、Stats 五种 Material 首页布局，并沿用 KernelSU 的 Material / MIUIX 双 UI 架构。移植参考版本为 `fa57d340`，保留原有 GPL 署名；具体范围见 [UI 移植记录](docs/lunexsu-folksu-theme.md)。

主题设置支持系统/明暗模式、AMOLED、动态取色、自定义颜色、调色板样式和字体导入。可导入 FinaSU FPT 主题包中的配色、字体、首页布局和全局／分页面图片背景，应用前展示主题信息及兼容提示，并支持恢复默认主题。主题商店可浏览和下载 FinaSU 商店中的在线主题，下载后同样经过确认才会应用。视频／音频、卡片专属图片和自定义导航图标暂未移植，详见 [主题包导入说明](docs/lunexsu-fpt-import.md)。

启动器使用完整底色与留白的自适应图标，由 Android 应用设备对应的圆形或圆角遮罩；应用内、网站和文档使用同一套 LunexSU 品牌资源。

## 模块系统

保留 KernelSU 的模块、Metamodule、App Profile 和模块 WebUI 机制：

- 在 Manager 中安装、启用、停用和卸载模块。
- 沿用模块启动脚本、`system.prop` 和 SELinux 策略处理。
- 由 Metamodule 提供系统文件挂载能力，同一时间只允许一个活动 Metamodule。
- 通过模块 WebUI 使用模块提供的配置界面。
- 通过 App Profile 配置各应用的 Root 权限及非 Root 行为。

只使用脚本、SELinux 策略或 `system.prop` 的模块无需 Metamodule；修改 `/system` 文件的模块需要配套挂载实现。详见 [上游安装指南](website/docs/zh_CN/guide/installation.md) 和 [Metamodule 指南](https://kernelsu.org/zh_CN/guide/metamodule.html)。

## 构建与开发

| 目录 | 内容 |
| --- | --- |
| `kernel/` | Core、授权判定及内核接口 |
| `userspace/ksud/` | Root 会话与用户空间守护进程 |
| `manager/` | Kotlin / Compose 管理器 |
| `userspace/meta-overlayfs/` | Metamodule 挂载实现 |
| `tests/authorization/` | 授权单元与设备集成测试 |
| `website/`、`js/` | 文档网站与模块 WebUI 库 |

先配置 Android SDK/NDK、Rust Android 目标和 `cargo-ndk`，构建 ksud：

```sh
cd userspace/ksud
cargo ndk -t arm64-v8a check
cargo ndk -t arm64-v8a clippy
cargo fmt
cargo ndk -t arm64-v8a build --release
```

将生成的 ksud 放入 Manager 再构建 APK：

```sh
cd ../../manager
mkdir -p app/src/main/jniLibs/arm64-v8a
cp ../target/aarch64-linux-android/release/ksud app/src/main/jniLibs/arm64-v8a/libksud.so
./gradlew clean assembleRelease :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
```

内核及签名构建以 [构建工作流](.github/workflows) 为准。Windows 可通过 Android Studio 配置工具链；本仓库的本地验证使用 WSL。网站构建使用 `bun install` 和 `bun run docs:build`。

## 测试与验证

- Root 授权：主机测试覆盖并发额度竞争、失败退还、到期判定及持久化故障，设备测试入口见 [授权测试说明](tests/authorization/README.md)。
- Manager：已运行主题相关 JVM 单元测试、Android 集成测试及 Material / MIUIX 模拟器导入流程，详见 [FPT 导入验证记录](docs/lunexsu-fpt-import.md)。
- 品牌资源：启动器、启动页、应用内、网站和文档的资源说明见 [图标适配记录](docs/lunexsu-brand-assets.md)。
- 构建与整体集成记录见 [集成验证说明](docs/lunexsu-theme-integration.md)。

真实设备的 `su`/SELinux、重启、多用户、卸载重装及模块回归仍需在配套 LunexSU Core 上验证；普通模拟器的主题测试不能代替这些检查。

## 参与开发与翻译

欢迎通过 [Issues](https://github.com/Shixiaoshi0417/LunexSU/issues) 反馈问题，或通过 [Pull Requests](https://github.com/Shixiaoshi0417/LunexSU/pulls) 提交改进。报告设备相关问题时，请附上设备型号、Android 版本、内核版本、安装方式、Manager/Core 版本以及复现步骤。

本项目 README 仅提供简体中文与英语：默认显示中文，点击顶部的 **English** 切换。Manager 的语言资源沿用现有项目结构，新增字符串应保持中英文一致；这与 README 的双语范围分开维护。

修改内核接口时需同步 ksud 和 Manager JNI；开发约定与各组件检查命令见 [AGENTS.md](AGENTS.md)。

## 讨论

- LunexSU 问题反馈：[GitHub Issues](https://github.com/Shixiaoshi0417/LunexSU/issues)。
- KernelSU 上游讨论：Telegram [@KernelSU](https://t.me/KernelSU)。该频道属于上游。
- FinaSU 主题与 UI 来源：[LyraVoid/FinaSU](https://github.com/LyraVoid/FinaSU)。

## 安全性

有关报告安全漏洞的信息，请参阅 [SECURITY.md](SECURITY.md)。该文件说明本项目的报告入口；LunexSU 新增代码相关问题请明确标注所用分支和版本。

## 许可证

- `kernel/` 使用 [GPL-2.0-only](kernel/LICENSE)；其余部分沿用 [GPL-3.0-or-later](LICENSE) 及文件中的原始许可声明。
- FinaSU 移植代码保留原作者与 GPL 许可声明，范围和参考版本见 [UI 移植记录](docs/lunexsu-folksu-theme.md)。

## 鸣谢

- [KernelSU / tiann](https://github.com/tiann/KernelSU)：内核 Root、管理器及模块系统的上游。
- [FinaSU（原 FolkSU）/ LyraVoid](https://github.com/LyraVoid/FinaSU)：主题、首页布局、交互动效及 FPT 格式的 UI 来源。
- [Kernel-Assisted Superuser](https://git.zx2c4.com/kernel-assisted-superuser/about/)：KernelSU 的灵感。
- [Magisk](https://github.com/topjohnwu/Magisk)：强大的 Root 工具箱。
- [genuine](https://github.com/brevent/genuine/)：APK v2 签名验证。
- [Diamorphine](https://github.com/m0nad/Diamorphine)：上游引用的内核实现技巧。
