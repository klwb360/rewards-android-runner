package com.aliothmoon.maafw.readonly
import org.junit.Assert.*
import org.junit.Test
class CapturePolicyTextTest {
 @Test fun disabledBuildDoesNotClaimEnabledCapture(){
  assertTrue(com.aliothmoon.maafw.once.CaptureReleaseGate.configured())
  val text=capturePolicyDescription(false)
  assertTrue(text.contains("真实采集开关关闭"));assertTrue(text.contains("投屏服务禁用"));assertFalse(text.contains("已启用"))
 }
 @Test fun enabledDescriptionStillRequiresExplicitAuthorization(){
  val text=capturePolicyDescription(true);assertTrue(text.contains("当前明确授权"));assertTrue(text.contains("不可重置或重放"))
 }
}
