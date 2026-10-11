**简体中文** | [English](README_EN.md)

<p align="center">
  <img src="docs/assets/lunexsu-logo.png" width="160" alt="LunexSU 图标">
</p>

# LunexSU

LunexSU 是基于 [KernelSU](https://github.com/tiann/KernelSU) 的 Android 内核 Root 项目，在现有超级用户预管理机制中加入授权有效期，并提供 Material / MIUIX 两套界面。

[下载发布版本](https://github.com/Shixiaoshi0417/LunexSU/releases) · [构建记录](https://github.com/Shixiaoshi0417/LunexSU/actions) · [问题反馈](https://github.com/Shixiaoshi0417/LunexSU/issues)

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

**UI 来源：[FolkSU，由 LyraVoid 开发](https://github.com/LyraVoid/FolkSU)。** 本项目移植其主题配色、字体设置、交互动效及 Circle、Grid、Focus、Dashboard、Stats 五种 Material 首页布局，并沿用 KernelSU 的 Material / MIUIX 双 UI 架构。移植参考版本为 `fa57d340`，保留原有 GPL 署名；具体范围见 [UI 移植记录](docs/lunexsu-folksu-theme.md)。

主题设置支持系统/明暗模式、AMOLED、动态取色、自定义颜色、调色板样式和字体导入。可导入 FolkSU FPT 主题包中的配色、字体、首页布局和全局／分页面图片背景，应用前展示主题信息及兼容提示，并支持恢复默认主题。在线主题商店、视频／音频、卡片专属图片和自定义导航图标暂未移植，详见 [主题包导入说明](docs/lunexsu-fpt-import.md)。

启动器使用完整底色与留白的自适应图标，由 Android 应用设备对应的圆形或圆角遮罩；应用内、网站和文档使用同一套 LunexSU 品牌资源。

## 模块与兼容性

保留 KernelSU 的模块、Metamodule、App Profile 和模块 WebUI 机制。设备是否可用取决于内核、架构、安装方式及签名配置，请参考 [上游安装指南](website/docs/zh_CN/guide/installation.md)。

Root 有效期需要配套的 LunexSU Manager、ksud 和 Core（授权 UAPI v6）；单独替换 Manager 不能让旧 Core 执行临时授权策略。主机测试和编译验证已覆盖授权并发与持久化故障；真实设备的 `su`/SELinux、重启、多用户、卸载重装及模块回归仍需验证。

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

## 许可与致谢

- `kernel/` 使用 [GPL-2.0-only](kernel/LICENSE)；其余部分沿用 [GPL-3.0-or-later](LICENSE) 及文件中的原始许可声明。
- [KernelSU / tiann](https://github.com/tiann/KernelSU)：内核 Root、管理器及模块系统的上游。
- [FolkSU / LyraVoid](https://github.com/LyraVoid/FolkSU)：主题、首页布局、交互动效及 FPT 格式的 UI 来源。
- [Kernel-Assisted Superuser](https://git.zx2c4.com/kernel-assisted-superuser/about/)、[Magisk](https://github.com/topjohnwu/Magisk)、[genuine](https://github.com/brevent/genuine/)、[Diamorphine](https://github.com/m0nad/Diamorphine)：保留上游致谢。

安全问题报告方式见 [SECURITY.md](SECURITY.md)。
