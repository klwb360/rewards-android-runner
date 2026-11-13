# Rewards Android 接手说明

> 状态快照：2026-11-13。此目录是基于 MaaFwApp 的 Android 工作副本，仍处于开发和验证阶段。下面保留的 MaaFwApp 原说明是上游通用文档，不能视为本工作副本已经完成构建、安装或验收，也不能直接用上游克隆地址取得本次修改。

## 项目现状

- Rewards 试验包使用独立的 capture-review 模块和 captureReviewDebug 变体。它共享 app/src/main 下的部分源码；不要把它与普通 app 模块的版本混为一谈。
- capture-review 的源码配置为 V54。OwnState 只读状态汇总及四处修订已进入工作副本源码；保存的独立 Java 结果为 46 项测试通过，且受检源码与记录的哈希一致。这不是 Android 单测、构建、lint 或真机验收通过的证明。
- 本机已有 APK 的输出元数据仍是 V53。最近一次 V54 汇总日志没有最终成功结果，因此 V54 的 Android 构建、lint、安装和设备行为均未确认；这次也没有检查当前设备上安装的是哪个版本。
- experiments/offline-reading-v3 保存独立合成实验源码，不参与 Android 构建。离线 Reading v3 的持久化和残留检查尚未接入 Android 生产源码。较新的独立 Java 验证记录为 6 组、1,734 个合成断言；这不是 Android 集成测试，也不证明实际积分、恢复、迁移或重试能力。实验不得据此连接真实任务。
- 当前源码中的 CaptureReleaseGate 将 REVIEWED_REAL_CAPTURE 设为 true，capture-review 的清单启用了投影服务。不要依据历史报告假定本版“默认关闭”。运行或安装前应重新审查权限、触发入口与用户授权。

## 目录与依赖

- capture-review：Rewards 试验构建目标，复用 app 中指定的源码、资源和测试。
- app：MaaFwApp 主模块及共享的 Android 源码；once、readonly、rewards 等目录包含相关实现。
- build-logic、gradle、annotation-api、ksp-processor、semi-icons、hidden-api：构建逻辑和辅助模块；macrobenchmark、testapp 为额外测试模块。
- 本次公开源码有意不包含 OCR 的 det.onnx、rec.onnx、keys.txt：这些模型在原工作副本中存在，但其来源与再分发许可尚未确认。相关 OCR 功能在补齐经核准的模型之前不可用；不要假定仓库包含完整运行资源。原上游 M9A 测试资源和生成的 baseline profile 也未纳入，依赖这些文件的测试或优化需另行准备。
- 本目录不含完整的可移植构建环境。需要 JDK 17、Android SDK（compileSdk 37，minSdk 28，targetSdk 36）、CMake 3.22.1，以及项目需要的 Gradle 9.4.1、Android Gradle Plugin 9.2.1、Kotlin 2.3.21 和相应依赖。部分构建还需要 NDK、MaaFramework 原生库、PI 资源和外部打包配方；具体约定见下方原说明及 INTEGRATION.md。
- 当前调试配置要求通过 Gradle 属性 diagnosticKeystore 指定本机调试签名文件。签名文件、密码、local.properties、PI 配方和任何本机路径应由接手者安全配置，不要提交到仓库。
- 之前的本机检查脚本位于项目目录之外，并依赖那台机器上的 SDK、JDK、依赖缓存和配方；不能假定复制本目录后仍可直接运行。新环境可能需要联网获取依赖，须遵守其网络和安全策略。

## 后续构建和检查

先准备并核对上述环境、签名文件、资源及必要权限。以下是当前模块定义的候选检查任务，须将占位路径换成本机配置；它们在本次交接中没有重新运行，也不保证在另一台机器上直接通过：

    .\gradlew.bat -PdiagnosticKeystore="<本机调试签名文件>" :capture-review:testCaptureReviewDebugUnitTest
    .\gradlew.bat -PdiagnosticKeystore="<本机调试签名文件>" :capture-review:assembleCaptureReviewDebug
    .\gradlew.bat -PdiagnosticKeystore="<本机调试签名文件>" :capture-review:lintCaptureReviewDebug

分别记录退出码及完整结果，确认测试、构建和 lint 三项的结果均针对同一份最终源码。仅在构建与权限边界核实后，另行安排设备验证；不要把历史 V53 APK 或独立 Java 测试当成 V54 验收。需要真实账户、截图、捕获权限或网络操作的步骤，应先确认授权和隐私边界。

## 资料保护与交接

只提交经过逐项检查的项目源码、必要构建文件和许可资料。不要提交账号或登录状态、令牌与密钥、签名文件、个人配置、原始界面数据、设备截图、私人报告、日志、APK、SDK/JDK、依赖缓存或构建输出。资源和测试素材也要检查来源、许可及是否包含个人信息；缺少的外部依赖应在交接时单独说明。不要删除、迁移或重置已有账户状态和账本来排查问题。

---

# 以下为保留的 MaaFwApp 上游通用说明
# MaaFwApp

MaaFramework 的 Android GUI

[![License](https://img.shields.io/github/license/Aliothmoon/MaaFwApp?style=flat-square&color=4a90d9)](./LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%209%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/Aliothmoon/MaaFwApp)
[![API](https://img.shields.io/badge/minSdk-28-green?style=flat-square)](https://developer.android.com/google/play/requirements/target-sdk)
[![Commit Activity](https://img.shields.io/github/commit-activity/m/Aliothmoon/MaaFwApp?style=flat-square&color=00d4aa)](https://github.com/Aliothmoon/MaaFwApp/commits)
[![Stars](https://img.shields.io/github/stars/Aliothmoon/MaaFwApp?style=flat-square&color=ffca28)](https://github.com/Aliothmoon/MaaFwApp/stargazers)

## 项目简介

MaaFwApp 是基于 [MaaFramework](https://github.com/MaaXYZ/MaaFramework) 的 Android 通用 GUI。资源开发者通过 [Project Interface V2](https://github.com/MaaXYZ/MaaFramework/blob/main/docs/zh_cn/3.3-ProjectInterfaceV2%E5%8D%8F%E8%AE%AE.md) 描述任务、选项和界面文本，用户即可在手机上配置并运行自动化任务。

MaaFramework 跑在 [Shizuku](https://shizuku.rikka.app/) 或 root 拉起的特权进程里，截屏和点击走本机 native controller，不经过 adb。

MaaFwApp 本身不包含具体业务资源。Android 上要把资源在**构建期**打进 APK。接入步骤见 [资源接入](INTEGRATION.md)。

## 主要功能

### 任务与资源

- 支持 Project Interface V2 的任务、选项（选择 / 开关 / 多选 / 输入）、预设、分组、`import`、`global_option` 与国际化。
- 任务说明支持 Markdown、文件路径和 http(s) 链接；首启欢迎、关于页的联系方式 / 许可 / 仓库地址取自 PI 顶层字段。
- 可同时保存多份运行配置，配置之间互相独立；同一配置里允许重复添加同一任务。
- 支持 PI 声明的 agent（自定义识别器 / 动作）。

### 运行与设备

- 后台模式：在虚拟屏上跑任务，手机可正常使用，分辨率可选 720P / 1080P。
- 前台模式：直接操作当前屏幕；可用悬浮球或音量键呼出操作面板。
- 后台运行时可自动熄屏挂机；定时触发支持亮屏并输入数字 PIN。
- 运行中在系统通知里显示进度。

### 定时、通知与诊断

- 支持按规则定时执行，可绑定到指定运行配置。
- 任务结束可发系统通知，也可推到 Server酱、Telegram、Discord、钉钉、KOOK、SMTP、Bark、Qmsg、Gotify 和自定义 Webhook。
- 每次运行的日志可回看、导出；另有应用错误日志。
- 资源可在 PI 里配置 Sentry 遥测；只有资源提供了配置、且用户未关闭「帮助改进本项目」时才会发送。任务失败时会附带该任务期间的运行日志与出错截图。

## 运行要求

| 项目 | 要求 |
|:---|:---|
| 系统 | Android 9（API 28）及以上 |
| 提权 | [Shizuku](https://shizuku.rikka.app/) 或 root，二选一，可在应用内切换 |
| 资源 | 一份符合 Project Interface V2 的资源项目（已打进当前 APK） |

不同资源可能还要求目标应用已安装、通知权限、电池白名单等，以对应资源项目的说明为准。没连上特权进程时只能看界面，不能跑任务。

## 快速开始

### 使用已打包的应用

1. 安装资源项目提供的 APK。
2. 按提示授予通知、电池白名单等权限。
3. 在应用里选择 Shizuku 或 root，完成授权并等到服务连上。
4. 选择服务器（资源）、核对任务列表，开始运行。

### 接入自己的资源项目

1. 先按 [Project Interface V2 协议](https://github.com/MaaXYZ/MaaFramework/blob/main/docs/zh_cn/3.3-ProjectInterfaceV2%E5%8D%8F%E8%AE%AE.md) 写好 `interface.json` 和资源。开发和排查 Pipeline 请用 MaaFramework 提供的调试工具，不要把本应用当调试器。
2. 拷贝 [`pi-profile.sample.yaml`](pi-profile.sample.yaml)，填资源路径和包名，放到本仓库之外。
3. 在 `local.properties` 里写 `pi.profile=<配方的绝对路径>`（或设环境变量 `PI_PROFILE`）。
4. 拉 MaaFramework 的 Android 产物并出包，详见 [资源接入](INTEGRATION.md)。

换一份资源 = 换一份配方 + 重新出包，不必改本仓库的代码。

## 资源开发

- [Project Interface V2 协议](https://github.com/MaaXYZ/MaaFramework/blob/main/docs/zh_cn/3.3-ProjectInterfaceV2%E5%8D%8F%E8%AE%AE.md)
- [MaaPracticeBoilerplate](https://github.com/MaaXYZ/MaaPracticeBoilerplate)
- [把资源打进 MaaFwApp](INTEGRATION.md)

## 从源码构建

需要 JDK 17、Android SDK、Python 3。构建用 git 计算版本号：独立 checkout 跟随本仓库，作为 submodule 时跟随最外层主仓库。请在 clone 下来的仓库里执行：

```bash
git clone https://github.com/Aliothmoon/MaaFwApp.git
cd MaaFwApp
python scripts/setup_maa_framework.py
./gradlew :app:installDebug          # Windows 用 .\gradlew.bat
```

未配置 `pi.profile` 时构建不会失败，只是包里没有资源。Release 签名读 `KEYSTORE_PATH` 等环境变量或 `local.properties`；缺了就打出未签名包。

## 相关项目

- [MaaFramework](https://github.com/MaaXYZ/MaaFramework) — 基于图像识别的自动化框架

## 开源许可

本项目基于 [GNU Affero General Public License v3.0](LICENSE) 开源。

## 致谢

MaaFwApp 使用了 [MaaFramework](https://github.com/MaaXYZ/MaaFramework)、[Shizuku](https://github.com/RikkaApps/Shizuku) 等开源项目。
