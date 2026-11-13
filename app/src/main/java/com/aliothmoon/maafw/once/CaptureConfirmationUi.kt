package com.aliothmoon.maafw.once
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.theme.MaaFwTheme
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
/** First-party disclosure. This UI cannot create authority without a live focused click. */
object CaptureConfirmationUi {
 @JvmStatic fun readOnlyOnly(activity: ComponentActivity, permissionState: String, ready: Boolean, capture: Runnable, cancel: Runnable) {
  activity.setContent { MaaFwTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
   Text("只读双阶段 · 不再点击任务")
   Text("当前Quote已完成。本次在同一新进程分别处理两张新鲜整屏帧，只核当前状态与采集生命周期。没有签到、卡片点击、返回、搜索或输入actor；允许两帧画面完全相同，不宣称新业务因果。")
   Text("第一阶段真实服务销毁及清理确认、结果已封存后，才可批准第二阶段。每帧都要新的Android系统许可和独立一次性槽。UTC18:00至19:30有效，末90秒不接受新审批；整个会话180秒，每帧最多90秒且受会话剩余时间限制。")
   Text("整屏可能含密码、付款信息或通知。仅设备内图像识别，无录音、不保存或外发截图及OCR原文。未知/取消/超时立即停止；不复用旧槽或旧UNKNOWN。")
   Text(permissionState)
   MaaButton(onClick={capture.run()},enabled=ready){Text("允许第一帧：只读当前状态")}
   MaaOutlinedButton(onClick={cancel.run()}){Text("取消")}
  } } }
 }
 @JvmStatic fun readOnlyStatus(activity: ComponentActivity, message: String, terminal: Boolean, second: Runnable?, close: Runnable) {
  activity.setContent { MaaFwTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
   Text("只读双阶段结果 · 0输入")
   Text(message)
   Text("两帧都是当前状态观察，没有新任务动作。硬件阶段BEFORE/AFTER分别映射第一/第二只读服务，不代表业务点击前后。")
   if(second!=null){
    Text("第一阶段已经封存并清理。第二阶段需要新的整屏许可，不输入，不复用前一帧。")
    MaaButton(onClick={second.run()}){Text("允许第二帧：只读当前状态")}
   }
   MaaOutlinedButton(onClick={close.run()}){Text(if(terminal)"关闭结果" else "取消并关闭")}
  } } }
 }

 @JvmStatic fun cardOnly(activity: ComponentActivity, permissionState: String, ready: Boolean, capture: Runnable, cancel: Runnable) {
  activity.setContent { MaaFwTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
   Text("更多活动 Quote · 原生一次测试")
   Text("不再签到。只从更多活动的真实原生帧识别完整卡边界、有效标题旁进度及唯一 Quote of the day 未完成+5。缺少真实进度、标题或本卡证据均停止，不伪造区域进度，不选择相邻问答。已完成、锁定、登录、支付或未知状态均停止或跳过。")
   Text("本次最多两帧、一张卡的一次触摸。点击可能跳转，但不等于任务完成；之后须另行明确批准全新只读帧，以同一任务身份的可靠勾选核验。不会自动返回、重复点击、搜索、兑换、购买或选择提醒。")
   Text("独立scope：UTC17:00至18:00有效，整个会话最长180秒，每帧确认最长90秒，末90秒不接受新审批。整屏可能含其他应用隐私，仅设备内识别，不保存或外发截图及OCR原文。来源、前台、清理或期限不明即停止，旧测试槽不复用。")
   Text(permissionState)
   MaaButton(onClick = { capture.run() }, enabled = ready) { Text("开始新卡测试：原生定位并至多点击一次") }
   MaaOutlinedButton(onClick = { cancel.run() }) { Text("取消") }
  } } }
 }
 @JvmStatic fun cardStatus(activity: ComponentActivity, message: String, terminal: Boolean, after: Runnable?, close: Runnable) {
  activity.setContent { MaaFwTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
   Text("本次原生任务卡测试")
   Text(message)
   Text("触摸或跳转不是完成证明；只看新鲜原生帧中同一任务是否出现可靠勾选。不核总积分，不自动重试或返回。")
   if (after != null) {
    Text("下一步只读一帧整屏，不再输入。Android会再次询问许可；若目前不在原任务卡页面，应先停止核验，不根据跳转猜完成。")
    MaaButton(onClick = { after.run() }) { Text("允许新一帧：核验任务卡完成态") }
   }
   MaaOutlinedButton(onClick = { close.run() }) { Text(if (terminal) "关闭结果" else "取消并关闭") }
  } } }
 }
 @JvmStatic fun currentOnly(activity: ComponentActivity, permissionState: String, ready: Boolean, capture: Runnable, cancel: Runnable) {
  activity.setContent { MaaFwTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
   Text("只读核验当前签到状态")
   Text("此前已执行一次签到触摸，本次绝不再次点击。只读取全新一帧整屏，在设备内用原生OCR与视觉标记识别签到成功弹层，或核对参考位置现在是否显示勾选；画面可能包含其他应用隐私信息，不主动保存或外发截图或OCR原文。")
   Text("这是一项独立的跨run当前状态观察，不续期先前测试、不复用先前AFTER槽，不推断积分或点击因果。历史邻近勾选位置只作历史参考。")
   Text("本次最多一帧、0输入，确认后最长90秒；UTC16:00至17:00有效，最后90秒不接受新审批。Android会另行询问本次整屏许可。取消、过期或来源/清理不确定均停止。")
   Text(permissionState)
   MaaButton(onClick = { capture.run() }, enabled = ready) { Text("允许新一帧：只读核验当前状态") }
   MaaOutlinedButton(onClick = { cancel.run() }) { Text("取消") }
  } } }
 }
 @JvmStatic fun navigation(activity: ComponentActivity, message: String, prepare: Runnable, close: Runnable) {
  activity.setContent { MaaFwTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
   Text("实际确认页 · 不采集转场验收")
   Text("真实采集持续关闭。这里只验证同任务跨组件根替换与自身页面生命周期；不会弹出系统投屏许可、运行采集服务或处理屏幕。")
   Text(message)
   Text("onStop 不是像素消失确认。epoch 不是授权，不证明目标 App 身份、任务可执行或积分到账。重新进入自身页面永久撤销旧 epoch。")
   MaaButton(onClick = {}, enabled = false) { Text("允许本次整屏处理一帧（已禁用）") }
   MaaOutlinedButton(onClick = { prepare.run() }) { Text("验证退后台（不采集）") }
   MaaOutlinedButton(onClick = { close.run() }) { Text("关闭确认页") }
  } } }
 }
 @JvmStatic fun show(activity: ComponentActivity, baseline: Runnable, after: Runnable, permission: Runnable, native: Runnable, permissionState: String, cancel: Runnable) {
  activity.setContent { MaaFwTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
   Text("选择本次测试")
   Text("旧只读观察：可能包含其他应用和屏幕上的隐私信息。仅处理一帧，授权窗口从本次确认起最长 90 秒；系统投屏可能产生多帧。不保存截图或 OCR 原文，不点击、搜索、登录、领取或操作账户。授权不限于 Bing，不能证明积分到账。Android 还会单独询问系统投屏许可。")
   Text("原产品页已请求关闭，本页须成为唯一自身任务的唯一根页面。系统同意且前台服务就绪后，请求本页退后台，只有本实例 onStop 到达才继续；重新可见立即撤销，不用固定等待。该生命周期证据不是画面消失的像素确认，也不能证明 Bing、目标页或任务已就绪。未能确认目标来源和完整结构时拒绝业务规划及执行。返回本确认任务查看本次分类和清理状态。关闭、重建或超时均取消；本进程无重试。")
   Text("旧只读2026-11-11业务观察：基线与操作后各限一帧，各自不可复用，UTC 15:00 绝对到期。程序不执行账户点击；本回执包含原生签到视觉坐标和状态，不证明到账。仅在已核对的 Bing Rewards 页面测试。")
   Text("原生签到测试（新 scope，与上面的旧只读槽分开）")
   Text("Shizuku 授权是持续的跨应用 root 权限，可能访问或修改模拟器中更多应用及系统数据，权限本身不只允许触摸。此测试包仅用固定前台元数据检查与一次签到触摸；不会授权自动刷积分、搜索、兑换或购买。可在 Shizuku 中撤销持续权限。")
   Text(permissionState)
   MaaOutlinedButton(onClick = { permission.run() }) { Text("申请本测试包 Shizuku root 持续权限") }
   Text("新测试最多两帧：点击前一帧、点击后另行明确批准一帧；每帧审批最长90秒，整个测试最长180秒；本次新授权UTC15:10生效、16:00到期，最后90秒不接受新采集审批。原生OCR只在设备内处理，不保存截图或原文。首帧取得后的保守年龄不超过15秒，且Bing前台窗口、方向和尺寸一致时，程序自动点击一次原生定位的签到圈。依据是新鲜快照，不保证每个像素连续不变。取消、过期、冲突、未知清理或前台改变都会停止。")
   MaaButton(onClick = { native.run() }) { Text("新测试：原生定位并点击一次签到") }
   MaaButton(onClick = { baseline.run() }) { Text("业务基线：允许处理一帧") }
   MaaButton(onClick = { after.run() }) { Text("业务操作后：允许处理一帧") }
   MaaOutlinedButton(onClick = { cancel.run() }) { Text("取消") }
  } } }
 }
 @JvmStatic fun nativeStatus(activity: ComponentActivity, message: String, terminal: Boolean, after: Runnable?, close: Runnable) {
  activity.setContent { MaaFwTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
   Text("本次原生签到测试")
   Text(message)
   Text("触摸命令退出不代表任务完成。只以新鲜操作后帧中的签到成功弹层，或同一面板、原位置的勾选状态核验；不核总积分，不自动重试。")
   if (after != null) {
    Text("允许全新整屏只读帧（可能包含其他应用隐私信息），仅本机原生OCR与状态比较，不保存截图或识别原文，不再点击。Android还会单独询问投屏许可。")
    MaaButton(onClick = { after.run() }) { Text("允许新一帧：核验签到完成态") }
   }
   MaaOutlinedButton(onClick = { close.run() }) { Text(if (terminal) "关闭结果" else "取消并关闭") }
  } } }
 }
 @JvmStatic fun status(activity: ComponentActivity, message: String, terminal: Boolean, close: Runnable) {
  activity.setContent { MaaFwTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
   Text("本次整屏只读识别")
   Text(message)
   Text("仅展示分类及清理元数据，无截图或识别原文；没有账户动作。返回本页不重放识别。关闭或进程死亡不证明系统、驱动或本地资源已清理。")
   MaaOutlinedButton(onClick = { close.run() }) { Text(if (terminal) "关闭结果" else "取消并关闭") }
  } } }
 }
}
