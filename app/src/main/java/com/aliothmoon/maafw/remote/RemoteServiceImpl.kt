package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.IMaaRunnerCallback
import com.aliothmoon.maafw.ITextInputSink
import com.aliothmoon.maafw.ITouchEventCallback
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.bridge.InputControlUtils
import com.aliothmoon.maafw.bridge.NativeBridgeLib
import com.aliothmoon.maafw.bridge.TextInputDispatcher
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.constant.DisplayMode
import com.aliothmoon.maafw.constant.WakeUnlockResult
import com.aliothmoon.maafw.runner.RunPlanPayload
import com.aliothmoon.maafw.runner.runPlanWireJson
import com.aliothmoon.maafw.maa.MaaFrameworkLoader
import com.aliothmoon.maafw.remote.internal.ActivityUtils
import com.aliothmoon.maafw.remote.internal.AppWatchdog
import com.aliothmoon.maafw.remote.internal.GameFpsMonitor
import com.aliothmoon.maafw.remote.internal.GestureRecorder
import com.aliothmoon.maafw.remote.internal.PermissionGrantHelper
import com.aliothmoon.maafw.service.AccessibilityHelperService
import com.aliothmoon.maafw.remote.internal.PowerController
import com.aliothmoon.maafw.remote.internal.PrimaryDisplayManager
import com.aliothmoon.maafw.remote.internal.ScreenManager
import com.aliothmoon.maafw.remote.internal.StaleFrameGuard
import com.aliothmoon.maafw.constant.PrivilegedGrant
import com.aliothmoon.maafw.log.LogExportCollector
import com.aliothmoon.maafw.remote.internal.MaafwLogRetention
import com.aliothmoon.maafw.remote.internal.VirtualDisplayManager
import com.aliothmoon.maafw.remote.internal.WakeUnlockController
import com.aliothmoon.maafw.remote.internal.XmsfFirewall
import com.aliothmoon.maafw.third.FakeContext
import com.aliothmoon.maafw.third.Ln
import com.aliothmoon.maafw.third.wrappers.ServiceManager
import com.aliothmoon.maafw.third.Workarounds
import android.graphics.Bitmap
import android.view.Surface
import android.os.Process
import android.provider.Settings
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * 特权进程的入口对象：由 Shizuku 或 root starter 反射实例化，实例化即完成进程内初始化
 * 构造函数不能抛：抛了 binder 回不去，app 侧只看得到连接超时
 */
class RemoteServiceImpl : RemoteService.Stub() {

    private val virtualDisplayMode = AtomicInteger(DisplayMode.BACKGROUND)
    private val captureLock = Any()
    private var captureGeneration = 0L
    private val appPid = AtomicInteger(0)
    private val destroyed = AtomicBoolean(false)
    private var piRoot: String? = null

    // 两者互相引用：host 要把 child 的输出交回 runner 的回调。用 lazy 打破初始化顺序——
    // host 的 lambda 到真正有输出时才读 runner，那会儿它早已建好
    private val runner: MaaRunner by lazy { MaaRunner(agentHost, ::stopCapture) }
    private val agentHost: ExecAgentHost by lazy {
        ExecAgentHost(
            onOutput = { line, fromStderr -> runner.onAgentLine(line, fromStderr) },
            onExit = { runner.onAgentExited(it) },
            crashDir = { logDir?.let { File(it, AppFiles.CRASH_DIR) } },
        )
    }

    @Volatile
    private var logDir: String? = null

    init {
        RemoteBootTrace.mark("CTOR_START")
        Workarounds.apply()
        Runtime.getRuntime().addShutdownHook(
            Thread { runCatching(::cleanup) }.apply { name = "remote-shutdown-hook" }
        )
        startHeartbeatWatchdog()
        // 上一实例可能断了 xmsf 的网没来得及恢复（被杀、崩溃），规则在 netd 里不随进程消失；
        // 要跑 shell，挪到后台线程，构造函数不能拖
        // Diagnostic: do not alter network rules.
        RemoteBootTrace.mark("CTOR_DONE")
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        Ln.i("$TAG: destroy()")
        stopDisplayWatchers()
        InputControlUtils.setTouchCallback(null)
        TextInputDispatcher.sink = null
        runner.destroy()
        cleanup()
        exitProcess(0)
    }

    override fun exit() = destroy()

    override fun version(): String = buildString {
        append("bridge=").append(if (NativeBridgeLib.LOADED) NativeBridgeLib.ping() else "not loaded")
        append(" uid=").append(Process.myUid())
        append(" pid=").append(Process.myPid())
        append(" pi=").append(piRoot ?: "unset")
    }

    override fun pid(): Int = Process.myPid()

    override fun watchdogState(): Int = AppWatchdog.state.value

    override fun watchdogTargetPackage(): String = AppWatchdog.targetPackage.orEmpty()

    // ── 亮屏与解锁 ──

    override fun unlock(credential: String?): Int = WakeUnlockResult.UNSUPPORTED
    override fun testUnlock(credential: String?): Int = WakeUnlockResult.UNSUPPORTED
    override fun lockAndSleep(): Int = WakeUnlockResult.UNSUPPORTED
    override fun startGestureRecord(timeoutMs: Int) { }
    override fun pollGestureRecord(): String = GestureRecorder.poll()
    override fun cancelGestureRecord() { }
    override fun unlockWithGesture(gestureJson: String?): Int = WakeUnlockResult.UNSUPPORTED
    override fun testUnlockGesture(gestureJson: String?): Int = WakeUnlockResult.UNSUPPORTED

    override fun isScreenOn(): Boolean =
        runCatching { ServiceManager.getPowerManager().isScreenOn(0) }.getOrDefault(true)

    override fun isSmartResolutionEnabled(): Boolean = try {
        Settings.Global.getInt(FakeContext.get().contentResolver, "low_resolution_switch", 0) == 1
    } catch (e: Exception) {
        Ln.w("$TAG: read low_resolution_switch failed", e)
        false
    }

    override fun setPackageNetworkingEnabled(packageName: String?, enabled: Boolean): Boolean = false
    override fun stopTargetApp(): Boolean = false

    override fun heartbeat(pid: Int) {
        appPid.set(pid)
    }

    override fun setup(piRoot: String?, logDir: String?, isDebug: Boolean): Boolean {
        if (piRoot.isNullOrBlank() || !File(piRoot).isDirectory) {
            Ln.e("$TAG: setup failed - PI root not readable: $piRoot")
            return false
        }
        if (!ReadOnlyResourceGuard.validFiles(piRoot)) return false
        this.piRoot = piRoot
        // agent child 的 cwd 与上游 MaaPiCli 对齐，取 PI 根
        runner.setProjectRoot(piRoot)
        // Android 12 起子进程会被 phantom process killer 收割，接 native 前先关掉
        // agent child 同样吃这条：它是特权进程 fork 出来的，不关就会被一起收走
        // Diagnostic: do not change system process policy.
        // Diagnostic: do not reap other agents.
        // 特权进程是 shell/root 身份，app 建的目录未必可写，这里自己建一遍
        if (!logDir.isNullOrBlank() && ensureWritableDir(logDir)) {
            this.logDir = logDir
            runner.applyGlobalOptions(logDir, isDebug)
        } else {
            Ln.w("$TAG: log dir unusable, MaaFramework will write to process CWD: $logDir")
        }
        // Diagnostic: do not prune existing logs.
        Ln.i("$TAG: setup ok, piRoot=$piRoot")
        return true
    }

    /** 外壳每轮开跑前都会先 setup，借这一下每轮收一次：跑一轮就能滚出上百 MB */
    private fun pruneMaafwBackups() {
        val shellLogDir = logDir?.let(::File)
        val piLogs = piRoot?.let { LogExportCollector.PiLogs(File(it), BuildConfig.MAFW_PI_LOG_INCLUDE.toList()) }
        Thread {
            runCatching { MaafwLogRetention.prune(shellLogDir, piLogs) }
                .onSuccess { if (it > 0) Ln.i("$TAG: pruned $it old maafw log backups") }
                .onFailure { Ln.w("$TAG: prune maafw log backups failed: ${it.message}") }
        }.apply { name = "maafw-log-prune"; isDaemon = true }.start()
    }

    override fun setSaveOnError(enabled: Boolean): Boolean {
        runner.setSaveOnError(false)
        return true
    }

    private fun ensureWritableDir(path: String): Boolean {
        val dir = File(path)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Ln.e("$TAG: mkdirs failed: $path")
            return false
        }
        return dir.canWrite()
    }

    // ── 显示 ──

    override fun setVirtualDisplayMode(mode: Int): Boolean {
        if (mode != DisplayMode.PRIMARY) return false
        virtualDisplayMode.set(DisplayMode.PRIMARY)
        return true
    }
    override fun setVirtualDisplayResolution(width: Int, height: Int, dpi: Int) { }
    override fun startVirtualDisplay(): Int = synchronized(captureLock) {
        if (virtualDisplayMode.get() != DisplayMode.PRIMARY) return@synchronized DefaultDisplayConfig.DISPLAY_NONE
        val display = try {
            PrimaryDisplayManager.start()
        } catch (failure: Throwable) {
            runCatching { PrimaryDisplayManager.stop() }
            throw failure
        }
        val generation = ++captureGeneration
        Thread({
            try { Thread.sleep(60_000) } catch (_: InterruptedException) { return@Thread }
            synchronized(captureLock) {
                if (generation == captureGeneration) {
                    captureGeneration++
                    PrimaryDisplayManager.stop()
                }
            }
        }, "diagnostic-capture-deadline").apply { isDaemon = true; start() }
        display
    }
    override fun stopVirtualDisplay() { stopCapture() }
    private fun stopCapture() = synchronized(captureLock) {
        captureGeneration++
        PrimaryDisplayManager.stop()
    }

    /** 没有虚拟屏时返回 true：调用方据此判断「是否需要拉回」，无屏可拉即无需处理 */
    override fun isAppOnVirtualDisplay(packageName: String): Boolean = true
    override fun moveAppToVirtualDisplay(packageName: String): Boolean = false
    override fun setForceFullscreenOnVirtualDisplay(enabled: Boolean) { }
    override fun setDisplayPower(on: Boolean) { }
    override fun setForcedDisplaySize(width: Int, height: Int): Boolean = false
    override fun clearForcedDisplaySize(): Boolean = false

    // ── 预览 ──

    override fun setMonitorSurface(surface: Surface?) { }
    override fun setTouchCallback(callback: ITouchEventCallback?) { }
    override fun setTextInputSink(sink: ITextInputSink?) { }
    override fun touchDown(x: Int, y: Int, contact: Int) { }
    override fun touchMove(x: Int, y: Int, contact: Int) { }
    override fun touchUp(x: Int, y: Int, contact: Int) { }

    // ── 执行 ──

    override fun setRunnerCallback(callback: IMaaRunnerCallback?) {
        runner.setCallback(callback)
    }

    override fun startRun(runPlanJson: String?): Boolean {
        if (runPlanJson.isNullOrBlank()) {
            stopCapture()
            return false
        }
        val payload = runCatching { runPlanWireJson.decodeFromString<RunPlanPayload>(runPlanJson) }.getOrNull()
            ?: run { stopCapture(); return false }
        val allowed = ReadOnlyResourceGuard.allow(
            piRoot, payload.resourcePaths, payload.tasks.map { it.entry },
            payload.tasks.all { it.pipelineOverrides.isEmpty() }, payload.agents.isEmpty(), payload.displayMode,
        )
        if (!allowed || !payload.proofToken.matches(Regex("[0-9a-f]{64}"))) {
            runCatching { ProofClient.call("cancel", payload.proofToken, null) }
            stopCapture()
            return false
        }
        val started = runner.start(runPlanJson)
        if (!started) {
            runCatching { ProofClient.call("cancel", payload.proofToken, null) }
            stopCapture()
        }
        return started
    }

    override fun stopRun(): Boolean {
        val stopped = runner.stop()
        stopCapture()
        return stopped
    }

    private fun stopDisplayWatchers() {
        AppWatchdog.stopWatching()
        StaleFrameGuard.stop()
    }

    override fun isRunning(): Boolean = runner.isRunning()

    // Diagnostic: frames are only used in memory; no screenshot export.
    override fun saveCachedImage(path: String?): Boolean = false
    override fun saveDisplayFrame(path: String?): Boolean = false

    override fun getGameFps(): Float = GameFpsMonitor.currentFps()

    override fun maaVersion(): String? = MaaFrameworkLoader.library?.MaaVersion()

    /**
     * 逐项独立执行：一项失败不影响其余，返回实际授到的位
     * 失败不抛——app 侧据返回值决定要不要再引导用户手点
     */
    override fun grantPermissions(packageName: String?, uid: Int, permissions: Int): Int = 0

    override fun isPackageInstalled(packageName: String): Boolean = try {
        FakeContext.get().packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        Ln.w("$TAG: isPackageInstalled: $packageName not found", e)
        false
    }

    /**
     * 逐项隔离，不共用一个 runCatching：原先四项串在一个块里，头一项抛了后面全跳过
     *
     * [ScreenManager.destroy] 尤其漏不得——它撤的是**物理主屏**的强改尺寸，
     * 漏掉的话用户会留在一块被改小的屏幕上，而且只能靠再拉一次特权进程才撤得回来。
     * 它自己按 flag 文件判要不要动手，没改过时是空操作
     */
    private fun cleanup() {
        step("primary display") { stopCapture() }
        step("preview") { shutdownPreview() }
    }

    private inline fun step(name: String, action: () -> Unit) {
        runCatching(action).onFailure { Ln.e("$TAG: cleanup $name failed: ${it.message}") }
    }

    /**
     * 退出前断开预览 Surface：SurfaceView 的缓冲队列认不出 producer 进程死了，不断开的话
     * 这块 Surface 会一直算在本进程头上，下一个特权进程 `eglCreateWindowSurface` 报 already connected
     *
     * 排在最后且限时：渲染线程可能正卡在 swap 上，等不到就走，不能拖住前面那几项和进程退出
     */
    private fun shutdownPreview() {
        if (!NativeBridgeLib.LOADED) return
        val worker = Thread { NativeBridgeLib.shutdownPreview() }.apply {
            name = "preview-shutdown"
            isDaemon = true
            start()
        }
        worker.join(PREVIEW_SHUTDOWN_TIMEOUT_MS)
        if (worker.isAlive) Ln.w("$TAG: preview shutdown still running after ${PREVIEW_SHUTDOWN_TIMEOUT_MS}ms, leaving it")
    }

    /**
     * app 进程消失后特权进程必须自杀
     * linkToDeath 是主路径，这里兜住「binder 还没建立就崩了」的窗口
     */
    private fun startHeartbeatWatchdog() {
        Thread {
            while (!destroyed.get()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val pid = appPid.get()
                if (pid <= 0) continue
                if (!File("/proc/$pid").exists()) {
                    Ln.w("$TAG: app process (pid=$pid) gone, destroying remote service")
                    destroy()
                    return@Thread
                }
            }
        }.apply {
            name = "remote-heartbeat-watchdog"
            isDaemon = true
        }.start()
    }

    private companion object {
        const val TAG = "RemoteService"
        const val HEARTBEAT_INTERVAL_MS = 5_000L
        const val PREVIEW_SHUTDOWN_TIMEOUT_MS = 1_000L
    }
}
