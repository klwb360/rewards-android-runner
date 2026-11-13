package com.aliothmoon.maafw.readonly

import com.aliothmoon.maafw.rewards.RewardsPlanning
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aliothmoon.maafw.ui.components.MaaButton
import androidx.compose.material3.MaterialTheme
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aliothmoon.maafw.R

@Composable
fun ReadOnlyStatusRoute(model: ReadOnlyStatusViewModel = viewModel()) {
    val state by model.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val app = context.applicationContext
    LaunchedEffect(model) { model.connect(app) }
    ReadOnlyStatusScreen(state, model::toggleDetails, model::startFixture, model::cancelDemo, model::archiveInterrupted, model::terminateWorker, { model.reconnect(app) }, model::selectTask, model::selectScenario, model::preparePlan, model::fakePreview, { (context as? android.app.Activity)?.let { com.aliothmoon.maafw.once.DormantCaptureConfirmationActivity.launch(it) } }, model::fakeMaterial)
}

private fun recoveryMessage(demo: SyntheticDemoState): String = when {
    demo.phase == "PROCESS_TERMINATION_REQUESTED" -> "已请求结束本应用的合成工作进程。终止不是清理证明；断开后请点重新连接，再查看归档选项。"
    demo.phase == "TERMINATION_RECORD_FAILED" -> "无法写入结束请求记录，尚未确认进程结束。未清除任何任务历史。可以重试结束，或继续等待；不要清除数据。"
    demo.phase == "SYNTHETIC_LIMIT_REACHED" -> "合成尝试已达到 20,000 次上限，旧记录保留，不能再开始。"
    demo.phase == "STOP_REQUESTED" -> "已请求停止。请求不等于结束，正在等当前操作返回。"
    demo.phase == "WAITING_OWNER_RETURN" -> "正在等待原操作实际返回并清理。此时不能开始新尝试；等待时间不能证明清理成功。"
    demo.phase == "RECOVERY_BLOCKED" && demo.canArchive -> "上次合成尝试中断，清理无法证实。旧工作进程已不再持有专用锁，但这不是驱动清理证明。可保留未证实记录并归档，再单独开始新演示。"
    demo.phase == "ARCHIVED_SYNTHETIC" || demo.recovery == "ARCHIVED_UNPROVEN" -> "旧尝试已归档，清理仍未证实，次数和历史已保留。尚未运行新任务，请单独选择下方合成页面。"
    demo.cleanup == "CONFIRMED" -> "同一任务的清理操作与内存擦除已确认。仅覆盖本应用拥有的对象，不代表系统或驱动零留存。"
    demo.phase in setOf("INTERRUPTED", "JOURNAL_UNAVAILABLE", "RECOVERY_BLOCKED", "CLEANUP_UNCONFIRMED", "JOURNAL_OR_PIPELINE_FAILURE") -> "当前状态无法确认，已阻止新任务。不要清除数据。若原操作仍在运行请等待；若原 worker 无法返回，可显式结束本应用的合成工作进程，然后重新连接。真实模式始终不可恢复为可用。"
    demo.phase == "IDLE" -> "没有任务自动运行。每次演示需要明确点击；重新打开不会重放识别结果。"
    else -> "仅处理本应用生成的合成页面，不读取真实屏幕。取消后先等待原任务完成清理。"
}

@Composable
fun ReadOnlyStatusScreen(
    state: ReadOnlyStatusUiState,
    onToggleDetails: () -> Unit,
    onStartFixture: (Int) -> Unit = {},
    onCancel: () -> Unit = {},
    onArchive: () -> Unit = {},
    onTerminate: () -> Unit = {},
    onReconnect: () -> Unit = {},
    onTask: (RewardsPlanning.Task) -> Unit = {},
    onScenario: (RewardsPlanning.Scenario) -> Unit = {},
    onPrepare: () -> Unit = {},
    onPreview: () -> Unit = {},
    onRealCapture: () -> Unit = {},
    onFakeMaterial: (Int) -> Unit = {},
) {
    val demo = state.demo
    var confirmEnd by remember { mutableStateOf(false) }
    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text("结束合成工作进程？") },
        text = { Text("仅结束当前产品自己的识别进程，不影响其他应用。可能留下清理未证实的记录；不会清除数据、恢复旧结果或获得真实权限。随后重新连接；若显示中断未证实记录，再确认归档，最后单独开始新演示。") },
        confirmButton = { MaaButton(onClick = { confirmEnd = false; onTerminate() }) { Text("确认结束合成进程") } },
        dismissButton = { MaaOutlinedButton(onClick = { confirmEnd = false }) { Text("继续等待") } },
    )
    Scaffold { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text("Rewards 原生业务准备", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            if (demo.canTerminate) {
                MaaOutlinedButton(onClick = { confirmEnd = true }) { Text("结束本应用合成工作进程") }
            }
            Text(recoveryMessage(demo), style = MaterialTheme.typography.bodySmall)
            Text("Phase: ${demo.phase} · Attempt: ${demo.attempt}", style = MaterialTheme.typography.bodySmall)
            Text("Page: ${demo.page} · Flow: ${demo.flow}", style = MaterialTheme.typography.bodySmall)
            Text("Cleanup: ${demo.cleanup} · Archived: ${demo.archivedCount} · Last archived: ${demo.lastArchived}", style = MaterialTheme.typography.bodySmall)
            Text("OCR counter: ${demo.ocrCounter}", style = MaterialTheme.typography.bodySmall)
            Text("合成原生 OCR 计数仅描述自生成画面；不是账户进度、点击授权或积分到账。", style = MaterialTheme.typography.bodySmall)
            Text("Recovery: ${demo.recovery}", style = MaterialTheme.typography.bodySmall)
            Text("原生 OCR 材料合成回放", style = MaterialTheme.typography.titleMedium)
            Text("生成裁图 → 原生 OCR 框 → 边界归属 → 显式假操作 → 新图核验。不是上传原图、全屏定位或真实积分。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MaaButton(onClick = { onStartFixture(6) }, enabled = demo.canStart) { Text("合成签到") }
                MaaButton(onClick = { onStartFixture(7) }, enabled = demo.canStart) { Text("合成每日") }
                MaaButton(onClick = { onStartFixture(8) }, enabled = demo.canStart) { Text("合成更多") }
            }
            Text("Material: ${demo.materialSummary}", style = MaterialTheme.typography.bodySmall)
            if (demo.materialTargets.isEmpty() && demo.materialSummary.contains("VISUAL_CIRCLE_PREVIEW_ONLY")) {
                Text("已定位签入圈内数字，下面保留原生识别诊断。日期尚未证实；当前只提供裁图坐标预览，没有签到假操作或真实点击。", style = MaterialTheme.typography.bodySmall)
            }
            demo.materialTargets.forEach { target ->
                MaaOutlinedButton(onClick = { onFakeMaterial(target) }, enabled = demo.canStart) {
                    Text("模拟选项 $target 并核验")
                }
            }
            Text("假操作只改变生成图；真实执行 0，积分与因果关系未知。没有候选时保持停止，不放宽识别守卫。", style = MaterialTheme.typography.bodySmall)
            if (demo.canArchive || demo.phase == "ARCHIVING_SYNTHETIC") {
                MaaOutlinedButton(onClick = onArchive, enabled = demo.canArchive, modifier = Modifier.fillMaxWidth()) {
                    Text("保留未证实记录并归档")
                }
                Text("归档只解开合成演示的阻塞，不确认旧清理、不重置次数、不启动任务，也不授予真实截图权限。", style = MaterialTheme.typography.bodySmall)
            }
            if (demo.phase == "INTERRUPTED") {
                MaaOutlinedButton(onClick = onReconnect) { Text("重新连接（不重放）") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MaaButton(onClick = { onStartFixture(1) }, enabled = demo.canStart) { Text("Home") }
                MaaButton(onClick = { onStartFixture(2) }, enabled = demo.canStart) { Text("Sign-in") }
                MaaButton(onClick = { onStartFixture(3) }, enabled = demo.canStart) { Text("Unknown") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MaaButton(onClick = { onStartFixture(4) }, enabled = demo.canStart) { Text("Blank") }
                MaaButton(onClick = { onStartFixture(5) }, enabled = demo.canStart) { Text("Cancel-window") }
                MaaOutlinedButton(onClick = onCancel, enabled = demo.canCancel) { Text("Cancel") }
            }
            Text(when(demo.flow) {
                "MANUAL_REVIEW_DAILY_SET" -> "演示下一步：由你在真实应用中手动检查每日任务。本程序未执行点击，也未赚取积分。"
                "MANUAL_SIGN_IN" -> "演示下一步：由你自行决定是否前往真实应用登录。这里不输入或保存账号信息。"
                "STOP_UNKNOWN" -> "未能明确识别页面，停止并等待人工判断。"
                else -> "没有可用页面结果；归档和重启均不会恢复旧结果。"
            }, style = MaterialTheme.typography.bodySmall)
            RewardsPlanningPanel(state.business, onTask, onScenario, onPrepare, onPreview)
            Text("Permission: ${demo.permission}", style = MaterialTheme.typography.bodySmall)
            Text("Origin: ${demo.origin} · Worker UID: ${if (demo.workerUid < 0) "unbound" else demo.workerUid.toString()}", style = MaterialTheme.typography.bodySmall)
            Text("Worker boundary: ${demo.workerBoundary}", style = MaterialTheme.typography.bodySmall)
            MaaOutlinedButton(onClick = onToggleDetails) { Text(if(state.detailsExpanded) "收起清理证据" else "查看清理证据") }
            if (state.detailsExpanded) {
                Text("证据来自当前 owner 的真实操作返回，不是按钮、计时器、Binder 断开或进程死亡推断。NOT_INVOKED=未调用；WAITING=尚待返回；CONFIRMED=该操作已返回；UNKNOWN=无法证实。", style = MaterialTheme.typography.bodySmall)
                Text("Evidence: ${demo.evidence}", style = MaterialTheme.typography.bodySmall)
                Text("历史归档始终是未证实，不会变成新任务的清理证据。无观察者回调、预览或 GPU 路径；不证明驱动或系统缓存零留存。", style = MaterialTheme.typography.bodySmall)
            }
            val navigationContext = LocalContext.current
            Text((navigationContext as? ReadOnlyStatusActivity)?.navigationStatus ?: "目标就绪未证实。", style = MaterialTheme.typography.bodySmall)
            MaaOutlinedButton(onClick = { (navigationContext as? ReadOnlyStatusActivity)?.preparePageWithoutCapture() }) { Text("打开受控确认页（尚不采集）") }
            Text(if (com.aliothmoon.maafw.once.CaptureReleaseGate.configured())
                "本受控版会打开唯一自身根确认页；此按钮尚不采集。下一页需明确的一次整屏确认和 Android 系统投屏许可，不证明 Bing 页面已就绪。"
                else "打开非采集确认页，仅检查自身任务导航和生命周期。真实采集保持关闭，不会请求 Android 系统投屏许可。", style = MaterialTheme.typography.bodySmall)
            MaaButton(onClick = onRealCapture, enabled = com.aliothmoon.maafw.once.CaptureReleaseGate.configured() && android.os.Build.VERSION.SDK_INT >= 34, modifier = Modifier.fillMaxWidth()) { Text(if (com.aliothmoon.maafw.once.CaptureReleaseGate.configured()) "本次整屏只读识别" else "真实屏幕识别未开放") }
            Text("Synthetic binding metadata: ${demo.realBinding}", style = MaterialTheme.typography.bodySmall)
            Text("Missing: ${demo.realMissing}", style = MaterialTheme.typography.bodySmall)
            Text(capturePolicyDescription(com.aliothmoon.maafw.once.CaptureReleaseGate.configured()), style = MaterialTheme.typography.bodySmall)
        }
    }
}
@StringRes
internal fun phaseLabel(phase: ReadOnlyRecognition.Phase): Int = when (phase) {
    ReadOnlyRecognition.Phase.IDLE -> R.string.ro_idle
    ReadOnlyRecognition.Phase.RUNNING -> R.string.ro_running
    ReadOnlyRecognition.Phase.STOPPING -> R.string.ro_stopping
    ReadOnlyRecognition.Phase.CLEANUP_UNCONFIRMED -> R.string.ro_cleanup_unconfirmed
    ReadOnlyRecognition.Phase.COMPLETED -> R.string.ro_completed
    ReadOnlyRecognition.Phase.FAILED -> R.string.ro_failed
    ReadOnlyRecognition.Phase.CANCELLED -> R.string.ro_cancelled
}

@StringRes
internal fun authorizationLabel(authorization: ReadOnlyRecognition.Authorization): Int = when (authorization) {
    ReadOnlyRecognition.Authorization.NOT_AUTHORIZED -> R.string.ro_not_authorized
    ReadOnlyRecognition.Authorization.APPROVED_ONCE -> R.string.ro_approved_once
    ReadOnlyRecognition.Authorization.CONSUMED -> R.string.ro_consumed
}

/** Display follows the compiled gate; neither branch creates capture authority. */
internal fun capturePolicyDescription(configured: Boolean): String = if (configured)
    "受控一次验收版：真实采集开关已启用，仍需当前明确授权及 Android 系统投屏许可。基线及操作后两个固定持久化槽不可重置或重放；90 秒只请求取消。尚未证明来源身份或业务资格，没有账户执行。"
else
    "离线感知开发版：真实采集开关关闭，投屏服务禁用。仅保留非采集确认页导航；不会弹出系统投屏许可或处理真实屏幕。没有新增真实尝试授权，旧尝试槽不复用，没有账户执行。"
