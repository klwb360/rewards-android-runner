package com.aliothmoon.maafw.readonly

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aliothmoon.maafw.theme.MaaFwTheme

/** Only reachable Activity. Intent extras/deep links are intentionally ignored. */
class ReadOnlyStatusActivity : ComponentActivity() {
    private val handoff = com.aliothmoon.maafw.once.OwnTaskHandoff(this)
    private var navigationUsed = false
    var navigationStatus by androidx.compose.runtime.mutableStateOf("尚未准备页面；目标就绪未证实。")
        private set
    fun preparePageWithoutCapture() {
        if (navigationUsed) return
        navigationUsed = true
        try {
            if (application is com.aliothmoon.maafw.once.ReviewVisibilityApplication) {
                com.aliothmoon.maafw.once.DormantCaptureConfirmationActivity.launch(this)
                return
            }
            handoff.request(
                { navigationStatus = "已观察到本页 onStop；目标就绪仍未证实，没有采集。"; android.util.Log.i("RewardsOwnNavigation", "OWN_STOP_OBSERVED; target=NOT_ESTABLISHED; capture=NONE") },
                { navigationStatus = "本页曾退出可见状态；重新进入已撤销该导航状态。目标就绪仍未证实，没有采集。"; android.util.Log.i("RewardsOwnNavigation", "OWN_REENTRY_REVOKED; target=NOT_ESTABLISHED; capture=NONE") }
            )
        } catch (failure: RuntimeException) {
            navigationStatus = "本次页面准备被拒绝；需要唯一自身根页面及 API34。没有采集或目标就绪证明。"
            android.util.Log.i("RewardsOwnNavigation", "OWN_NAVIGATION_REJECTED; target=NOT_ESTABLISHED; capture=NONE")
        }
    }
    override fun onStart() { super.onStart(); handoff.onStart() }
    override fun onResume() { super.onResume(); handoff.onResume() }
    override fun onPause() { handoff.onPause(); super.onPause() }
    override fun onStop() { super.onStop(); handoff.onStop() }
    override fun onDestroy() { handoff.onDestroy(); super.onDestroy() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaaFwTheme { ReadOnlyStatusRoute() } }
    }
}
