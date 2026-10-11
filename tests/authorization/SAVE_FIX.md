# Root 授权有效期保存修复 / Temporary authorization save repair

## 中文说明

保存调用链为 Material / MIUIX App Profile → 共用保存仓库 → Kotlin
`Natives` → JNI → `SET_AUTH_PROFILE` → Core `set_profile`，读取则通过
`GET_AUTH_PROFILE` 返回同一模型。旧版 Profile 保持 v4 / 784 字节，授权扩展
保持 824 字节，没有修改 Core 与用户空间 ABI。

源码中确认的缺陷是：Core 切换到 Root 凭据后仍在 Manager 的挂载视图中查询
其他 App 的数据目录。Android 的 App 数据隔离会隐藏这些目录，导致安装身份
校验失败；JNI 再把具体错误压成布尔值。该路径能够解释 MT 管理器的保存失败
和重新进入时仍显示原有永久授权，但尚未取得 Redmi K60 上的实际 errno。

修复改用 Android init 的根目录路径引用查询和持久化，保留 UID / inode 校验、
失败关闭和事务发布顺序。JNI 保留负 errno，并按实际 IOCTL 能力判断兼容性。
两套 UI 共用串行保存、保存后重新读取及结果校验，支持本次、限时、永久、
预设及自定义分钟数、续期和撤销，不自动重试为永久授权。

主要涉及文件：

- Core：`kernel/policy/allowlist.c`、新增 `authorization_path.h`。
- JNI：`jni.cc`、`ksu.cc/h`、新增 `authorization_compat.h`；Kotlin `Natives.kt`。
- UI：新增 `AuthorizationRepository.kt`；`AppProfileScreen.kt`、
  `AppProfileMaterial.kt`、`AppProfileMiuix.kt`、`AppProfileUiState.kt`、
  `AuthorizationConfig.kt` 和中英字符串。
- 回归测试：授权主机测试、路径 / ABI 测试、保存仓库 JVM 测试、
  Material / MIUIX Android UI 测试及仅存在于 Debug 的测试宿主。

必须配套重新构建的 LKM。单独安装 APK 不能修复旧 Core 的目录查询。
模拟器未加载 LunexSU Core，只验证 JNI 错误和使用注入存储的真实 UI 页面。
K60 的保存、单次并发及失败退款、到期 / 重启、多用户和重装边界仍需真机验收。
其他 Agent 的主题、字体和统计页面改动保留，没有合并 main。

已通过：54 项 JVM 测试（含 8 项保存仓库回归）、18 项双 UI 模拟器回归、
13 项主题 / 字体集成测试、128 万次单次授权并发尝试、10 类持久化 I/O 故障、
ABI / 能力兼容和 5.10 / 5.14 / 5.15 路径 API 回归。Core 的 6 个相关对象
在可用 Linux 7.0 头文件下编译通过，内核 clang-format 检查通过。
最终 `clean assembleRelease` 连同 JVM 测试及 Debug / AndroidTest APK 构建
全部成功（157 个任务，155 个实际执行，2 分 30 秒）。Release 签名沿用本地
构建配置的 Debug 签名回退；真机使用时必须与 Core 的 Manager 签名配置匹配。

模拟器中 Manager 能读取自己的数据目录，查询独立测试 APK 的
`/data/user/0/com.shixiaoshi0417.lunexsu.test` 返回 ENOENT（errno 2）；用测试 APK
自己的 UID 查询同一目录则存在。这验证了 App 挂载视图的隔离现象，但不等同
于已经捕获 K60 Core 的实际错误或完成 Android 5.10 LKM 链接验证。

## English explanation

## Trace and cause

Both App Profile pages call `AuthorizationConfig`, then the shared
`AppProfileScreen` action, `Natives.setAppProfile`, JNI, `SET_AUTH_PROFILE`,
`do_set_auth_profile`, and `ksu_set_auth_profile` / `set_profile`.
Reads travel back through `GET_AUTH_PROFILE` and the same JNI model.

The reported package `bin.mt.plus` and UID 10387 pass package/app UID
validation. Once accepts zero duration. The upstream profile is still v4,
784 bytes; the separate authorization command is 824 bytes, with mode at
offset 784 and generation at 800. No Core/Rust ioctl layout was changed.

The Core install identity lookup used `override_creds(ksu_cred)` followed by
`filp_open("/data/user/<user>/<package>")` in the calling Manager's mount
view. Root credentials do not remove Android app data isolation: unrelated
app directories can be hidden by tmpfs. An existing app therefore produces
ENOENT, or a replacement directory fails the UID/inode check. `set_profile`
rejects the candidate before publishing it; the original Permanent profile
remains. JNI collapsed all errors to false, explaining the generic failure
feedback and the original mode on reentry. This is a confirmed source defect;
the precise errno from the reported K60 has not yet been captured.

Android's implementation is documented in the
[AOSP Zygote source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/jni/com_android_internal_os_Zygote.cpp)
(`isolateAppData`, `mountAppData`).

## Repair and safety

- Resolve install identity from Android init's referenced root path in the
  caller's PID namespace; never switch the Manager's namespace or fs_struct.
  Pin and compare the real data inode and full UID as before.
- Resolve the allowlist directory from that same trusted view; open staging
  and guard files relative to its directory handle. Keep the existing durable
  guard, fsync/rename transaction and publish-after-write behavior.
- Carry negative errno through JNI. Check the actual authorization ioctl;
  only explicit unsupported-command errors permit the permanent-only reader.
  Access, ABI, identity, stale and storage failures do not trigger a downgrade.
  Reject malformed replies and expose an unknown mode on read failure.
- Serialize UI saves and countdown reads. Read back Core policy after success
  or rejection; verify mode, configured permission and requested Timed duration
  before acknowledging success. Do not automatically retry stale/busy saves,
  rearm a consumed grant, or write a rejected request through the legacy ABI.
- Material and MIUIX share the repository and grant parameter builder. Both
  provide Once/Timed/Permanent, 5/15/60-minute presets, bounded custom duration,
  renewal/revocation, feedback, saving guards and fresh state on resume.
- The existing mutex-protected Once lease is unchanged: reserve atomically,
  finish after successful ksud exec and session FD installation, refund on
  failed admission, and kill a failed elevated temporary exec before it can
  return to the original app. A successful process is not terminated.

A rejected update leaves the previously committed policy unchanged. If that
policy was already Permanent, it remains Permanent and the rejection is shown;
the Manager does not claim that a Once grant was saved. No requested grant is
broadened by retry/fallback. There are no Rust, module or Metamodule changes.

## Regression and reproduction

```sh
python3 tests/authorization/run_host_tests.py
cd manager
./gradlew clean assembleRelease :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest
adb -s <dedicated-emulator> shell am instrument -w -e suite authorization_ui \
  com.shixiaoshi0417.lunexsu.test/com.shixiaoshi0417.lunexsu.AuthorizationTestRunner
```

Host tests compile the production state/persistence headers, compatibility
checks and root-path helper. The path test mocks kernel task/path plumbing
and uses real directories/openat for distinct caller/init views, including
Linux 5.10, 5.14 and 5.15 signatures, multi-user paths and fail-closed lookup.
It does not validate GKI mount traversal or symbol availability at runtime.

JVM tests exercise parameters, unsupported Core, errno preservation, rejection
without retry, readback mismatches, consumed Once KEEP, revoke and polling/save
serialization. Device UI tests host the actual Material/MIUIX App Profile
pages with injected storage. The debug host is protected by DUMP and absent
from release; it never grants Root. It also reports the Manager's lookup of
the test APK's separate data directory, without requesting Root.

For real Core integration, deploy matching rebuilt Core/ksud/recognized
Manager and use the disposable fixture harness in [README.md](README.md).
It now covers Permanent-to-Once save/readback and rejection of a nonexistent
install identity without changing the committed policy. Do not use MT Manager
as the destructive/concurrent fixture. Independently verify MT Manager's
mode save/reentry without launching su or consuming its credit.

## Validation boundary

Local checks include host safety tests, JVM regressions, clean Release/Debug
and test APK builds, Core authorization object compilation against available
Linux 7.0 headers, and kernel-wide clang-format. Core object compilation is
not a full Android 12 / Linux 5.10 LKM build.

The emulator passed 18 authorization UI cases and 13 theme/font cases; all
54 JVM tests passed (8 save repository regressions). Host tests passed 1.28
million Once admission attempts, 10 persistence I/O fault cases, ABI checks
and three root-path API branches. The Manager's peer-directory probe returned
ENOENT while `run-as` under the peer's own UID confirmed the directory exists.

The K60 still requires a matching new LKM: an APK alone cannot repair the old
Core lookup. Verify the actual save errno/identity lookup, page reentry in both
UI modes, 32 parallel su requests yielding one success, failed exec refund,
Timed expiry/renew/revoke, suspend and wall-clock changes, reboot invalidation,
UID/user/reinstall identity changes, and module/Metamodule smoke tests. The
Core-less Android Studio emulator cannot certify these kernel behaviors.
