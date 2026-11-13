package com.aliothmoon.maafw.readonly

import com.aliothmoon.maafw.rewards.RewardsPlanning
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Immutable UI state; deliberately independent of the privileged MaaFwApp graph.
data class ReadOnlyStatusUiState(
    val admission: ReadOnlyRecognition.Admission,
    val recognition: ReadOnlyRecognition.State,
    val backend: com.aliothmoon.maafw.once.OnceBackend.Result,
    val detailsExpanded: Boolean = false,
    val demo: SyntheticDemoState = SyntheticDemoState(),
    val business: BusinessUiState = BusinessUiState(),
)

class ReadOnlyStatusViewModel : ViewModel() {
    private val controller = UnboundReadOnlyController(Build.VERSION.SDK_INT, Build.HARDWARE, Build.FINGERPRINT)
    private val mutableState = MutableStateFlow(ReadOnlyStatusUiState(controller.admission(), controller.snapshot(), controller.backendSnapshot()))
    val uiState = mutableState.asStateFlow()
    private var client: SyntheticDemoClient? = null
    private val business = RewardsPlanning.Session()
    private var epoch = 1L
    fun connect(context: Context) {
        if (client != null) return
        client = SyntheticDemoClient(context.applicationContext) { demo ->
                        business.observe(if (demo.workerUid == android.os.Process.myUid()) RewardsPlanning.accept(epoch, demo.attempt, demo.requestId, demo.phase,
                demo.cleanup, demo.origin, demo.workerUid, demo.page) else null)
            mutableState.update { it.copy(demo = demo, business = business.snapshot()) }
        }.also { it.connect() }
    }
        private fun invalidateBusiness() {
        business.invalidate()
        mutableState.update { it.copy(business = business.snapshot()) }
    }
    fun startFixture(fixture: Int) {
        if (!mutableState.value.demo.canStart) return
        invalidateBusiness()
        client?.start(fixture)
    }
    fun selectTask(task: RewardsPlanning.Task) { business.select(task, business.scenario()); publishBusiness() }
    fun fakeMaterial(target: Int) { invalidateBusiness(); client?.fakeMaterial(target) }
    fun selectScenario(scenario: RewardsPlanning.Scenario) { business.select(business.task(), scenario); publishBusiness() }
    fun preparePlan() { business.prepare(); publishBusiness() }
    fun fakePreview() {
        try { business.previewExplicitly() } catch (_: IllegalStateException) { business.invalidate() }
        publishBusiness()
    }
    private fun publishBusiness() { mutableState.update { it.copy(business = business.snapshot()) } }
    fun reconnect(context: Context) {
        ++epoch
        invalidateBusiness()
        client?.close()
        client = null
        connect(context)
    }
    fun terminateWorker() { invalidateBusiness(); client?.terminateWorker() }
    fun archiveInterrupted() { invalidateBusiness(); client?.archiveInterrupted() }
    fun cancelDemo() { invalidateBusiness(); client?.cancel() }
    fun toggleDetails() { mutableState.update { it.copy(detailsExpanded = !it.detailsExpanded) } }
    override fun onCleared() {
        client?.close()
        client = null
        super.onCleared()
    }
    // No real-screen start/approve/reset/permission Intent is exposed. Recreation never runs work.
}
