package com.aliothmoon.maafw.readonly

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

/** Safe scalar-only status from our private synthetic worker. Never real account observations. */
data class SyntheticDemoState(
    val phase: String = "CONNECTING",
    val permission: String = "SYNTHETIC_ONLY",
    val recovery: String = "No automatic replay",
    val page: String = "UNKNOWN",
    val flow: String = "NO_RESULT",
    val cleanup: String = "NOT_STARTED",
    val origin: String = "GENERATED_FIXTURE",
    val attempt: Int = 0,
    val requestId: Int = 0,
    val workerUid: Int = -1,
    val canStart: Boolean = false,
    val canCancel: Boolean = false,
    val canArchive: Boolean = false,
    val canTerminate: Boolean = false,
    val archivedCount: Int = 0,
    val lastArchived: Int = 0,
    val ocrCounter: String = "NOT_OBSERVED",
    val evidence: String = "NOT_OBSERVED",
    val workerBoundary: String = "NOT_CONNECTED",
    val realBinding: String = "CONNECTED_DEFAULT_OFF",
    val realMissing: String = "CAPABILITY_NOT_CONNECTED",
    val materialSummary: String = "NOT_OBSERVED",
    val materialTargets: List<Int> = emptyList(),

)

/** Main-thread Messenger boundary. Binding never starts OCR; only an explicit fixture tap does. */
internal class SyntheticDemoClient(context: Context, private val publish: (SyntheticDemoState) -> Unit) {
    private val app = context.applicationContext
    private var generation = 0
    private var closed = false
    private var bound = false
    private var remote: Messenger? = null
    private var callback: Messenger? = null
    private var connection: ServiceConnection? = null
    private var state = SyntheticDemoState()
    private var pendingStart = false
    private var currentRequestId = 0
    private var cancelLatched = false

    fun connect() {
        if (closed || connection != null) return
        val epoch = ++generation
        val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (!closed && epoch == generation && message.what == 10) {
                try {
                    val data = message.data
                    fun safe(key: String, fallback: String) = data.getString(key)?.take(160) ?: fallback
                    val next = SyntheticDemoState(
                        phase = safe("phase", "UNKNOWN"), permission = safe("permission", "SYNTHETIC_ONLY"),
                        recovery = safe("recovery", "No automatic replay"), page = safe("page", "UNKNOWN"),
                        flow = safe("flow", "NO_RESULT"), cleanup = safe("cleanup", "UNCONFIRMED"),
                        origin = safe("origin", "GENERATED_FIXTURE"), attempt = data.getInt("attempt", 0),
                        requestId = data.getInt("requestId", 0),
                        workerUid = data.getInt("workerUid", -1),
                        canStart = data.getBoolean("canStart", false) && currentRequestId < Int.MAX_VALUE,
                        canCancel = data.getBoolean("canCancel", false),
                        canArchive = data.getBoolean("canArchive", false),
                        canTerminate = data.getBoolean("canTerminate", false),
                        archivedCount = data.getInt("archivedCount", 0),
                        lastArchived = data.getInt("lastArchived", 0),
                        ocrCounter = safe("ocrCounter", "NOT_OBSERVED"),
                        evidence = data.getString("evidence")?.take(3000) ?: "NOT_OBSERVED",
                        workerBoundary = safe("workerBoundary", "UNKNOWN"),
                        realBinding = safe("realBinding", "CONNECTED_DEFAULT_OFF"),
                        realMissing = safe("realMissing", "CAPABILITY_NOT_CONNECTED"),
                        materialSummary = data.getString("materialSummary")?.take(2400) ?: "NOT_OBSERVED",
                        materialTargets = data.getIntArray("materialTargets")?.filter { it in 1..7 }?.distinct()?.take(7) ?: emptyList(),
                    )
                    // A queued snapshot from before START cannot resurrect a prior page or enable START.
                    // Attempt ordering alone is insufficient while reservation has not assigned one.
                    if ((currentRequestId == 0 || next.requestId == currentRequestId) &&
                        next.attempt >= state.attempt) {
                        if (next.attempt > state.attempt || next.canStart) pendingStart = false
                        if (data.getBoolean("endAcknowledged", false)) {
                            // Acknowledgement is request/durable-metadata evidence only.
                            // Explicit unbind prevents BIND_AUTO_CREATE replacing the worker.
                            ++generation
                            remote = null
                            callback = null
                            releaseBinding()
                            update(next.copy(phase = "INTERRUPTED", page = "UNKNOWN", flow = "NO_RESULT",
                                cleanup = "UNCONFIRMED", recovery = "END_REQUEST_ACKNOWLEDGED_NOT_CLEANUP",
                                canStart = false, canCancel = false, canArchive = false, canTerminate = false,
                                evidence = "WORKER_END_REQUESTED_OLD_CLEANUP_UNKNOWN"))
                        } else update(if (cancelLatched) cancelledPresentation(next) else next)
                    }
                } catch (_: RuntimeException) { lost() }
            }
            true
        })
        callback = receiver
        val link = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (closed || epoch != generation) return
                remote = Messenger(binder)
                send(1)
            }
            override fun onServiceDisconnected(name: ComponentName) { if (epoch == generation) lost() }
            override fun onBindingDied(name: ComponentName) { if (epoch == generation) lost() }
            override fun onNullBinding(name: ComponentName) { if (epoch == generation) lost() }
        }
        connection = link
        try {
            bound = app.bindService(Intent().setClassName(app.packageName,
                "com.aliothmoon.maafw.once.SyntheticDemoService"), link, Context.BIND_AUTO_CREATE)
            if (!bound) lost()
        } catch (_: RuntimeException) { lost() }
    }

    fun start(fixture: Int) {
        if (fixture !in 1..8 || !state.canStart || remote == null || closed ||
            currentRequestId == Int.MAX_VALUE) return
        ++currentRequestId
        cancelLatched = false
        pendingStart = true
        update(state.copy(phase = "START_REQUESTED", page = "UNKNOWN", flow = "NO_RESULT",
            requestId = currentRequestId,
            cleanup = "PENDING", canStart = false, canCancel = true, canArchive = false, evidence = "NOT_OBSERVED"))
        send(2, fixture, currentRequestId)
    }

    fun fakeMaterial(target: Int) {
        if (target !in state.materialTargets || !state.canStart || remote == null || closed ||
            currentRequestId == Int.MAX_VALUE) return
        ++currentRequestId
        cancelLatched = false
        pendingStart = true
        update(state.copy(phase = "START_REQUESTED", page = "UNKNOWN", flow = "NO_RESULT",
            requestId = currentRequestId, cleanup = "PENDING", canStart = false, canCancel = true,
            canArchive = false, evidence = "NOT_OBSERVED"))
        send(7, target, currentRequestId)
    }

    fun terminateWorker() {
        if (!state.canTerminate || closed || remote == null) return
        cancelLatched = false
        update(state.copy(phase = "PROCESS_TERMINATION_REQUESTED", page = "UNKNOWN", flow = "NO_RESULT",
            cleanup = "UNCONFIRMED", canTerminate = false, canStart = false, canCancel = false, canArchive = false))
        send(6, if (pendingStart) 0 else state.attempt, currentRequestId)
    }

    fun archiveInterrupted() {
        if (!state.canArchive || remote == null || closed) return
        cancelLatched = false
        update(state.copy(phase = "ARCHIVING_SYNTHETIC", canStart = false, canArchive = false,
            page = "UNKNOWN", flow = "NO_RESULT"))
        send(5, state.attempt, currentRequestId)
    }

    fun cancel() {
        if (!state.canCancel || closed) return
        cancelLatched = true
        update(state.copy(phase = "STOP_REQUESTED", page = "UNKNOWN", flow = "NO_RESULT", canStart = false, canCancel = false))
        send(3, if (pendingStart) 0 else state.attempt, currentRequestId)
    }

    private fun send(what: Int, argument: Int = 0, requestId: Int = 0) {
        try {
            val target = remote ?: return
            target.send(Message.obtain(null, what, argument, requestId).apply { replyTo = this@SyntheticDemoClient.callback })
        } catch (_: android.os.RemoteException) { lost() }
    }

    // Cancellation is a local presentation barrier, even if completion was already queued.
    // Cleanup remains the worker's actual report; cancellation never implies confirmed disposal.
    private fun cancelledPresentation(next: SyntheticDemoState): SyntheticDemoState {
        val failed = next.phase in setOf(
            "FAILED", "CLEANUP_UNCONFIRMED", "JOURNAL_OR_PIPELINE_FAILURE", "RECOVERY_BLOCKED",
        )
        val safelyFinished = next.phase in setOf("COMPLETED", "RESULT_DISCARDED", "CANCELLED")
        return next.copy(
            phase = when {
                failed -> next.phase
                safelyFinished -> "CANCELLED"
                else -> next.phase
            },
            page = "UNKNOWN", flow = "NO_RESULT",
            canStart = (failed || safelyFinished) && next.canStart, canCancel = false,
        )
    }

    private fun update(next: SyntheticDemoState) {
        val safe = if (next.phase == "COMPLETED" && next.cleanup == "CONFIRMED" &&
            next.page == "REWARDS_HOME" && next.origin == "SYNTHETIC" && !cancelLatched) next
            else next.copy(ocrCounter = "NOT_OBSERVED")
        val materialSafe = if (safe.phase == "COMPLETED" && safe.cleanup == "CONFIRMED" &&
            safe.origin == "SYNTHETIC" && !cancelLatched) safe
            else safe.copy(materialSummary = "NOT_OBSERVED", materialTargets = emptyList())
        state = materialSafe
        publish(materialSafe)
    }

    private fun lost() {
        ++generation // Invalidates queued callbacks before dropping the connection.
        cancelLatched = false
        remote = null
        callback = null
        releaseBinding()
        update(state.copy(phase = "INTERRUPTED", page = "UNKNOWN", flow = "NO_RESULT",
            cleanup = "UNCONFIRMED", recovery = "WORKER_DISCONNECTED_EXPLICIT_RECONNECT_NO_REPLAY",
            workerUid = -1, canStart = false, canCancel = false, canArchive = false, canTerminate = false, evidence = "UNKNOWN_AFTER_DISCONNECT"))
    }

    private fun releaseBinding() {
        val link = connection
        connection = null
        if (bound && link != null) {
            bound = false
            try { app.unbindService(link) } catch (_: IllegalArgumentException) { /* Already detached. */ }
        }
    }

    fun close() {
        if (closed) return
        // UNREGISTER must also stop a request whose START acknowledgement is still in flight.
        send(3, if (pendingStart) 0 else state.attempt, currentRequestId)
        send(4)
        closed = true
        cancelLatched = false
        update(state.copy(page = "UNKNOWN", flow = "NO_RESULT", canStart = false, canCancel = false, canArchive = false,
            materialSummary = "NOT_OBSERVED", materialTargets = emptyList()))
        ++generation
        remote = null
        callback = null
        releaseBinding()
    }
}
