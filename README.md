# Android 版 Bing Rewards Runner

V55 在雷电 Android 14（x86_64）本机运行，可从桌面的 **Rewards 助手** 启动。APK 内含界面识别代码和 OCR 模型，日常使用无需连接电脑或另行下载模型。Bing 需要联网并登录账户，设备需已运行 root 模式的 Shizuku，且已为这个 APK 授权。

## 快速开始

1. 将 V55 APK 复制到雷电，在安卓文件管理器中点开安装。已有本项目的同签名版本可直接覆盖安装，保留已有任务记录。
2. 打开一次 **Rewards 助手**，再到 Bing 主页打开 Rewards。积分卡加载后，从最近任务切回助手。
3. 点 **打开受控确认页（尚不采集） → 打开 Rewards 自动运行**。选 **完整流程 · 基础任务、阅读和搜索 → 确认开始完整流程**，或选 **单独运行搜索 → 确认开始字母搜索**。
4. 完整流程依次执行基础任务、阅读和搜索。基础任务沿用签入、每日活动、更多活动的顺序。阅读采用每天最多七篇的连续入口，跳过广告，每篇停留至少十秒。当天已尝试的任务按本机账本跳过。
5. 搜索按小写 **a 到 t** 顺序进行，每次只提交一个字母，从成功提交起等待至少五秒，然后返回一次。程序记录已尝试的字母，重新启动也会跳过这些字母。执行次数和返回次数可用于核对流程，账户实际计分以 Bing Rewards 页面为准。

来源、输入结果或清理状态无法确认时，本轮会停止。保留助手数据即可保留当天进度。新流程使用系统窗口信息、设备端界面识别和既有输入桥，截图、账本及诊断留在本机。

## 早期 V54 只读基线

PR #34 保留了最早通过两轮设备只读复查的 V54 源码，版本名为 `0.54-native-own-state`。[基线分支](https://github.com/klwb360/rewards-android-runner/tree/baseline/v54-android14-20261118) · [PR #34](https://github.com/klwb360/rewards-android-runner/pull/34)

### 已有基线 APK，在雷电中验证只读流程

1. 准备雷电 Android 14 x86_64、Bing 和以 root 身份运行的 Shizuku。
2. 如果已经安装验收版本，直接进入下一步。需要安装时，先用 `adb devices` 找到目标模拟器序列号。以下为 PowerShell 示例，替换 APK 路径和序列号。同签名覆盖安装使用 `-r` 保留数据；已有历史记录的设备应继续使用原签名。

   ```powershell
   $apk = 'D:/rewards/Rewards-V54-PR34-Android14-x86_64.apk'
   Get-FileHash -Algorithm SHA256 -LiteralPath $apk
   adb -s emulator-5554 install -r $apk
   ```

   本机验收 APK 的 SHA-256 为 `66a2f3e2dd46a5510aab8390bacdfc556e2bbe7c36986b954813739069ed5095`。此指纹用于比对归档文件。其他签名或资源组合可生成不同指纹。
3. 安装后，在 Shizuku 中确认 **Rewards只读** 已获授权。在 Bing 中登录并打开 Rewards 主页，等积分和活动区域加载，再切换到 **Rewards只读**。依次选择 **打开受控确认页（尚不采集） → 打开其余卡片模拟测试（尚不采集） → 打开独立只读预检（不点击任务） → 打开更多活动二次复查（只读） → 确认只读验证更多活动复查**。
4. 确认后让程序自行读取。完成页显示 `stage=COMPLETE` 与 `released=true` 表示本轮结束且资源已释放。该入口只读界面结构，任务点击、返回、滚动和截图均为零。验收时可从本机导出的 `UI_RECHECK.json` 查看每次读取的结果，从 `RELEASE.json` 核对释放状态。导出到电脑下载目录由本机验收工具完成。

**如何看第二次复查。** 两次读取共用五秒，第一次结束到第二次至少间隔一秒。两轮实测中，第一次均成功，第二次因余时不足而在启动读取器前停止（`SHARED_SECOND = INSUFFICIENT_BUDGET`）。随后在单独的五秒窗口，第二次读取均成功（`INDEPENDENT_SECOND = QUALIFIED`），收到了输出流结束、进程正常退出、管道关闭和原生清理凭据。该验收覆盖界面读取与释放；账户积分到账情况须另行核实。

### 从源码构建

示例使用 Windows PowerShell，在仓库根目录运行。

```powershell
git clone https://github.com/klwb360/rewards-android-runner.git
cd rewards-android-runner
git switch -c work/my-change
```

1. 准备 JDK 17、Android SDK Platform 37、Android SDK Platform-Tools、与构建配置兼容的 NDK、CMake 3.22.1 和 Python 3。令 `JAVA_HOME` 指向 JDK，在本机 `local.properties` 配置 SDK 路径。Gradle Wrapper 会获取 Gradle 9.4.1；首次构建需要相应的依赖下载权限或已填充的本地缓存。
2. 在仓库外创建 PI（Project Interface）配方，例如 `D:/rewards-local/pi-profile.yaml`。其中 `assets` 指向备好的 PI 资源目录。下面的 `app.id` 和变体后缀组合成原生入口核验的包名 `com.aliothmoon.maafw.productrewardsworking.capturereview`。[完整配方示例](pi-profile.sample.yaml) · [资源接入说明](INTEGRATION.md)

   ```yaml
   assets: D:/rewards-local/pi-assets
   app:
     id: productrewardsworking
   ```

   在仓库根目录的 `local.properties` 中设置 SDK、配方和雷电架构，将示例路径替换为本机路径。

   ```properties
   sdk.dir=D:/Android/Sdk
   pi.profile=D:/rewards-local/pi-profile.yaml
   build.debugAbi=x86_64
   ```

3. 准备雷电所需的 MaaFramework 原生库。涉及 OCR 时，还需将有权使用且与项目固定摘要一致的 `det.onnx`、`rec.onnx`、`keys.txt` 放入 `app/src/main/assets/bundle/model/ocr/`。这三个文件因来源和再分发许可尚待确认，由开发者在本机单独准备。下方脚本只准备原生库。[模型校验规则](app/src/main/java/com/aliothmoon/maafw/once/MaaOnceModelPolicy.java)

   ```powershell
   python scripts/setup_maa_framework.py --tag v5.15.0 --abi x86_64
   ```

4. 准备已存在的 Android 调试签名库，使用模块要求的标准调试别名 `androiddebugkey` 和调试口令。将下例路径换成签名库的绝对路径，然后针对同一份源码执行三项检查。[签名配置和构建目标](capture-review/build.gradle.kts)

   ```powershell
   $debugKey = 'D:/rewards-local/debug.keystore'
   .\gradlew.bat "-PdiagnosticKeystore=$debugKey" :capture-review:testCaptureReviewDebugUnitTest :capture-review:assembleCaptureReviewDebug :capture-review:lintCaptureReviewDebug
   ```

   成功后 APK 位于 `capture-review/build/outputs/apk/captureReview/debug/capture-review-captureReview-debug.apk`。基线在备齐配方、资源和缓存的环境中完成离线单测、打包和 lint。共发现 2,713 项单测，其中 2,712 项通过，1 项符号链接测试受环境限制，仍待验证。lint 为 0 错误、922 条警告。公开仓库提供源码；签名、模型、APK、账户信息和设备原始诊断保留在本机。

## 技术栈

| 层次 | 本版采用的实现 |
|---|---|
| Android 应用 | Java、Kotlin 2.3.21、Android 原生 View 和 Jetpack Compose。构建配置为 minSdk 28、targetSdk 36、compileSdk 37，本版原生 Rewards 流程入口要求 API 34 及以上。 |
| 设备端识别 | Shizuku 13.1.5 启动受控读取器，使用 Android UiAutomation 获取活动窗口结构。需要卡片图像时，只采集完整卡片的局部区域，MaaFramework 经 JNA 5.18.1 在设备上运行 OCR。 |
| 原生与构建 | C++17 原生桥接、CMake 3.22.1、JDK 17、Gradle 9.4.1、Android Gradle Plugin 9.2.1。雷电基线使用 MaaFramework v5.15.0 的 x86_64 原生库。 |
| 测试与验收 | JUnit 4 单测覆盖预算、来源校验、资源释放等分支。Android lint 做静态检查；模拟器中的独立入口验证实际读取、进程退出和设备端收据。 |

## 项目结构

| 位置 | 维护内容 |
|---|---|
| [capture-review/](capture-review/) | Rewards APK 的构建模块，含专用清单、包名后缀和 `captureReviewDebug` 变体。其构建脚本指定从 `app` 共享哪些源码与测试。 |
| [app/…/once/](app/src/main/java/com/aliothmoon/maafw/once/) | 原生运行入口、授权、来源核对、界面读取、卡片局部 OCR、输入和清理收据。 |
| [app/…/readonly/](app/src/main/java/com/aliothmoon/maafw/readonly/) · [app/…/rewards/](app/src/main/java/com/aliothmoon/maafw/rewards/) | 前者提供只读状态界面，后者包含卡片区域、边界和任务语义识别规则。 |
| [RewardsCore](app/src/main/java/io/github/klwb360/rewards/core/RewardsCore.java) | 独立的 Java 任务、日期和状态持久化逻辑。当前安卓整轮执行由 `LiveRewardsRuntime` 与 `LiveRewardsCoordinator` 驱动。 |
| [app/src/main/native/](app/src/main/native/) · [scripts/](scripts/) | Android 原生桥接和 MaaFramework 等构建资源的准备脚本。 |
| [app/src/test/](app/src/test/) · [experiments/offline-reading-v3/](experiments/offline-reading-v3/) | 共享单元测试与独立的阅读实验。阅读实验在 Android 构建之外运行，合成断言只验证实验逻辑。 |
| [build-logic/](build-logic/) · [gradle/](gradle/) | Android 构建约定、PI 资源打包、依赖版本与 Wrapper。`hidden-api`、`annotation-api`、`ksp-processor`、`semi-icons` 提供共用组件；`macrobenchmark`、`testapp` 用于专项测试。 |

### 从哪里跟踪一轮执行

- **更多活动的只读二次复查**从 `LiveMoreUiRecheck` 进入 `LiveRewardsPerception` 的生产读取路径。排查短预算、进程退出或 EOF 时，从 `AndroidLiveUiReader` 和 `LiveUiReadStream` 入手。[只读入口](app/src/main/java/com/aliothmoon/maafw/once/LiveMoreUiRecheck.java) · [界面读取器](app/src/main/java/com/aliothmoon/maafw/once/AndroidLiveUiReader.java)
- **V54 基础任务入口**经单独的可见确认后按签入、每日活动、更多活动执行。来源通过后，按区域进度、同卡状态和当日账本跳过已完成或已尝试项。签入在原页复查；活动卡点击成功后静置至少五秒，再返回一次。返回后重新读取并按任务身份匹配，避免列表重排引起重复操作。搜索、阅读和七日进度的点击不在这条流程内。[协调器](app/src/main/java/com/aliothmoon/maafw/once/LiveRewardsCoordinator.java) · [当日账本](app/src/main/java/com/aliothmoon/maafw/once/LiveRewardsLedger.java)

本项目基于 MaaFwApp，使用 MaaFramework 和 Shizuku。上游通用功能、致谢和资源开发说明单独保留。[MaaFwApp 上游说明](MAAFWAPP.md) · [AGPL-3.0 许可证](LICENSE)
