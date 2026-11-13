# 离线 Reading v3 实验

本目录是独立 Java 合成实验，不属于 Android Gradle 源集、APK 或生产功能。它探索账本状态、持久化和事务残留分类。它没有生产授权来源，也不能授权实际任务、积分结算、迁移、恢复、清理或自动重试。

此前保留的 19 个源码文件曾在隔离的 JDK 17 环境通过 6 组、共 1,734 个显式合成断言。这个结果不是 Android 构建或设备验收，也不证明实际断电恢复、跨进程锁、Android 存储持久性或可信时间。当前整理过程中没有重新执行。

虽然 OfflineReadingV3Store 对其合成目录有约束，其他依赖类能够写入调用者提供的路径。测试通过反射构造实验授权对象，不能作为生产授权。运行前请审查源码和测试，不要对真实账户、账本或设备数据运行。

## 独立验证示例（Windows PowerShell）

以下是与此前保存的参数等价的示例，假设 JAVA_HOME 指向 JDK 17。测试会在新建临时目录写入合成文件。其他机器及环境未在此次交接中验证。使用受控的干净子进程环境，避免注入 Java 选项或无关类路径。

    $ErrorActionPreference = 'Stop'
    $src = (Resolve-Path -LiteralPath '.\src').Path
    $files = @(Get-ChildItem -LiteralPath $src -Recurse -File -Filter '*.java' | Sort-Object FullName | ForEach-Object FullName)
    if ($files.Count -ne 19) { throw 'Expected exactly 19 source files' }
    $run = Join-Path ([IO.Path]::GetTempPath()) ('RewardsOfflineReading-' + [guid]::NewGuid().ToString('N'))
    $tmp = Join-Path $run 'tmp'
    $empty = Join-Path $run 'empty'
    $classes = Join-Path $run 'classes'
    New-Item -ItemType Directory -Path $tmp,$empty,$classes | Out-Null
    & "$env:JAVA_HOME\bin\javac.exe" '-J-XX:-UsePerfData' "-J-Duser.home=$tmp" "-J-Djava.io.tmpdir=$tmp" -proc:none --release 17 -encoding UTF-8 -sourcepath $empty -classpath $empty -d $classes @files
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    $suites = @('NormalReadingLedgerTest', 'NormalReadingDeadlineTest', 'ReadingReceiptLifecycleTest', 'OfflineReadingV3EnvelopeTest', 'OfflineReadingV3StoreTest', 'OfflineReadingV3InspectionTest')
    foreach ($suite in $suites) {
        & "$env:JAVA_HOME\bin\java.exe" '-XX:-UsePerfData' "-Duser.home=$tmp" "-Djava.io.tmpdir=$tmp" -ea -cp $classes "com.aliothmoon.maafw.readingtrust.$suite"
        if ($LASTEXITCODE -ne 0) { throw "$suite failed" }
    }

每一组此前记录的断言数依次为 52、221、339、44、669、409。新环境需要自己检查结果；不能据历史记录宣称新运行通过。