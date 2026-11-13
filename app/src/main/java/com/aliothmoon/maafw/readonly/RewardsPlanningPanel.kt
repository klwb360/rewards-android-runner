package com.aliothmoon.maafw.readonly

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.rewards.RewardsPlanning

data class BusinessUiState(
    val task: RewardsPlanning.Task = RewardsPlanning.Task.ACTIVITIES,
    val scenario: RewardsPlanning.Scenario = RewardsPlanning.Scenario.MISSING,
    val evidence: RewardsPlanning.Evidence? = null,
    val plan: RewardsPlanning.Plan? = null,
    val preview: RewardsPlanning.Preview? = null,
)
internal fun RewardsPlanning.Session.snapshot() = BusinessUiState(task(), scenario(), evidence(), plan(), preview())
private fun taskLabel(task: RewardsPlanning.Task) = when(task) {
    RewardsPlanning.Task.ACTIVITIES -> "活动"
    RewardsPlanning.Task.SEARCH -> "搜索"
    RewardsPlanning.Task.READING -> "阅读"
}
private fun scenarioLabel(scenario: RewardsPlanning.Scenario) = when(scenario) {
    RewardsPlanning.Scenario.READY -> "合成0/2"
    RewardsPlanning.Scenario.COMPLETE -> "样例已满"
    RewardsPlanning.Scenario.MISSING -> "缺计数"
    RewardsPlanning.Scenario.AMBIGUOUS -> "歧义"
    RewardsPlanning.Scenario.PENDING -> "待核验"
    RewardsPlanning.Scenario.NO_CREDIT -> "无增长"
    RewardsPlanning.Scenario.COUNTER_CONFLICT -> "计数冲突"
    RewardsPlanning.Scenario.CARD_CHANGE -> "选中卡变化"
    RewardsPlanning.Scenario.CARD_UNCHANGED -> "卡片未变"
    RewardsPlanning.Scenario.OTHER_CARD_CHANGE -> "他卡增长"
    RewardsPlanning.Scenario.SCOPE_CHANGE -> "分区变化"
}
private fun actionLabel(action: RewardsPlanning.Action) = when(action) {
    RewardsPlanning.Action.WOULD_OPEN_DAILY_SET -> "拟打开每日活动入口"
    RewardsPlanning.Action.WOULD_SELECT_UNIQUE_CARD -> "拟选择唯一卡片；卡片身份尚未观察"
    RewardsPlanning.Action.WOULD_OPEN_SEARCH -> "拟打开搜索入口"
    RewardsPlanning.Action.WOULD_ENTER_SAMPLE_QUERY -> "拟输入固定合成查询（不会输入）"
    RewardsPlanning.Action.WOULD_SUBMIT_SAMPLE_QUERY -> "拟提交样例查询（不会提交）"
    RewardsPlanning.Action.WOULD_OPEN_READING_FEED -> "拟打开阅读列表"
    RewardsPlanning.Action.WOULD_SELECT_UNIQUE_ARTICLE -> "拟选择唯一文章；正文尚未观察"
    RewardsPlanning.Action.WOULD_WAIT_FOR_CONTENT -> "需要正文证据；停留时间不能证明积分"
    RewardsPlanning.Action.REQUIRE_NEW_TASK_COUNTER -> "之后必须重新观察同一任务计数；当前结果未证实"
    RewardsPlanning.Action.REQUIRE_SAME_CARD_SCOPE -> "活动还需相同卡片集合与选中卡片的归因核验"
}
@Composable
fun RewardsPlanningPanel(state: BusinessUiState, onTask: (RewardsPlanning.Task) -> Unit,
    onScenario: (RewardsPlanning.Scenario) -> Unit, onPrepare: () -> Unit, onPreview: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("业务准备 · 仅合成", style = MaterialTheme.typography.titleMedium)
        Text("真实账户积分 / 任务进度：未知。OCR 只识别页面，不提供下方样例计数。", style = MaterialTheme.typography.bodySmall)
        Text("示例顺序：活动 → 搜索 → 阅读。仅手动选一项，不自动串联。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RewardsPlanning.Task.entries.forEach { task ->
                MaaOutlinedButton(onClick = { onTask(task) }) { Text(taskLabel(task)) }
            }
        }
        Text("样例：${taskLabel(state.task)} / ${scenarioLabel(state.scenario)} · 计数来源：SYNTHETIC_SEMANTIC（非 OCR）", style = MaterialTheme.typography.bodySmall)
        RewardsPlanning.Scenario.entries.chunked(3).forEach { group ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                group.forEach { scenario -> MaaOutlinedButton(onClick = { onScenario(scenario) }) { Text(scenarioLabel(scenario)) } }
            }
        }
        Text("Business evidence: ${state.evidence?.let { "${it.page()} / attempt ${it.attempt()} / request ${it.requestId()}" } ?: "NONE"}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MaaButton(onClick = onPrepare, enabled = state.evidence != null) { Text("生成合成计划") }
            MaaButton(onClick = onPreview, enabled = state.plan?.previewable() == true && state.preview == null) { Text("预览假动作") }
        }
        Text("Plan: ${state.plan?.decision() ?: "NONE"} · ${state.plan?.reason() ?: "等待本次清理已确认的原生识别"}", style = MaterialTheme.typography.bodySmall)
        state.plan?.semantic()?.let { semantic ->
            val parsed = semantic.before()
            Text("Semantic: ${parsed?.confidence() ?: "NONE"} · ${parsed?.reason() ?: semantic.explanation()}", style = MaterialTheme.typography.bodySmall)
            val count = parsed?.counter()?.counter()
            Text("Parsed counter: ${count?.let { "${it.current()}/${it.target()}" } ?: "UNKNOWN"} · scope: ${parsed?.scope() ?: "NONE"} · cards: ${parsed?.cardCount() ?: 0}", style = MaterialTheme.typography.bodySmall)
            Text("Provenance: ${parsed?.provenance()?.let { "${it.source()} owner ${it.ownerUid()} epoch ${it.epoch()} attempt ${it.attempt()} request ${it.requestId()} frame ${it.frame()}" } ?: "NONE"}", style = MaterialTheme.typography.bodySmall)
            Text("Attribution: ${semantic.attribution()} · ${semantic.explanation()}", style = MaterialTheme.typography.bodySmall)
            Text("卡片变化只解释两份显式合成语义帧，未观察真实任务，也不是假动作到账。真实来源仍关闭。", style = MaterialTheme.typography.bodySmall)
        }
        state.plan?.actions()?.forEachIndexed { index, action -> Text("${index+1}. ${actionLabel(action)}", style = MaterialTheme.typography.bodySmall) }
        if (state.plan?.decision() == RewardsPlanning.Decision.SAMPLE_COMPLETE)
            Text("只是固定样例的计数已满，不代表真实任务完成。", style = MaterialTheme.typography.bodySmall)
        if (state.plan?.decision() == RewardsPlanning.Decision.MANUAL_SIGN_IN)
            Text("需人工处理登录；本程序不会打开登录页或传递账号。", style = MaterialTheme.typography.bodySmall)
        Text("Preview: ${state.preview?.outcome() ?: "NONE"} · Executed: 0 · Account credit: UNKNOWN", style = MaterialTheme.typography.bodySmall)
        if (state.preview != null) Text("假动作预览已生成：未执行点击、输入、等待或网络请求，未写任务完成。不会自动继续，需新的真实任务证据。", style = MaterialTheme.typography.bodySmall)
    }
}

