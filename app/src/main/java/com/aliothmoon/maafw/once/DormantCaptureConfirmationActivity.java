package com.aliothmoon.maafw.once;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import androidx.activity.ComponentActivity;
/** Private review component in :capture_once. No Intent or saved-state value is authority. */
public final class DormantCaptureConfirmationActivity extends ComponentActivity {
 private final Object request=new Object();
 private boolean resumed, consumed, destroyed, terminalShown;
 private LiveClick click;
 private OwnTaskHandoff handoff=new OwnTaskHandoff(this);
 private volatile NativeCheckInSession nativeSession;
 private volatile NativeRewardCardRun cardRun;
 private volatile ReadOnlyTwoStageRun readOnlyRun;
 private volatile NativeAutonomousCardRun autonomousRun;
 private volatile RootReturnReadIntent returnReadIntent;
 private volatile UiImageAlignmentRuntime alignmentRuntime;
 private volatile OverviewScanRuntime overviewRuntime;
 private Object overviewOwner;
 private boolean overviewSelected,overviewSpent;
 private volatile boolean overviewFinished;
 private volatile String overviewHistory;
 boolean overviewSelected(){return overviewSelected;}
 boolean overviewApprovalMatches(Object owner,LiveClick incoming){
  return overviewSelected&&overviewSpent&&!overviewFinished&&owner!=null&&owner==overviewOwner
   &&incoming!=null&&incoming==click&&isResumedForApproval();
 }
 private void openOverviewChoice(){
  if(consumed||overviewSpent||alignmentSpent||!isResumedForApproval())return;
  overviewSelected=true;alignmentSelected=false;showOverviewChoice();
 }
 private void showOverviewChoice(){
  if(destroyed||isFinishing()||isDestroyed())return;
  OverviewReviewUi.choice(this,NativeCheckInSession.permissionReady()&&!consumed&&!overviewSpent
    &&isResumedForApproval(),this::confirmOverview,this::cancelAndFinish);
 }
 private void confirmOverview(){
  if(consumed||overviewSpent||!overviewSelected||!isResumedForApproval())return;
  overviewSpent=true;consumed=true;terminalShown=false;overviewOwner=new Object();click=new LiveClick(this);
  terminalSnapshot=new TerminalSnapshot(false,false,"CURRENT_STATUS",false);
  try{
   if(!NativeCheckInSession.permissionReady())throw new SecurityException("Existing permission unavailable");
   OverviewReviewUi.running(this,this::cancelAndFinish);
   overviewRuntime=OverviewScanRuntime.start(this,click,overviewOwner);
  }catch(RuntimeException rejected){
   if(overviewRuntime!=null)overviewRuntime.cancel();if(click!=null)click.invalidate();
   overviewFinished=true;terminalShown=true;terminalSnapshot=new TerminalSnapshot(true,false,"CURRENT_STATUS",false);
   overviewHistory="overview=UNQUALIFIED stage=START_REJECTED taskTaps=0 actionAuthority=false";
   OverviewReviewUi.status(this,overviewHistory,this::cancelAndFinish);
  }
 }
 /** Main-thread, exact-runtime publication under the unchanged hidden-app fence. */
 boolean overviewPublish(OverviewScanRuntime exact,Runnable append){
  if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()||exact==null||exact!=overviewRuntime
    ||overviewFinished||destroyed||isFinishing()||isDestroyed()||append==null
    ||!rootHiddenScalar()||!hiddenWithSoleTask())return false;
  return ReviewVisibilityProvider.FENCE.publish(visibilityLease,append);
 }
 void overviewFinished(OverviewScanRuntime exact,String fixedDetails){
  if(exact==null)return;
  if(!stageDeadlines.post(()->{
   if(exact!=overviewRuntime||overviewFinished){exact.cancel();return;}
   overviewHistory=CaptureSessionRuntime.boundedRootScalars(fixedDetails==null?"overview=UNQUALIFIED":fixedDetails,8000);
   overviewFinished=true;terminalShown=true;terminalSnapshot=new TerminalSnapshot(true,false,"CURRENT_STATUS",false);
   if(!destroyed&&!isFinishing()&&!isDestroyed())OverviewReviewUi.status(this,overviewHistory,this::cancelAndFinish);
  }))exact.cancel();
 }
 private Object alignmentOwner;
 private boolean alignmentSelected,alignmentSpent,alignmentDiagnostic;
 private UiImageAlignmentScope.Calibration alignmentCalibration;
 private volatile boolean alignmentFinished;
 private volatile String alignmentHistory;
 boolean alignmentDiagnosticSelected(){return alignmentDiagnostic;}
 UiImageAlignmentScope.Calibration alignmentCalibrationSelected(){return alignmentCalibration;}
 boolean alignmentApprovalMatches(Object owner,LiveClick incoming){
  return alignmentSelected&&alignmentSpent&&!alignmentFinished&&owner!=null&&owner==alignmentOwner
   &&incoming!=null&&incoming==click&&isResumedForApproval();
 }
 private void openAlignmentChoice(){
  if(consumed||alignmentSpent||!isResumedForApproval())return;
  alignmentDiagnostic=false;
  alignmentCalibration=null;
  alignmentSelected=true;showAlignmentChoice();
 }
 private void openAlignmentDiagnosticChoice(){
  if(consumed||alignmentSpent||!isResumedForApproval())return;
  alignmentDiagnostic=true;
  alignmentCalibration=null;
  alignmentSelected=true;showAlignmentChoice();
 }
 private void openAlignmentCalibrationChoice(UiImageAlignmentScope.Calibration attempt){
  if(consumed||alignmentSpent||!isResumedForApproval()||attempt==null)return;
  alignmentDiagnostic=false;alignmentCalibration=attempt;
  alignmentSelected=true;showAlignmentChoice();
 }
 private void showAlignmentChoice(){
  if(destroyed||isFinishing()||isDestroyed())return;
  boolean ready=NativeCheckInSession.permissionReady();
  if(alignmentCalibration!=null){
   OwnStateSummaryUi.calibrationChoice(this,ready?"现有服务与权限可用。":"现有服务或权限不可用；本次不申请权限，也不采集。",
    ready&&!consumed&&!alignmentSpent&&isResumedForApproval(),this::confirmAlignment,this::cancelAndFinish,alignmentCalibration);
   return;
  }
  OwnStateSummaryUi.alignmentChoice(this,ready?"现有服务与权限可用；确认后再检查当前页面。":"现有服务或权限不可用；本次不申请权限，也不采集。",
   ready&&!consumed&&!alignmentSpent&&isResumedForApproval(),this::confirmAlignment,this::cancelAndFinish,alignmentDiagnostic);
 }
 private void confirmAlignment(){
  if(consumed||alignmentSpent||!alignmentSelected||!isResumedForApproval())return;
  alignmentSpent=true;consumed=true;terminalShown=false;alignmentOwner=new Object();click=new LiveClick(this);
  terminalSnapshot=new TerminalSnapshot(false,false,"CURRENT_STATUS",false);
  try{
   if(!NativeCheckInSession.permissionReady())throw new SecurityException("Existing permission unavailable");
   OwnStateSummaryUi.alignmentRunning(this,this::cancelAndFinish);
   alignmentRuntime=alignmentCalibration!=null?UiImageAlignmentRuntime.startCalibrated(this,click,alignmentOwner,alignmentCalibration)
    :alignmentDiagnostic?UiImageAlignmentRuntime.startDiagnostic(this,click,alignmentOwner)
    :UiImageAlignmentRuntime.start(this,click,alignmentOwner);
  }catch(RuntimeException rejected){
   if(alignmentRuntime!=null)alignmentRuntime.cancel();if(click!=null)click.invalidate();
   alignmentFinished=true;terminalShown=true;terminalSnapshot=new TerminalSnapshot(true,false,"CURRENT_STATUS",false);
   alignmentHistory="alignmentEvidence=UNQUALIFIED stage=START_REJECTED cleanup="
    +(alignmentRuntime!=null&&alignmentRuntime.finishedAndReleased()?"CONFIRMED":"NOT_CONFIRMED")
    +" actionAuthority=false counterAuthority=false";
   OwnStateSummaryUi.alignmentStatus(this,alignmentHistory,this::cancelAndFinish);
  }
 }
 void alignmentFinished(UiImageAlignmentRuntime exact,String fixedDetails){
  if(exact==null)return;
  // Always queue: a synchronous startup failure must not beat assignment of alignmentRuntime.
  if(!stageDeadlines.post(()->{
   if(destroyed||isFinishing()||isDestroyed()||alignmentRuntime!=exact||alignmentFinished){exact.cancel();return;}
   boolean appended=false;
   boolean hiddenAccepted=false;
   final boolean[] callbackEntered={false};
   try{
    if(rootHiddenScalar()&&hiddenWithSoleTask()){
     hiddenAccepted=true;
     appended=ReviewVisibilityProvider.FENCE.publish(visibilityLease,()->{
     callbackEntered[0]=true;
     String summary=exact.finalizePublication();
     if(summary==null)throw new IllegalStateException("Alignment publication unavailable");
     alignmentHistory=CaptureSessionRuntime.boundedRootScalars((fixedDetails==null?"":fixedDetails+"\n")+summary,8000);
     });
    }
   }catch(RuntimeException rejected){appended=false;}
   if(!appended)alignmentHistory=CaptureSessionRuntime.boundedRootScalars(
    (fixedDetails==null?"":fixedDetails.replace(" stage="," preAppendStage=")+"\n")+exact.captureDiagnostics()
    +"\nalignmentEvidence=UNQUALIFIED stage=APPEND_REJECTED cleanup="
    +(exact.finishedAndReleased()?"CONFIRMED":"NOT_CONFIRMED")+" actionAuthority=false counterAuthority=false regions=SUPPRESSED"
    +" outerVisibilityFence="+(!hiddenAccepted?"HIDDEN_REJECTED":callbackEntered[0]?"CALLBACK_FAILED":"FENCE_REJECTED"),8000);
   else alignmentHistory+="\nouterVisibilityFence=RETURNED";
   exact.cancel();alignmentFinished=true;terminalShown=true;
   terminalSnapshot=new TerminalSnapshot(true,false,"CURRENT_STATUS",false);
   OwnStateSummaryUi.alignmentStatus(this,alignmentHistory,this::cancelAndFinish);
  }))exact.cancel();
 }
 private boolean returnReadSpent,returnReadFinished;
 private final java.util.List<String> returnReadReports=new java.util.ArrayList<>();
 boolean returnReadApprovalMatches(Object owner,LiveClick incoming){return !RootCurrentCardScope.ACTIVE&&RootReturnReadScope.ACTIVE&&owner!=null&&owner==rootOwner&&incoming==click&&isResumedForApproval();}
 void returnReadVisibilityRevoked(RootReturnReadIntent exact){CaptureSessionRuntime active=runtime;if(exact!=null&&returnReadIntent==exact&&!returnReadFinished){exact.cancel();if(active!=null&&active.isReturnRead()&&active.returnReadIntent()==exact)active.rootVisibilityRevoked();}}
 boolean currentReadApprovalMatches(Object owner,LiveClick incoming){return RootCurrentCardScope.ACTIVE&&owner!=null&&owner==rootOwner&&incoming==click&&isResumedForApproval();}
 boolean statusRecoveryApprovalMatches(Object owner,LiveClick incoming){return RootStatusRecoveryScope.ACTIVE&&owner!=null&&owner==rootOwner&&incoming==click&&isResumedForApproval();}
 private void confirmStatusRecovery(){
  if(RootUniqueRefinedScope.ACTIVE)return;
  if(!RootStatusRecoveryScope.ACTIVE||returnReadSpent||!isResumedForApproval())return;
  returnReadSpent=true;consumed=true;terminalShown=false;rootOwner=new Object();click=new LiveClick(this);
  try{
   returnReadIntent=CaptureSessionRuntime.approveStatusRecovery(this,click,rootOwner);
   terminalSnapshot=new TerminalSnapshot(false,false,"CURRENT_STATUS",false);
   showStatus("开始任务卡原生验证 v36：独立恢复与当前逐卡只读；零任务点击、至多一次Back，A/B不再点击。",false);
   runtime=CaptureSessionRuntime.startStatusRecovery(this,returnReadIntent,rootOwner,1,null);
   RootReturnReadIntent exact=returnReadIntent;
   stageDeadlines.postDelayed(()->{if(returnReadIntent==exact&&!returnReadFinished){exact.cancel();if(runtime!=null&&runtime.returnReadIntent()==exact)runtime.cancel();}},Math.max(0,exact.sessionEnd()-android.os.SystemClock.elapsedRealtime()));
  }catch(RuntimeException rejected){if(returnReadIntent!=null)returnReadIntent.cancel();if(click!=null)click.invalidate();returnReadFinished=true;showStatus("独立状态恢复未开始或已停止；零任务点击，不重试。",true);}
 }
 boolean progressReadApprovalMatches(Object owner,LiveClick incoming){return RootProgressReadScope.ACTIVE&&owner!=null&&owner==rootOwner&&incoming==click&&isResumedForApproval();}
 boolean progressScanApprovalMatches(Object owner,LiveClick incoming){return RootProgressScanScope.ACTIVE&&owner!=null&&owner==rootOwner&&incoming==click&&isResumedForApproval();}
 boolean checkpointApprovalMatches(Object owner,LiveClick incoming){return ReadOnlyCheckpoint.CURRENT!=null&&owner!=null&&owner==rootOwner&&incoming==click&&isResumedForApproval();}
 boolean progressLocateApprovalMatches(Object owner,LiveClick incoming){return RootProgressLocateScope.ACTIVE&&owner!=null&&owner==rootOwner&&incoming==click&&isResumedForApproval();}
 private void confirmProgressLocate(){



  if(!RootProgressLocateScope.ACTIVE||returnReadSpent||!isResumedForApproval())return;
  returnReadSpent=true;consumed=true;terminalShown=false;rootOwner=new Object();click=new LiveClick(this);
  try{
   returnReadIntent=CaptureSessionRuntime.approveProgressLocate(this,click,rootOwner);
   terminalSnapshot=new TerminalSnapshot(false,false,"CURRENT_STATUS",false);
   showStatus("开始搜索/阅读上方内容定位 v42；最多3帧、2次固定手指向下滑动，零任务点击/Back/文字输入。",false);
   runtime=CaptureSessionRuntime.startProgressLocate(this,returnReadIntent,rootOwner,1,null);
   RootReturnReadIntent exact=returnReadIntent;
   stageDeadlines.postDelayed(()->{if(returnReadIntent==exact&&!returnReadFinished){exact.cancel();if(runtime!=null&&runtime.returnReadIntent()==exact)runtime.cancel();}},Math.max(0,exact.sessionEnd()-android.os.SystemClock.elapsedRealtime()));
  }catch(RuntimeException rejected){if(returnReadIntent!=null)returnReadIntent.cancel();if(click!=null)click.invalidate();returnReadFinished=true;showStatus("独立当前页只读未开始或已停止；没有任务输入或Back，不重试。",true);}
 }
 private void confirmCheckpointRead(){



  if(ReadOnlyCheckpoint.CURRENT==null||returnReadSpent||!isResumedForApproval())return;
  returnReadSpent=true;consumed=true;terminalShown=false;rootOwner=new Object();click=new LiveClick(this);
  try{
   returnReadIntent=CaptureSessionRuntime.approveCheckpoint(this,click,rootOwner);
   terminalSnapshot=new TerminalSnapshot(false,false,"CURRENT_STATUS",false);
   showStatus(returnReadIntent.uiBoundPoints()?"开始V53结构绑定积分只读；零输入，不重复定位。":returnReadIntent.uiLocate()?"开始V52原生区域定位：最多一次SHOW_ON_SCREEN，零任务点击。":returnReadIntent.uiField()?"开始V51字段与父链只读诊断：零导航，不发布业务KNOWN。":returnReadIntent.uiFormat()?"开始V50严格计数与字段只读诊断；不推断数字归属，零导航/输入。":returnReadIntent.uiReady()?"开始V49单次页面就绪观察与计数只读：零导航/点击/Back/刷新/截图/OCR。":returnReadIntent.entryDiagnostic()?"开始V47只读入口诊断：0导航/点击/Back/刷新/计数读取；仅设备内结构匹配统计。":returnReadIntent.entryRecovery()?"开始V48入口恢复：至多1次首页导航，0积分任务点击；不Back/刷新/文字输入。":"开始设备内UI结构只读；零截图/OCR/点击/滑动/Back/文字输入，仅报告严格完整字段。",false);
   runtime=CaptureSessionRuntime.startCheckpointRead(this,returnReadIntent,rootOwner,1,null);
   RootReturnReadIntent exact=returnReadIntent;
   stageDeadlines.postDelayed(()->{if(returnReadIntent==exact&&!returnReadFinished){exact.cancel();if(runtime!=null&&runtime.returnReadIntent()==exact)runtime.cancel();}},Math.max(0,exact.sessionEnd()-android.os.SystemClock.elapsedRealtime()));
  }catch(RuntimeException rejected){if(returnReadIntent!=null)returnReadIntent.cancel();if(click!=null)click.invalidate();returnReadFinished=true;showStatus("独立当前页只读未开始或已停止；没有任务输入或Back，不重试。",true);}
 }
 private void confirmProgressScan(){



  if(!RootProgressScanScope.ACTIVE||returnReadSpent||!isResumedForApproval())return;
  returnReadSpent=true;consumed=true;terminalShown=false;rootOwner=new Object();click=new LiveClick(this);
  try{
   returnReadIntent=CaptureSessionRuntime.approveProgressScan(this,click,rootOwner);
   terminalSnapshot=new TerminalSnapshot(false,false,"CURRENT_STATUS",false);
   showStatus("开始搜索/阅读页内定位 v40；最多3帧、2次固定向上滑动，零任务点击/Back/文字输入。",false);
   runtime=CaptureSessionRuntime.startProgressScan(this,returnReadIntent,rootOwner,1,null);
   RootReturnReadIntent exact=returnReadIntent;
   stageDeadlines.postDelayed(()->{if(returnReadIntent==exact&&!returnReadFinished){exact.cancel();if(runtime!=null&&runtime.returnReadIntent()==exact)runtime.cancel();}},Math.max(0,exact.sessionEnd()-android.os.SystemClock.elapsedRealtime()));
  }catch(RuntimeException rejected){if(returnReadIntent!=null)returnReadIntent.cancel();if(click!=null)click.invalidate();returnReadFinished=true;showStatus("独立当前页只读未开始或已停止；没有任务输入或Back，不重试。",true);}
 }
 private void confirmProgressRead(){



  if(!RootProgressReadScope.ACTIVE||returnReadSpent||!isResumedForApproval())return;
  returnReadSpent=true;consumed=true;terminalShown=false;rootOwner=new Object();click=new LiveClick(this);
  try{
   returnReadIntent=CaptureSessionRuntime.approveProgressRead(this,click,rootOwner);
   terminalSnapshot=new TerminalSnapshot(false,false,"CURRENT_STATUS",false);
   showStatus("开始搜索/阅读进度只读 v39；零点击、零Back、零滑动、零输入。",false);
   runtime=CaptureSessionRuntime.startProgressRead(this,returnReadIntent,rootOwner,1,null);
   RootReturnReadIntent exact=returnReadIntent;
   stageDeadlines.postDelayed(()->{if(returnReadIntent==exact&&!returnReadFinished){exact.cancel();if(runtime!=null&&runtime.returnReadIntent()==exact)runtime.cancel();}},Math.max(0,exact.sessionEnd()-android.os.SystemClock.elapsedRealtime()));
  }catch(RuntimeException rejected){if(returnReadIntent!=null)returnReadIntent.cancel();if(click!=null)click.invalidate();returnReadFinished=true;showStatus("独立当前页只读未开始或已停止；没有任务输入或Back，不重试。",true);}
 }
 private void confirmCurrentRead(){
  if(RootUniqueRefinedScope.ACTIVE)return;
  if(RootStatusRecoveryScope.ACTIVE)return;
  if(RootSelectedPendingScope.ACTIVE)return;
  if(!RootCurrentCardScope.ACTIVE||returnReadSpent||!isResumedForApproval())return;
  returnReadSpent=true;consumed=true;terminalShown=false;rootOwner=new Object();click=new LiveClick(this);
  try{
   returnReadIntent=CaptureSessionRuntime.approveCurrentRead(this,click,rootOwner);
   terminalSnapshot=new TerminalSnapshot(false,false,"CURRENT_STATUS",false);
   showStatus("开始任务卡原生验证 v34：独立当前页逐卡只读，零Back、零任务点击；不是v31或v32续跑。",false);
   runtime=CaptureSessionRuntime.startCurrentRead(this,returnReadIntent,rootOwner,1,null);
   RootReturnReadIntent exact=returnReadIntent;
   stageDeadlines.postDelayed(()->{if(returnReadIntent==exact&&!returnReadFinished){exact.cancel();if(runtime!=null&&runtime.returnReadIntent()==exact)runtime.cancel();}},Math.max(0,exact.sessionEnd()-android.os.SystemClock.elapsedRealtime()));
  }catch(RuntimeException rejected){if(returnReadIntent!=null)returnReadIntent.cancel();if(click!=null)click.invalidate();returnReadFinished=true;showStatus("独立当前页只读未开始或已停止；没有任务输入或Back，不重试。",true);}
 }
 private void confirmReturnRead(){
  if(RootUniqueRefinedScope.ACTIVE)return;
  if(RootStatusRecoveryScope.ACTIVE)return;
  if(RootSelectedPendingScope.ACTIVE)return;
  if(RootCurrentCardScope.ACTIVE||!RootReturnReadScope.ACTIVE||returnReadSpent||!isResumedForApproval())return;
  returnReadSpent=true;consumed=true;terminalShown=false;rootOwner=new Object();click=new LiveClick(this);
  try{
   returnReadIntent=CaptureSessionRuntime.approveReturnRead(this,click,rootOwner);
   terminalSnapshot=new TerminalSnapshot(false,false,"CURRENT_STATUS",false);
   showStatus("开始独立返回与只读核验 v32。零任务点击，最多一次原生Back；不会继续或重跑v31。",false);
   runtime=CaptureSessionRuntime.startReturnRead(this,returnReadIntent,rootOwner,1,null);
   RootReturnReadIntent exact=returnReadIntent;
   stageDeadlines.postDelayed(()->{if(returnReadIntent==exact&&!returnReadFinished){exact.cancel();if(runtime!=null&&runtime.returnReadIntent()==exact)runtime.cancel();}},Math.max(0,exact.sessionEnd()-android.os.SystemClock.elapsedRealtime()));
  }catch(RuntimeException rejected){if(returnReadIntent!=null)returnReadIntent.cancel();if(click!=null)click.invalidate();returnReadFinished=true;showStatus("独立恢复未开始或已停止；不重试、不重复任务输入。",true);}
 }
 void returnReadFrameFinished(CaptureSessionRuntime exact,CaptureSessionRuntime.ReturnReadCompletion next,String details){
  runOnUiThread(()->{
   if(destroyed||isFinishing()||isDestroyed()||runtime!=exact||!exact.isReturnRead()||exact.returnReadIntent()!=returnReadIntent||returnReadFinished)return;
   if(returnReadReports.size()<RootReturnReadScope.MAX_FRAMES)returnReadReports.add(exact.finalizeProgressPublication(details));
   if(next!=null&&exact.finishedAndReleased()&&returnReadIntent.current(android.os.SystemClock.elapsedRealtime())&&rootHiddenScalar()&&hiddenWithSoleTask()){
    try{runtime=returnReadIntent.progressLocate()?CaptureSessionRuntime.startProgressLocate(this,returnReadIntent,rootOwner,exact.returnReadIndex()+1,next):returnReadIntent.progressScan()?CaptureSessionRuntime.startProgressScan(this,returnReadIntent,rootOwner,exact.returnReadIndex()+1,next):returnReadIntent.progressRead()?CaptureSessionRuntime.startProgressRead(this,returnReadIntent,rootOwner,exact.returnReadIndex()+1,next):returnReadIntent.statusRecovery()?CaptureSessionRuntime.startStatusRecovery(this,returnReadIntent,rootOwner,exact.returnReadIndex()+1,next):returnReadIntent.currentOnly()?CaptureSessionRuntime.startCurrentRead(this,returnReadIntent,rootOwner,exact.returnReadIndex()+1,next):CaptureSessionRuntime.startReturnRead(this,returnReadIntent,rootOwner,exact.returnReadIndex()+1,next);return;}
    catch(RuntimeException rejected){returnReadIntent.cancel();}
   }
   returnReadFinished=true;terminalShown=true;returnReadIntent.cancel();terminalSnapshot=new TerminalSnapshot(true,false,"CURRENT_STATUS",false);
   RootCaptureUi.returnReadStatus(this,returnReadReports,(returnReadIntent.uiBoundPoints()?"V53结构绑定积分只读结束，零输入，不赋动作资格。清理=":returnReadIntent.uiLocate()?"V52定位与诊断结束；实际定位结果见回执，零任务点击。清理=":returnReadIntent.uiField()?"V51诊断结束；仅结构与格式，不赋予计数归属或动作资格。清理=":returnReadIntent.uiFormat()?"V50只读结束；诊断不赋予计数归属或动作资格，零导航。清理=":returnReadIntent.uiReady()?"V49只读结束；没有再次导航或积分任务点击。清理=":returnReadIntent.entryDiagnostic()?"V47只读入口诊断结束，0导航/点击；不是计数或动作资格。清理=":returnReadIntent.entryRecovery()?"V48首页入口恢复结束，实际导航次数见回执；0积分任务点击/Back，不补写旧业务。清理=":returnReadIntent.statusRecovery()?"独立状态恢复与逐卡只读结束；零任务点击，不补写任何旧业务闭环或因果。清理=":returnReadIntent.currentOnly()?"独立当前页只读结束，0Back/0任务点击；不是旧业务闭环。清理=":"独立恢复已结束；只说明当前观察，不补写v31闭环或因果。清理=")+(exact.finishedAndReleased()?"CONFIRMED":"UNKNOWN"),this::cancelAndFinish);
  });
 }
 private volatile RootBoundedBusinessIntent boundedIntent;
 private boolean boundedSpent,boundedFinished;
 private final java.util.List<String> boundedReports=new java.util.ArrayList<>();
 boolean boundedApprovalMatches(Object owner,LiveClick incoming){return !RootSelectedPendingScope.ACTIVE&&!RootReturnReadScope.ACTIVE&&RootBoundedBusinessScope.ACTIVE&&owner!=null&&owner==rootOwner&&incoming==click&&isResumedForApproval();}
 void boundedVisibilityRevoked(RootBoundedBusinessIntent exact){
  CaptureSessionRuntime active=runtime;
  if(boundedIntent==exact&&!boundedFinished){exact.cancel();if(autonomousRun!=null)autonomousRun.cancel();
   if(active!=null&&active.isBounded()&&active.boundedIntent()==exact)active.rootVisibilityRevoked();}
 }
 boolean selectedPendingApprovalMatches(Object owner,LiveClick incoming){return RootSelectedPendingScope.ACTIVE&&owner!=null&&owner==rootOwner&&incoming==click&&isResumedForApproval();}
 boolean uniquePendingApprovalMatches(Object owner,LiveClick incoming){return RootUniqueRefinedScope.ACTIVE&&owner!=null&&owner==rootOwner&&incoming==click&&isResumedForApproval();}
 private void confirmUniquePending(){
  if(!RootUniqueRefinedScope.ACTIVE||boundedSpent||!isResumedForApproval())return;
  boundedSpent=true;consumed=true;terminalShown=false;rootOwner=new Object();click=new LiveClick(this);
  try{
   boundedIntent=CaptureSessionRuntime.approveUniquePending(this,click,rootOwner);
   autonomousRun=NativeAutonomousCardRun.confirmedUniquePending(this,boundedIntent.sessionEnd());boundedIntent.bindRun(autonomousRun);
   terminalSnapshot=new TerminalSnapshot(false,false,"BEFORE",false);
   showStatus("开始任务卡原生验证 v38：只打开本帧唯一合法+5未完成项；原生点击命令成功确认后至少5秒一次Back，再新帧核验。本轮已消费A/B同族、Quote、Puzzle、签到禁止自动重复。",false);
   runtime=CaptureSessionRuntime.startUniquePending(this,boundedIntent,rootOwner,autonomousRun,NativeRewardCardSession.Phase.BEFORE,1,null);
   RootBoundedBusinessIntent exact=boundedIntent;
   stageDeadlines.postDelayed(()->{if(boundedIntent==exact&&!boundedFinished){exact.cancel();autonomousRun.cancel();if(runtime!=null&&runtime.boundedIntent()==exact)runtime.cancel();}},Math.max(0,exact.sessionEnd()-android.os.SystemClock.elapsedRealtime()));
  }catch(RuntimeException rejected){if(boundedIntent!=null)boundedIntent.cancel();if(autonomousRun!=null)autonomousRun.cancel();if(click!=null)click.invalidate();boundedFinished=true;showStatus("唯一待办未开始或已停止；不重试、不复用旧槽、不重复A/B或其它已完成任务。",true);}
 }
 private void confirmSelectedPending(){
  if(RootUniqueRefinedScope.ACTIVE)return;
  if(RootStatusRecoveryScope.ACTIVE)return;
  if(!RootSelectedPendingScope.ACTIVE||boundedSpent||!isResumedForApproval())return;
  boundedSpent=true;consumed=true;terminalShown=false;rootOwner=new Object();click=new LiveClick(this);
  try{
   boundedIntent=CaptureSessionRuntime.approveSelectedPending(this,click,rootOwner);
   autonomousRun=NativeAutonomousCardRun.confirmedSelectedPending(this,boundedIntent.sessionEnd());boundedIntent.bindRun(autonomousRun);
   terminalSnapshot=new TerminalSnapshot(false,false,"BEFORE",false);
   showStatus("开始任务卡原生验证 v35：仅新鲜exact B未完成卡，最多1次任务点击、1次返回；不再触A。",false);
   runtime=CaptureSessionRuntime.startSelectedPending(this,boundedIntent,rootOwner,autonomousRun,NativeRewardCardSession.Phase.BEFORE,1,null);
   RootBoundedBusinessIntent exact=boundedIntent;
   stageDeadlines.postDelayed(()->{if(boundedIntent==exact&&!boundedFinished){exact.cancel();autonomousRun.cancel();if(runtime!=null&&runtime.boundedIntent()==exact)runtime.cancel();}},Math.max(0,exact.sessionEnd()-android.os.SystemClock.elapsedRealtime()));
  }catch(RuntimeException rejected){if(boundedIntent!=null)boundedIntent.cancel();if(autonomousRun!=null)autonomousRun.cancel();if(click!=null)click.invalidate();boundedFinished=true;showStatus("B任务未开始或已停止；不重试、不复用旧槽、不重复A或其它任务。",true);}
 }
 private void confirmBounded(){
  if(RootUniqueRefinedScope.ACTIVE)return;
  if(RootStatusRecoveryScope.ACTIVE)return;
  if(RootSelectedPendingScope.ACTIVE)return;
  if(RootReturnReadScope.ACTIVE||!RootBoundedBusinessScope.ACTIVE||boundedSpent||!isResumedForApproval())return;
  boundedSpent=true;consumed=true;terminalShown=false;rootOwner=new Object();click=new LiveClick(this);
  try{
   boundedIntent=CaptureSessionRuntime.approveBounded(this,click,rootOwner);
   autonomousRun=NativeAutonomousCardRun.confirmedBounded(this,boundedIntent.sessionEnd());boundedIntent.bindRun(autonomousRun);
   terminalSnapshot=new TerminalSnapshot(false,false,"BEFORE",false);
   showStatus("开始任务卡原生验证 v31；BEFORE/AFTER各最多3次。只有健康未呈现才继续，最多一次任务点击与一次返回。",false);
   runtime=CaptureSessionRuntime.startBounded(this,boundedIntent,rootOwner,autonomousRun,NativeRewardCardSession.Phase.BEFORE,1,null);
   RootBoundedBusinessIntent exact=boundedIntent;
   stageDeadlines.postDelayed(()->{if(boundedIntent==exact&&!boundedFinished){exact.cancel();autonomousRun.cancel();if(runtime!=null)runtime.cancel();}},
       Math.max(0,exact.sessionEnd()-android.os.SystemClock.elapsedRealtime()));
  }catch(RuntimeException rejected){if(boundedIntent!=null)boundedIntent.cancel();if(autonomousRun!=null)autonomousRun.cancel();if(click!=null)click.invalidate();
   boundedFinished=true;showStatus("业务未开始或已停止；不重试、不复用旧槽、不重复任务。",true);}
 }
 void boundedFrameFinished(CaptureSessionRuntime exact,CaptureSessionRuntime.BoundedTransition transition,String candidate,String details){
  runOnUiThread(()->{
   if(destroyed||isFinishing()||isDestroyed()||runtime!=exact||!exact.isBounded()||exact.boundedIntent()!=boundedIntent||boundedFinished)return;
   boolean published=false;
   if(candidate!=null&&exact.boundedPublicationCurrent()){
    SealedReadOnlyReceipt committed=new SealedReadOnlyReceipt();
    published=committed.commit(ReviewVisibilityProvider.FENCE,visibilityLease,handoff.hiddenWithSoleTask(),CaptureSessionRuntime.boundedRootScalars(candidate,7000))
        &&exact.commitBounded();
    if(published)receipt=committed;
   }
   String record="phase="+exact.boundedPhase()+" attempt="+exact.boundedAttempt()+" published="+published+"\n"
       +(candidate==null?"pageCandidate=NONE":candidate)+"\n"+details;
   if(boundedReports.size()<RootBoundedBusinessScope.MAX_FRAMES)boundedReports.add(record);
   boolean canContinue=transition!=null&&exact.finishedAndReleased()&&boundedIntent.current(android.os.SystemClock.elapsedRealtime())
       &&rootHiddenScalar()&&hiddenWithSoleTask()&&(transition.phase()==exact.boundedPhase()||published);
   if(canContinue){
    try{
     terminalSnapshot=new TerminalSnapshot(false,false,transition.phase().name(),false);
     runtime=boundedIntent.uniquePending()?CaptureSessionRuntime.startUniquePending(this,boundedIntent,rootOwner,autonomousRun,transition.phase(),transition.attempt(),transition):boundedIntent.selectedPending()?CaptureSessionRuntime.startSelectedPending(this,boundedIntent,rootOwner,autonomousRun,transition.phase(),transition.attempt(),transition):CaptureSessionRuntime.startBounded(this,boundedIntent,rootOwner,autonomousRun,transition.phase(),transition.attempt(),transition);return;
    }catch(RuntimeException rejected){boundedIntent.cancel();autonomousRun.cancel();}
   }
   boundedFinished=true;terminalShown=true;boundedIntent.cancel();autonomousRun.cancel();
   terminalSnapshot=new TerminalSnapshot(true,published,exact.boundedPhase().name(),false);
   RootCaptureUi.boundedStatus(this,boundedReports,"业务观察结束；历史回执不是新授权。清理="+(exact.finishedAndReleased()?"CONFIRMED":"UNKNOWN"),this::cancelAndFinish);
   // No own-task focus between observations or at terminal. A later real return only displays history.
  });
 }
 private volatile RootImageSeriesIntent seriesIntent;
 private boolean seriesSpent,seriesFinished;
 private final java.util.List<String> seriesReports=new java.util.ArrayList<>();
 private MaaImageBufferAudit.Fingerprint seriesFirstFingerprint,seriesPreviousFingerprint;
 boolean seriesApprovalMatches(Object owner,LiveClick incoming){return !RootBoundedBusinessScope.ACTIVE&&RootImageSeriesScope.ACTIVE&&owner!=null&&owner==rootOwner&&incoming==click&&isResumedForApproval();}
 void seriesVisibilityRevoked(RootImageSeriesIntent exact){
  CaptureSessionRuntime active=runtime;
  if(seriesIntent==exact&&!seriesFinished){exact.cancel();if(active!=null&&active.isSeries()&&active.seriesIntent()==exact)active.rootVisibilityRevoked();}
 }
 private void confirmSeries(){
  if(RootUniqueRefinedScope.ACTIVE)return;
  if(RootBoundedBusinessScope.ACTIVE||!RootImageSeriesScope.ACTIVE||seriesSpent||!isResumedForApproval())return;
  seriesSpent=true;consumed=true;terminalShown=false;rootOwner=new Object();click=new LiveClick(this);
  try{
   seriesIntent=CaptureSessionRuntime.approveSeries(this,click,rootOwner);
   terminalSnapshot=new TerminalSnapshot(false,false,"BEFORE",false);
   showStatus("开始独立整屏只读序列；最多3帧，每帧1次OCR，零业务输入。中间不恢复自身页面。",false);
   runtime=CaptureSessionRuntime.startSeries(this,seriesIntent,rootOwner,1,null);
   RootImageSeriesIntent exact=seriesIntent;
   stageDeadlines.postDelayed(()->{if(seriesIntent==exact&&!seriesFinished){exact.cancel();if(runtime!=null)runtime.cancel();}},
       Math.max(0,exact.sessionEnd()-android.os.SystemClock.elapsedRealtime()));
  }catch(RuntimeException rejected){if(seriesIntent!=null)seriesIntent.cancel();if(click!=null)click.invalidate();
   seriesFinished=true;showStatus("只读序列未开始或已停止；不重试、不复用旧槽。",true);}
 }
 void seriesFrameFinished(CaptureSessionRuntime exact,CaptureSessionRuntime.SeriesCompletion completion,String details){
  runOnUiThread(()->{
   if(destroyed||isFinishing()||isDestroyed()||runtime!=exact||!exact.isSeries()||exact.seriesIntent()!=seriesIntent||seriesFinished)return;
   var fingerprint=exact.takeSeriesFingerprint();
   String comparisons="compareFirst="+MaaImageBufferAudit.compare(seriesFirstFingerprint,fingerprint)
       +" comparePrevious="+MaaImageBufferAudit.compare(seriesPreviousFingerprint,fingerprint);
   String report=details+"\n"+comparisons;
   boolean bounded=report.length()<=7000&&seriesReports.size()<RootImageSeriesScope.MAX_FRAMES;
   if(bounded)seriesReports.add(report);else seriesReports.add("frame="+exact.seriesIndex()+" reportBudget=EXCEEDED sourceCleanup="+exact.finishedAndReleased()+" noNext=true");
   if(fingerprint!=null){if(seriesFirstFingerprint==null)seriesFirstFingerprint=fingerprint;seriesPreviousFingerprint=fingerprint;}
   if(bounded&&completion!=null&&exact.seriesIndex()<RootImageSeriesScope.MAX_FRAMES&&seriesIntent.current(android.os.SystemClock.elapsedRealtime())
       &&rootHiddenScalar()&&hiddenWithSoleTask()){
    try{runtime=CaptureSessionRuntime.startSeries(this,seriesIntent,rootOwner,exact.seriesIndex()+1,completion);return;}
    catch(RuntimeException rejected){seriesIntent.cancel();}
   }
   seriesFinished=true;terminalShown=true;seriesIntent.cancel();
   terminalSnapshot=new TerminalSnapshot(true,false,"BEFORE",false);
   seriesFirstFingerprint=null;seriesPreviousFingerprint=null;
   RootCaptureUi.seriesStatus(this,seriesReports,"只读序列已结束。没有页面候选、业务pass或继续授权。清理="+(exact.finishedAndReleased()?"CONFIRMED":"UNKNOWN"),this::cancelAndFinish);
   // No focus request, including at completion. Historical scalar UI is available on an actual later return.
  });
 }
 private volatile RootCaptureIntent rootIntent;
 private RootCaptureIntent.PreflightPass rootPreflightPass;
 private RootCaptureScope.Mode rootApprovalMode;
 private Object rootOwner;
 private boolean rootPreflightSpent,rootBusinessSpent;
 private CaptureSessionRuntime rootCommittedRuntime,rootRestoreRequested;
 private String rootHistory,rootFirstHistory;
 boolean rootApprovalMatches(RootCaptureScope.Mode mode,Object owner,LiveClick incoming){
  return mode!=null&&mode==rootApprovalMode&&owner!=null&&owner==rootOwner&&incoming!=null&&incoming==click&&isResumedForApproval();
 }
 boolean rootHiddenScalar(){return !destroyed&&handoff.hidden()&&ReviewVisibilityProvider.FENCE.valid(visibilityLease);}
 void rootVisibilityRevoked(RootCaptureIntent exactIntent){
  CaptureSessionRuntime active=runtime;
  if(rootIntent==exactIntent&&active!=null&&active.isRoot())active.rootVisibilityRevoked();
 }
 boolean rootCommitMatches(CaptureSessionRuntime exact){return exact!=null&&exact==runtime&&exact==rootCommittedRuntime
   &&terminalSnapshot.terminal()&&terminalSnapshot.published()&&receipt.history()!=null;}
 private void confirmRootPreflight(){
  if(RootUniqueRefinedScope.ACTIVE)return;confirmRoot(RootCaptureScope.Mode.PREFLIGHT);}
 private void confirmRootBusiness(){
  if(RootUniqueRefinedScope.ACTIVE)return;confirmRoot(RootCaptureScope.Mode.BUSINESS);}
 private void confirmRoot(RootCaptureScope.Mode mode){
  if(RootUniqueRefinedScope.ACTIVE)return;
  if(RootBoundedBusinessScope.ACTIVE||RootImageSeriesScope.ACTIVE)return; // Before every old Run/actor constructor.
  if(!RootFamilyDiagnosticProfile.modeAllowed(mode))return; // Before any Run/actor constructor.
  if(!isResumedForApproval()||mode==RootCaptureScope.Mode.PREFLIGHT&&rootPreflightSpent
       ||mode==RootCaptureScope.Mode.BUSINESS&&(rootBusinessSpent||rootPreflightPass==null))return;
  if(mode==RootCaptureScope.Mode.PREFLIGHT)rootPreflightSpent=true;else rootBusinessSpent=true;
  consumed=true;terminalShown=false;rootApprovalMode=mode;rootOwner=new Object();click=new LiveClick(this);
  receipt=new SealedReadOnlyReceipt();rootCommittedRuntime=null;rootRestoreRequested=null;
  try{
   // No business Run or actor exists on the separate preflight path.
   autonomousRun=mode==RootCaptureScope.Mode.BUSINESS?NativeAutonomousCardRun.confirmed(this):null;
   rootIntent=CaptureSessionRuntime.approveRoot(this,click,rootOwner,mode,rootPreflightPass);
   if(mode==RootCaptureScope.Mode.BUSINESS)rootPreflightPass=null;
   terminalSnapshot=new TerminalSnapshot(false,false,"BEFORE",false);
   showStatus(mode==RootCaptureScope.Mode.PREFLIGHT?"开始独立整屏只读预检；1帧，零业务输入。":"本次独立业务已批准；开始全新BEFORE整屏帧。",false);
   runtime=CaptureSessionRuntime.startRoot(this,rootIntent,rootOwner,RootCaptureScope.Phase.FIRST,null,autonomousRun);
   RootCaptureIntent exact=rootIntent;
   stageDeadlines.postDelayed(()->{if(rootIntent==exact){exact.cancel();if(runtime!=null)runtime.cancel();}},
     Math.max(0,exact.sessionEnd()-android.os.SystemClock.elapsedRealtime()));
  }catch(RuntimeException denied){if(seriesIntent!=null)seriesIntent.cancel();if(rootIntent!=null)rootIntent.cancel();if(autonomousRun!=null)autonomousRun.cancel();
   if(click!=null)click.invalidate();showStatus("本次root入口未开始或已停止，不重试，不复用旧许可。",true);}
 }
 void showRootFinalStatus(CaptureSessionRuntime exact,String candidate,String details){
  runOnUiThread(()->{
   if(destroyed||isFinishing()||isDestroyed()||runtime!=exact)return;
   terminalShown=true;
   boolean published=candidate!=null&&exact.rootPublicationCurrent()
      &&receipt.commit(ReviewVisibilityProvider.FENCE,visibilityLease,handoff.hiddenWithSoleTask(),CaptureSessionRuntime.rootReceiptText(candidate,details));
   terminalSnapshot=new TerminalSnapshot(true,published,exact.nativePhaseLabel(),false);
   rootCommittedRuntime=published?exact:null;
   String text=published?"已封存历史回执（不是新授权）\n"+receipt.history():"没有可发布页面结果。\n"+details;
   if(rootFirstHistory!=null&&exact.rootPhase()==RootCaptureScope.Phase.SECOND)text="FIRST已封存历史\n"+CaptureSessionRuntime.boundedRootScalars(rootFirstHistory,3900)+"\nSECOND\n"+CaptureSessionRuntime.boundedRootScalars(text,3900);
   rootHistory=CaptureSessionRuntime.boundedRootScalars(text,8000);
   if(published){
    var publication=exact.rootCommitted();
    if(publication!=null){
     rootIntent.acceptPublished(publication,android.os.SystemClock.elapsedRealtime());
     if(exact.rootMode()==RootCaptureScope.Mode.PREFLIGHT){
      rootPreflightPass=rootIntent.claimPreflightPass(publication.epoch(),rootOwner,android.os.SystemClock.elapsedRealtime());
     }else if(exact.rootPhase()==RootCaptureScope.Phase.FIRST&&publication.returnReady()){
      var continuation=rootIntent.claimOpaqueContinuation(publication.epoch(),rootOwner,android.os.SystemClock.elapsedRealtime());
      if(continuation!=null){
       rootFirstHistory=receipt.history();terminalShown=false;receipt=new SealedReadOnlyReceipt();rootCommittedRuntime=null;
       terminalSnapshot=new TerminalSnapshot(false,false,"AFTER",false);
       try{
        // No own-task restore, synthetic LiveClick or Projection request between root phases.
        runtime=CaptureSessionRuntime.startRoot(this,rootIntent,rootOwner,RootCaptureScope.Phase.SECOND,continuation,autonomousRun);
        return;
       }catch(RuntimeException rejected){rootIntent.cancel();autonomousRun.cancel();rootHistory=text+"\nSECOND未能开始；不重复任务触摸或返回。";}
      }
     }
    }
   }
   RootCaptureUi.status(this,rootHistory,true,this::cancelAndFinish);
   if(exact.finishedAndReleased())restoreOwnTaskForRootDisplay(exact);
  });
 }
 private void restoreOwnTaskForRootDisplay(CaptureSessionRuntime exact){
  if(runtime!=exact||!exact.finishedAndReleased()||rootRestoreRequested==exact)return;rootRestoreRequested=exact;
  try{
   if(android.os.Build.VERSION.SDK_INT<34||!handoff.hiddenWithSoleTask())throw new SecurityException("Exact hidden own task required");
   var tasks=getSystemService(android.app.ActivityManager.class).getAppTasks();if(tasks.size()!=1)throw new SecurityException("One own task required");
   var own=tasks.get(0);var info=own.getTaskInfo();
   if(!OwnTaskHandoff.shape(tasks.size(),getTaskId(),info.taskId,info.numActivities,isTaskRoot(),getComponentName().equals(info.baseActivity),getComponentName().equals(info.topActivity)))throw new SecurityException("Own task changed");
   own.moveToFront(); // Display request only. It never proves source identity or issues another intent.
  }catch(RuntimeException unavailable){rootHistory+="\n系统未恢复结果页。仅显示回执受影响，不据此发起新动作。";}
 }
 private AutonomousStageRoute autonomousRoute;
 private String firstAutonomousReceipt;
 private long ownRestoreDeadline=-1;
 private final android.os.Handler stageDeadlines=new android.os.Handler(android.os.Looper.getMainLooper());
 private String permissionStatus="Shizuku 权限未检查。";
 private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener permissionListener=(code,result)->{
  if(code==24011&&!consumed)runOnUiThread(this::showCaptureChoices);
 };
 private final java.util.concurrent.atomic.AtomicReference<Runnable> pendingHandoff=new java.util.concurrent.atomic.AtomicReference<>();
 private volatile CaptureSessionRuntime runtime;
 private AppVisibilityFence.Lease visibilityLease;
 private boolean navigationUsed, permissionRequestSpent;
 static record TerminalSnapshot(boolean terminal,boolean published,String phase,boolean needsAfter){
  String line(long remaining){
   String safePhase=phase!=null&&java.util.Set.of("NONE","CURRENT_STATUS","BEFORE","AFTER").contains(phase)?phase:"NONE";
   boolean allowed=terminal&&published&&safePhase.equals("BEFORE")&&needsAfter&&remaining>0;
   long bounded=allowed?Math.min(remaining,NativeCheckInScope.SESSION_TTL):0;
   return "RewardsNativeTerminal terminal="+terminal+" published="+published+" phase="+safePhase
    +" needsAfter="+allowed+" nextAllowed="+(allowed?"AFTER_READ_ONLY_APPROVAL":terminal?"NONE":"WAIT")
    +" sessionRemainingMs="+bounded;
  }
 }
 private volatile TerminalSnapshot terminalSnapshot=new TerminalSnapshot(false,false,"NONE",false);
 @Override public void dump(String prefix,java.io.FileDescriptor fd,java.io.PrintWriter writer,String[] args){
  TerminalSnapshot value=terminalSnapshot;
  NativeCheckInSession session=nativeSession;CaptureSessionRuntime run=runtime;
  NativeRewardCardRun card=cardRun;
  long remaining=run!=null&&run.finishedAndReleased()?(autonomousRun!=null?autonomousRun.remainingForAfterMillis():readOnlyRun!=null?readOnlyRun.remainingForAfterMillis():card!=null?card.remainingForAfterMillis():session!=null?session.remainingForAfterMillis():0):0;
  writer.println(value.line(remaining));
  OverviewScanRuntime overview=overviewRuntime;
  writer.println("RewardsOverviewRelease present="+(overview!=null)+" released="+(overview!=null&&overview.finishedAndReleased()));
  if(overviewFinished&&overviewHistory!=null)writer.println("RewardsOverviewDiagnostic version=1 "+CaptureSessionRuntime.boundedRootScalars(overviewHistory.replace('\n',' '),8000));
  UiImageAlignmentRuntime exact=alignmentRuntime;
  writer.println("RewardsAlignmentRelease present="+(exact!=null)+" released="+(exact!=null&&exact.finishedAndReleased()));
  String history=alignmentHistory;
  if(alignmentFinished&&history!=null)writer.println("RewardsAlignmentDiagnostic version=2 "+CaptureSessionRuntime.boundedRootScalars(history.replace('\n',' '),8000));
 }
 private SealedReadOnlyReceipt receipt=new SealedReadOnlyReceipt();
 private BingIdentityEvidence.Installed installed;
 String installedSummary(){return BingIdentityEvidence.summary(installed);}
 private String navigationStatus="确认页已打开；尚未进行导航。";
 /** Replace the caller in the same own task. No foreign task navigation or extras. */
 public static void launch(Activity product) {
  if(!(product.getApplication() instanceof ReviewVisibilityApplication))throw new SecurityException("Review navigation only");
  if(android.os.Build.VERSION.SDK_INT<34)throw new UnsupportedOperationException("API34 required");
  if(!OwnTaskHandoff.soleRoot(product))throw new IllegalStateException("One product root required");
  product.startActivity(new Intent(product,DormantCaptureConfirmationActivity.class));
  product.finish(); // Completion is checked later by exact sole-root task metadata, not this return.
 }
 void showStatus(String status,boolean terminal) {
  runOnUiThread(()->{if(!destroyed&&!isFinishing()&&!isDestroyed()&&(!terminalShown||terminal)) {
   terminalShown|=terminal;
   if(terminal)terminalSnapshot=new TerminalSnapshot(true,false,terminalSnapshot.phase().equals("NONE")&&runtime!=null?runtime.nativePhaseLabel():terminalSnapshot.phase(),false);
   if(returnReadIntent!=null||boundedIntent!=null||seriesIntent!=null||rootIntent!=null)RootCaptureUi.status(this,status,terminal,this::cancelAndFinish);
   else if(autonomousRun!=null)AutonomousCaptureUi.status(this,status,terminal,this::cancelAndFinish);
   else if(readOnlyRun!=null)CaptureConfirmationUi.readOnlyStatus(this,status,terminal,null,this::cancelAndFinish);
   else if(cardRun!=null)CaptureConfirmationUi.cardStatus(this,status,terminal,null,this::cancelAndFinish);
   else if(nativeSession==null)CaptureConfirmationUi.status(this,status,terminal,this::cancelAndFinish);
   else CaptureConfirmationUi.nativeStatus(this,status,terminal,null,this::cancelAndFinish);
  }});
 }
 void showFinalStatus(String candidate,String details){
  runOnUiThread(()->{if(!destroyed&&!isFinishing()&&!isDestroyed()){
   terminalShown=true;
   // Final acceptance is serialized with onStart, not a stale worker-side snapshot.
   boolean published=candidate!=null && (runtime==null||runtime.publicationCurrent()) && receipt.commit(ReviewVisibilityProvider.FENCE,visibilityLease,
       handoff.hiddenWithSoleTask(),candidate+"\n"+details);
   terminalSnapshot=new TerminalSnapshot(true,published,runtime==null?"NONE":runtime.nativePhaseLabel(),
       published&&sessionNeedsAfter()&&runtime!=null&&runtime.finishedAndReleased());
   String text=published?"已封存的历史回执（不是新授权）\n"+receipt.history():"没有可发布页面结果。\n"+details;
   if(autonomousRun!=null){
    if(published&&runtime!=null&&runtime.nativePhaseLabel().equals("BEFORE"))firstAutonomousReceipt=receipt.history();
    text=autonomousHistory(text);
    AutonomousCaptureUi.status(this,text,true,this::cancelAndFinish);
    if(published)restoreOwnTaskForAutonomousAfter();
    else {autonomousRun.cancel();autonomousRoute.cancel();}
   }
   else if(readOnlyRun!=null)CaptureConfirmationUi.readOnlyStatus(this,text,true,null,this::cancelAndFinish);
   else if(cardRun!=null)CaptureConfirmationUi.cardStatus(this,text,true,null,this::cancelAndFinish);
   else if(nativeSession==null)CaptureConfirmationUi.status(this,text,true,this::cancelAndFinish);
   else CaptureConfirmationUi.nativeStatus(this,text,true,null,this::cancelAndFinish);
  }});
 }
 public static final class LiveClick {
  private final DormantCaptureConfirmationActivity activity;
  private final Object request;
  private boolean spent;
  private final AutonomousStageRoute.Continuation continuation;
  private LiveClick(DormantCaptureConfirmationActivity activity) {this(activity,null);}
  private LiveClick(DormantCaptureConfirmationActivity activity,AutonomousStageRoute.Continuation continuation) {this.activity=activity;this.request=activity.request;this.continuation=continuation;}
  synchronized boolean consume(Activity exact,Object expected,Object owner,RealCaptureClaim.ProcessEpoch epoch) {
   if(spent)return false;spent=true;
   return exact==activity && expected==request && owner!=null && epoch!=null
      && activity.click==this && activity.isResumedForApproval();
  }
  synchronized void invalidate(){spent=true;}
 }
 Object requestIdentity(){return request;}
 boolean isResumedForApproval(){return resumed&&!destroyed&&!isFinishing()&&!isDestroyed()&&hasWindowFocus()&&OwnTaskHandoff.soleRoot(this);}
 @Override protected void onCreate(Bundle state){
  super.onCreate(state);
  if(state!=null || android.os.Process.myUid()<10000 || !Application.getProcessName().equals(getPackageName()+":capture_once")) {finish();return;}
  if(OwnStateSummaryUi.ACTIVE){OwnStateSummaryUi.choice(this,this::inspectOwnState,this::openAlignmentChoice,this::cancelAndFinish,this::openAlignmentDiagnosticChoice,()->openAlignmentCalibrationChoice(UiImageAlignmentScope.Calibration.FIRST),()->openAlignmentCalibrationChoice(UiImageAlignmentScope.Calibration.CONFIRMATION),()->openAlignmentCalibrationChoice(UiImageAlignmentScope.Calibration.TITLE_TIMING),this::openOverviewChoice);return;}
  installed=BingPackageMetadata.read(getPackageManager());
  if(!CaptureReleaseGate.configured()){showNavigation();return;}
  rikka.shizuku.Shizuku.addRequestPermissionResultListener(permissionListener);
  showCaptureChoices();
 }
 private volatile boolean ownStateCancelled;
 private void inspectOwnState(){
  if(consumed||!isResumedForApproval())return;
  consumed=true;terminalShown=false;ownStateCancelled=false;
  final long ownStateEnd=android.os.SystemClock.elapsedRealtime()+OwnStateSummary.MAX_MILLIS;
  OwnStateSummaryUi.running(this,this::cancelAndFinish);
  final String ownRoot=getApplicationInfo().dataDir;
  final int ownUid=getApplicationInfo().uid;
  new Thread(()->{
   String summary=OwnStateSummary.inspect(ownRoot,ownUid,android.os.Process.myUid(),
       android.os.SystemClock::elapsedRealtime,()->ownStateCancelled||android.os.SystemClock.elapsedRealtime()>=ownStateEnd||Thread.currentThread().isInterrupted());
   runOnUiThread(()->{if(destroyed||isFinishing()||isDestroyed())return;
    if(ownStateCancelled||android.os.SystemClock.elapsedRealtime()>=ownStateEnd||!isResumedForApproval())summaryRejected();
    else {returnReadReports.clear();returnReadReports.add(summary);terminalShown=true;
     terminalSnapshot=new TerminalSnapshot(true,false,"OWN_STATE",false);
     RootCaptureUi.returnReadStatus(this,returnReadReports,"V54本应用状态只读结束；不授予动作资格，不重新读取Bing。",this::cancelAndFinish);}
   });
  },"own-state-read-only").start();
 }
 private void summaryRejected(){terminalShown=true;terminalSnapshot=new TerminalSnapshot(true,false,"OWN_STATE",false);OwnStateSummaryUi.rejected(this,this::cancelAndFinish);}
 private void showCaptureChoices(){
  if(OwnStateSummaryUi.ACTIVE){if(!consumed){if(overviewSelected)showOverviewChoice();else if(alignmentSelected)showAlignmentChoice();else OwnStateSummaryUi.choice(this,this::inspectOwnState,this::openAlignmentChoice,this::cancelAndFinish,this::openAlignmentDiagnosticChoice,()->openAlignmentCalibrationChoice(UiImageAlignmentScope.Calibration.FIRST),()->openAlignmentCalibrationChoice(UiImageAlignmentScope.Calibration.CONFIRMATION),()->openAlignmentCalibrationChoice(UiImageAlignmentScope.Calibration.TITLE_TIMING),this::openOverviewChoice);}return;}
  boolean ready=false;
  try{
   ready=rikka.shizuku.Shizuku.pingBinder()&&!rikka.shizuku.Shizuku.isPreV11()
       &&rikka.shizuku.Shizuku.getUid()==0
       &&rikka.shizuku.Shizuku.checkSelfPermission()==android.content.pm.PackageManager.PERMISSION_GRANTED;
  }catch(RuntimeException unavailable){ready=false;}
  if(ReadOnlyCheckpoint.blocksLiveInput()){
   String status=ready?"Root来源就绪；重新核对本次实际Bing Main/Template来源。":"服务或权限未就绪。";
   boolean enabled=ready&&!returnReadSpent&&ReadOnlyCheckpoint.CURRENT.admissionCurrent(System.currentTimeMillis());
   switch(ReadOnlyCheckpoint.CURRENT.projection()){
    case LAYOUT->RootCaptureUi.checkpointReadChoice(this,status,enabled,this::confirmCheckpointRead,this::cancelAndFinish);
    case STRUCTURE->RootCaptureUi.structureReadChoice(this,status,enabled,this::confirmCheckpointRead,this::cancelAndFinish);
    case UI_STRUCTURE->RootCaptureUi.uiStructureChoice(this,status,enabled,this::confirmCheckpointRead,this::cancelAndFinish);
    case UI_READY->RootCaptureUi.uiBoundPointsChoice(this,status,enabled,this::confirmCheckpointRead,this::cancelAndFinish);
    case ENTRY_RECOVERY->RootCaptureUi.rewardsParentEntryChoice(this,status,enabled,this::confirmCheckpointRead,this::cancelAndFinish);
    case ENTRY_DIAGNOSTIC->RootCaptureUi.entryDiagnosticChoice(this,status,enabled,this::confirmCheckpointRead,this::cancelAndFinish);
   }return;
  }
  if(RootProgressLocateScope.ACTIVE){
   RootCaptureUi.progressLocateChoice(this,ready?"Root来源就绪；重新核对本次实际Bing Main/Template来源。":"服务或权限未就绪。",ready&&!returnReadSpent&&RootProgressLocateScope.admissionCurrent(System.currentTimeMillis()),this::confirmProgressLocate,this::cancelAndFinish);return;
  }
  if(ReadOnlyCheckpoint.CURRENT!=null){
   RootCaptureUi.checkpointReadChoice(this,ready?"Root来源就绪；重新核对原Bing task15，不沿用旧位置。":"服务或权限未就绪。",ready&&!returnReadSpent&&ReadOnlyCheckpoint.CURRENT.admissionCurrent(System.currentTimeMillis()),this::confirmCheckpointRead,this::cancelAndFinish);return;
  }
  if(RootProgressScanScope.ACTIVE){
   RootCaptureUi.progressScanChoice(this,ready?"Root来源就绪；所有已消费任务禁止重复。":"服务或权限未就绪。",ready&&!returnReadSpent&&RootProgressScanScope.admissionCurrent(System.currentTimeMillis()),this::confirmProgressScan,this::cancelAndFinish);return;
  }
  if(RootProgressReadScope.ACTIVE){
   RootCaptureUi.progressReadChoice(this,ready?"Root来源就绪，旧任务禁止重复。":"服务或权限未就绪。",ready&&!returnReadSpent&&RootProgressReadScope.admissionCurrent(System.currentTimeMillis()),this::confirmProgressRead,this::cancelAndFinish);return;
  }
  if(RootUniqueRefinedScope.ACTIVE){
   RootCaptureUi.uniquePendingChoice(this,ready?"已授权Root来源就绪；本轮已消费A/B同族、Quote、Puzzle、签到禁止自动重复。":"服务或权限未就绪，不新申请权限。",ready&&!boundedSpent&&RootUniqueRefinedScope.admissionCurrent(System.currentTimeMillis()),this::confirmUniquePending,this::cancelAndFinish);return;
  }
  if(RootStatusRecoveryScope.ACTIVE){
   RootCaptureUi.statusRecoveryChoice(this,ready?"已授权Root来源就绪；A/B均禁止重触。":"服务或权限未就绪，不新申请权限。",ready&&!returnReadSpent&&RootStatusRecoveryScope.admissionCurrent(System.currentTimeMillis()),this::confirmStatusRecovery,this::cancelAndFinish);return;
  }
  if(RootSelectedPendingScope.ACTIVE){
   RootCaptureUi.selectedPendingChoice(this,ready?"已授权Root来源就绪。":"服务或权限未就绪，不新申请权限。",ready&&!boundedSpent&&RootSelectedPendingScope.admissionCurrent(System.currentTimeMillis()),this::confirmSelectedPending,this::cancelAndFinish);return;
  }
  if(RootCurrentCardScope.ACTIVE){
   RootCaptureUi.currentReadChoice(this,ready?"已授权Root来源就绪。":"服务或权限未就绪，不新申请权限。",ready&&!returnReadSpent&&RootCurrentCardScope.admissionCurrent(System.currentTimeMillis()),this::confirmCurrentRead,this::cancelAndFinish);return;
  }
  if(RootReturnReadScope.ACTIVE){
   RootCaptureUi.returnReadChoice(this,ready?"已授权Root来源就绪。":"服务或权限未就绪，不新申请权限。",ready&&!returnReadSpent&&RootReturnReadScope.admissionCurrent(System.currentTimeMillis()),this::confirmReturnRead,this::cancelAndFinish);return;
  }
  if(RootBoundedBusinessScope.ACTIVE){
   RootCaptureUi.boundedChoice(this,ready?"已保留的 Shizuku root 权限就绪。":"服务或权限未就绪；不新申请权限。",
       ready&&!boundedSpent&&RootBoundedBusinessScope.admissionCurrent(System.currentTimeMillis()),this::confirmBounded,this::cancelAndFinish);return;
  }
  if(RootImageSeriesScope.ACTIVE){
   RootCaptureUi.seriesChoice(this,ready?"已保留的 Shizuku root 权限就绪。":"服务或权限未就绪；不新申请权限。",
       ready&&!seriesSpent&&RootImageSeriesScope.admissionCurrent(System.currentTimeMillis()),this::confirmSeries,this::cancelAndFinish);return;
  }
  permissionStatus=ready?(RootFamilyDiagnosticProfile.ACTIVE?"已保留的 Shizuku root 权限就绪；本次只审计同一PNG与同一Maa图像缓冲，1帧、1次OCR，业务输入硬关闭。":"已保留的 Shizuku root 权限就绪；本次仅固定来源检查、唯一指定卡触摸与至多一次原生返回。"):"已授权限或服务目前未就绪。本构建不重新申请权限。";
  boolean current=RootFamilyDiagnosticProfile.ACTIVE?RootFamilyDiagnosticProfile.admissionCurrent(System.currentTimeMillis())
    :RootCaptureScope.admissionCurrent(System.currentTimeMillis());
  RootCaptureUi.choice(this,(rootHistory==null?"":rootHistory+"\n\n")+permissionStatus,
    ready&&current&&!rootPreflightSpent,
    ready&&current&&!rootBusinessSpent&&CaptureSessionRuntime.rootPreflightAvailable(this,rootPreflightPass),
    this::confirmRootPreflight,this::confirmRootBusiness,this::cancelAndFinish);
 }
 private boolean sessionNeedsAfter(){return autonomousRun!=null?autonomousRun.needsAfter():readOnlyRun!=null?readOnlyRun.needsAfter():cardRun!=null?cardRun.needsAfter():nativeSession!=null&&nativeSession.needsAfter();}
 private void confirmAutonomousBefore(){
  if(RootUniqueRefinedScope.ACTIVE)return;showStatus("v24禁止旧Projection业务入口。",true);}
 boolean autonomousApprovalMatches(NativeAutonomousCardRun exact,NativeRewardCardSession.Phase phase,LiveClick incoming){
  if(exact==null||exact!=autonomousRun||incoming==null||incoming!=click||!exact.current()||!isResumedForApproval())return false;
  if(phase==NativeRewardCardSession.Phase.BEFORE)return incoming.continuation==null&&autonomousRoute!=null;
  return phase==NativeRewardCardSession.Phase.AFTER&&autonomousRoute!=null
      &&autonomousRoute.consume(incoming.continuation,exact,exact.current());
 }
 private boolean automaticAfterEligible(){
  return autonomousRun!=null&&autonomousRoute!=null&&canOfferAfter()&&autonomousRun.current()
      &&autonomousRun.returnReady()&&runtime!=null&&runtime.publicationCurrent()
      &&(ownRestoreDeadline<0||android.os.SystemClock.elapsedRealtime()<ownRestoreDeadline);
 }
 private void restoreOwnTaskForAutonomousAfter(){
  if(!automaticAfterEligible()||!autonomousRoute.requestRestore(autonomousRun,true))return;
  ownRestoreDeadline=android.os.SystemClock.elapsedRealtime()+5000L;
  try{
   if(android.os.Build.VERSION.SDK_INT<34)throw new SecurityException("Android14 autonomous profile required");
   if(!handoff.hiddenWithSoleTask())throw new SecurityException("Original hidden sole task required");
   android.app.ActivityManager manager=getSystemService(android.app.ActivityManager.class);
   java.util.List<android.app.ActivityManager.AppTask> tasks=manager.getAppTasks();
   if(tasks.size()!=1)throw new SecurityException("Exact own task unavailable");
   android.app.ActivityManager.AppTask own=tasks.get(0);android.app.ActivityManager.RecentTaskInfo info=own.getTaskInfo();
   if(!OwnTaskHandoff.shape(tasks.size(),getTaskId(),info.taskId,info.numActivities,isTaskRoot(),getComponentName().equals(info.baseActivity),getComponentName().equals(info.topActivity)))
     throw new SecurityException("Exact own root changed");
   // One request, never success evidence. Only actual onStart/onResume/focus can continue.
   own.moveToFront();
   stageDeadlines.postDelayed(()->{
    if(autonomousRoute!=null&&autonomousRoute.pending())stopAutonomous("恢复自身可见页未得到实际生命周期确认；停止，未请求第二帧。");
   },5000L);
  }catch(RuntimeException denied){stopAutonomous("系统未允许恢复本确认任务；停止，不以人工返回替代。");}
 }
 private void tryAutomaticAfter(){
  if(!automaticAfterEligible()||!isResumedForApproval())return;
  var continuation=autonomousRoute.claimSecond(autonomousRun,true,true);
  if(continuation==null)return;
  terminalShown=false;receipt=new SealedReadOnlyReceipt();click=new LiveClick(this,continuation);
  terminalSnapshot=new TerminalSnapshot(false,false,"AFTER",false);
  try{
   showStatus("第一阶段已真实返回并封存。依照原两阶段意图，自动请求第二张全新系统许可；不会再触摸任务。",false);
   runtime=CaptureSessionRuntime.startAutonomous(this,click,autonomousRun,NativeRewardCardSession.Phase.AFTER);
  }catch(RuntimeException denied){stopAutonomous("第二阶段新许可未能开始；停止，不重复任务或返回。");}
 }
 private String autonomousHistory(String current){
  return firstAutonomousReceipt!=null&&runtime!=null&&runtime.nativePhaseLabel().equals("AFTER")
      ?"第一阶段已封存历史（非新授权）\n"+firstAutonomousReceipt+"\n第二阶段\n"+current:current;
 }
 private void stopAutonomous(String message){
  if(autonomousRun!=null)autonomousRun.cancel();if(autonomousRoute!=null)autonomousRoute.cancel();if(click!=null)click.invalidate();if(runtime!=null)runtime.cancel();
  showStatus(message,true);
 }
 private void confirmCardBefore(){
  if(RootUniqueRefinedScope.ACTIVE)return;showStatus("本构建禁止业务输入。",true);}
 private void confirmCardAfter(){
  if(RootUniqueRefinedScope.ACTIVE)return;showStatus("本构建不恢复旧业务会话。",true);}
 private void confirmReadOnlyFirst(){
  if(RootUniqueRefinedScope.ACTIVE)return;
  if(consumed||!isResumedForApproval())return;
  try{
   readOnlyRun=ReadOnlyTwoStageRun.confirmed(this);consumed=true;click=new LiveClick(this);
   terminalSnapshot=new TerminalSnapshot(false,false,"BEFORE",false);
   showStatus("第一帧：只读当前Quote状态与生命周期。没有业务动作。",false);
   runtime=CaptureSessionRuntime.startReadOnly(this,click,readOnlyRun,ReadOnlyTwoStageRun.Phase.FIRST);
  }catch(RuntimeException denied){if(readOnlyRun!=null)readOnlyRun.cancel();if(click!=null)click.invalidate();showStatus("第一只读阶段未开始或已停止；不重试。",true);}
 }
 private void confirmReadOnlySecond(){
  if(RootUniqueRefinedScope.ACTIVE)return;
  if(readOnlyRun==null||!canOfferAfter()||!isResumedForApproval())return;
  terminalShown=false;receipt=new SealedReadOnlyReceipt();click=new LiveClick(this);
  terminalSnapshot=new TerminalSnapshot(false,false,"AFTER",false);
  try{
   showStatus("第二帧：全新系统许可与只读帧，允许相同画面，没有业务动作。",false);
   runtime=CaptureSessionRuntime.startReadOnly(this,click,readOnlyRun,ReadOnlyTwoStageRun.Phase.SECOND);
  }catch(RuntimeException denied){readOnlyRun.cancel();click.invalidate();showStatus("第二只读阶段未能开始；停止，不重试。",true);}
 }
 private void confirmCurrentStatus(){
  if(RootUniqueRefinedScope.ACTIVE)return;
  if(consumed||!isResumedForApproval())return;
  consumed=true;click=new LiveClick(this);
  terminalSnapshot=new TerminalSnapshot(false,false,"CURRENT_STATUS",false);
  try{
   showStatus("准备独立一帧只读核验。本构建已禁用输入，不会再次点击。",false);
   runtime=CaptureSessionRuntime.startCurrentStatus(this,click);
  }catch(RuntimeException denied){
   click.invalidate();showStatus("当前状态核验未能开始。没有输入，不复用或续期先前测试。",true);
  }
 }
 private void requestShizukuPermission(){
  if(consumed||permissionRequestSpent||!isResumedForApproval())return;
  try{
   if(!rikka.shizuku.Shizuku.pingBinder()||rikka.shizuku.Shizuku.getUid()!=0){showCaptureChoices();return;}
   if(NativeCheckInSession.permissionReady()){showCaptureChoices();return;}
   permissionRequestSpent=true;rikka.shizuku.Shizuku.requestPermission(24011);
  }catch(RuntimeException denied){showCaptureChoices();}
 }
 private void confirmNativeBefore(){
  if(RootUniqueRefinedScope.ACTIVE)return;showStatus("本构建禁止签到输入。",true);}
 private boolean canOfferAfter(){
  TerminalSnapshot value=terminalSnapshot;
  return value.terminal()&&value.published()&&value.phase().equals("BEFORE")&&value.needsAfter()
   &&receipt.history()!=null&&sessionNeedsAfter()
   &&runtime!=null&&runtime.finishedAndReleased();
 }
 private void confirmNativeAfter(){
  if(RootUniqueRefinedScope.ACTIVE)return;
  if(nativeSession==null||!canOfferAfter()||!isResumedForApproval())return;
  terminalShown=false;receipt=new SealedReadOnlyReceipt();click=new LiveClick(this);
  terminalSnapshot=new TerminalSnapshot(false,false,"AFTER",false);
  try{
   showStatus("请求全新的操作后只读帧；不会再次点击。",false);
   runtime=CaptureSessionRuntime.startNative(this,click,nativeSession,NativeCheckInScope.Phase.AFTER);
  }catch(RuntimeException denied){nativeSession.cancel();click.invalidate();showStatus("操作后观察未能开始；不能确认任务完成，也不再点击。",true);}
 }
 private void showNavigation(){CaptureConfirmationUi.navigation(this,navigationStatus+"\n"+installedSummary(),this::navigateWithoutCapture,this::cancelAndFinish);}
 private void navigateWithoutCapture(){
  if(navigationUsed||!isResumedForApproval())return;navigationUsed=true;
  android.util.Log.i("RewardsSafeNavigation","ACTUAL_CONFIRMATION_SOLE_ROOT task="+getTaskId()+" activities=1 base=confirmation top=confirmation");
  try{handoff.request(()->{visibilityLease=ReviewVisibilityProvider.FENCE.lease(this::navigationRevoked);
   navigationStatus=visibilityLease==null?"全应用隐藏状态未知；已拒绝。":"本确认页已成为唯一根，已收到 onStop；全应用生命周期 fence 已建立。没有采集。";
   android.util.Log.i("RewardsSafeNavigation",visibilityLease==null?"FENCE_REJECTED":"CROSS_COMPONENT_STOP_FENCED");
  },this::navigationRevoked);}catch(RuntimeException rejected){navigationStatus="转场被拒绝；没有采集。";showNavigation();}
 }
 private void navigationRevoked(){navigationStatus="自身页面重新启动或状态未知，旧 epoch 已永久撤销。没有采集。";android.util.Log.i("RewardsSafeNavigation","APP_EPOCH_REVOKED");}
 private void confirm(BusinessCaptureScope.Purpose purpose){
  if(RootUniqueRefinedScope.ACTIVE)return;
  CaptureReleaseGate.requireConfigured();
  if(consumed || !isResumedForApproval())return;
  consumed=true;click=new LiveClick(this);
  showStatus("等待 Android 系统同意。授权窗口从本次明确确认起最长 90 秒；超时只请求取消，不证明清理完成。",false);
  try {runtime=CaptureSessionRuntime.start(this,click,purpose);}catch(RuntimeException denied){click.invalidate();showStatus("无法开始本次识别（系统版本、进程或显示尺寸不受支持）。未获得可用结果；不能重试或恢复本次授权。",true);}
 }
 private void cancelAndFinish(){if(overviewRuntime!=null)overviewRuntime.cancel();if(alignmentRuntime!=null)alignmentRuntime.cancel();if(returnReadIntent!=null)returnReadIntent.cancel();if(boundedIntent!=null)boundedIntent.cancel();if(seriesIntent!=null)seriesIntent.cancel();if(rootIntent!=null)rootIntent.cancel();if(autonomousRoute!=null)autonomousRoute.cancel();if(autonomousRun!=null)autonomousRun.cancel();stageDeadlines.removeCallbacksAndMessages(null);if(readOnlyRun!=null)readOnlyRun.cancel();if(cardRun!=null)cardRun.cancel();if(nativeSession!=null)nativeSession.cancel();if(click!=null)click.invalidate();if(runtime!=null)runtime.cancel();finish();}
 /** Only ordinary superclass UI state may be saved. This route registers no
  * ActivityResultRegistry launcher: legacy request codes stay below 65536.
  * Never save the click, request, grant, pending consent Intent or runtime. */
 @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);}
 void requestHandoff(Runnable stillActive,Runnable ready,Runnable revoke){
  if(!pendingHandoff.compareAndSet(null,()->{stillActive.run();handoff.request(()->{visibilityLease=ReviewVisibilityProvider.FENCE.lease(revoke);if(visibilityLease==null){revoke.run();return;}ready.run();},revoke);}))
   throw new IllegalStateException("Handoff already pending");
  tryHandoff();
 }
 void clearPendingHandoff(){pendingHandoff.set(null);} // Scalar only, safe on deadline thread.
 private void tryHandoff(){if(isResumedForApproval()){
  Runnable action=pendingHandoff.getAndSet(null);if(action==null)return;
  try{action.run();}catch(RuntimeException failure){if(overviewRuntime!=null)overviewRuntime.cancel();if(alignmentRuntime!=null)alignmentRuntime.cancel();if(runtime!=null)runtime.cancel();}
 }}
 boolean hiddenWithSoleTask(){return handoff.hiddenWithSoleTask() && ReviewVisibilityProvider.FENCE.valid(visibilityLease);}
 @Override protected void onStart(){super.onStart();handoff.onStart();
  if(overviewSpent){
   if(overviewRuntime!=null&&!overviewFinished)overviewRuntime.cancel();
   if(overviewFinished)OverviewReviewUi.status(this,overviewHistory,this::cancelAndFinish);
   return;
  }
  if(alignmentSpent){
   if(alignmentRuntime!=null&&!alignmentFinished)alignmentRuntime.cancel();
   if(alignmentFinished)OwnStateSummaryUi.alignmentStatus(this,alignmentHistory,this::cancelAndFinish);
   return;
  }
  if(returnReadIntent!=null){if(!returnReadFinished)returnReadVisibilityRevoked(returnReadIntent);
   if(returnReadFinished){terminalShown=true;RootCaptureUi.returnReadStatus(this,returnReadReports,returnReadIntent.currentOnly()?"独立当前页只读历史回执；0Back，不是新授权。":"独立返回与当前状态历史回执；不是新授权。",this::cancelAndFinish);}return;}

  if(boundedIntent!=null){
   if(!boundedFinished)boundedVisibilityRevoked(boundedIntent);
   if(boundedFinished){terminalShown=true;RootCaptureUi.boundedStatus(this,boundedReports,"有界业务历史回执；不是新授权。",this::cancelAndFinish);}
   return;
  }
  if(seriesIntent!=null){
   if(!seriesFinished)seriesVisibilityRevoked(seriesIntent);
   if(seriesFinished){terminalShown=true;RootCaptureUi.seriesStatus(this,seriesReports,"只读序列历史审计；不是新授权。",this::cancelAndFinish);}
   return;
  }
  if(rootIntent!=null){
   if(runtime!=null&&runtime.finishedAndReleased()){
    handoff=new OwnTaskHandoff(this);handoff.onStart();visibilityLease=null;
    if(runtime.rootMode()==RootCaptureScope.Mode.PREFLIGHT&&rootPreflightPass!=null&&!rootBusinessSpent)showCaptureChoices();
    else RootCaptureUi.status(this,rootHistory==null?"没有新的已封存root结果。":rootHistory,true,this::cancelAndFinish);
   }else{if(runtime!=null)runtime.cancel();RootCaptureUi.status(this,"自身页面在root工作期间重新出现；已请求停止，仍需等待真实清理，不重试。",true,this::cancelAndFinish);}
   return;
  }
  if(canOfferAfter()){
   if(autonomousRun!=null&&!autonomousRoute.onStart(autonomousRun,automaticAfterEligible())){
    stopAutonomous("没有本次自动恢复对应的有效可见阶段；停止。");return;
   }
   // The old one-shot helper is revoked above. This new helper observes THIS actual
   // onStart, followed by the platform's onResume, for the fresh AFTER approval.
   handoff=new OwnTaskHandoff(this);handoff.onStart();visibilityLease=null;
   terminalShown=true;
   if(autonomousRun!=null)AutonomousCaptureUi.status(this,receipt.history()==null?"没有已封存回执。":receipt.history(),false,this::cancelAndFinish);
   else if(readOnlyRun!=null)CaptureConfirmationUi.readOnlyStatus(this,receipt.history()==null?"没有已封存回执。":receipt.history(),true,this::confirmReadOnlySecond,this::cancelAndFinish);
   else if(cardRun!=null)CaptureConfirmationUi.cardStatus(this,receipt.history()==null?"没有已封存回执。":receipt.history(),true,this::confirmCardAfter,this::cancelAndFinish);
   else CaptureConfirmationUi.nativeStatus(this,receipt.history()==null?"没有已封存回执。":receipt.history(),
       true,this::confirmNativeAfter,this::cancelAndFinish);
   return;
  }
  // In-flight candidates are revoked. Only scalars committed under the fence before reentry survive as history.
  if(visibilityLease!=null && !ReviewVisibilityProvider.FENCE.valid(visibilityLease)){
   if(!CaptureReleaseGate.configured())showNavigation();
   else if(autonomousRun!=null){terminalShown=true;AutonomousCaptureUi.status(this,autonomousHistory(receipt.history()==null?"未提交结果已撤销。":receipt.history()),true,this::cancelAndFinish);}
   else if(readOnlyRun!=null){terminalShown=true;CaptureConfirmationUi.readOnlyStatus(this,receipt.history()==null?"没有已封存回执。":receipt.history(),true,null,this::cancelAndFinish);}
   else if(cardRun!=null){terminalShown=true;CaptureConfirmationUi.cardStatus(this,receipt.history()==null?"没有已封存回执。":receipt.history(),true,null,this::cancelAndFinish);}
   else if(nativeSession!=null){terminalShown=true;CaptureConfirmationUi.nativeStatus(this,receipt.history()==null?"没有已封存回执。":receipt.history(),true,null,this::cancelAndFinish);}
   else {terminalShown=true;CaptureConfirmationUi.status(this,receipt.history()==null?"重新进入自身页面，未提交结果已撤销。没有可发布页面结果。":"已封存的历史只读回执（不是新授权）\n"+receipt.history(),true,this::cancelAndFinish);}
  }
 }
 @Override protected void onStop(){super.onStop();}
 void visibilityStopped(){handoff.onStop();}
 @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus){tryHandoff();tryAutomaticAfter();}}
 @Override protected void onResume(){super.onResume();resumed=true;handoff.onResume();tryHandoff();tryAutomaticAfter();if(!CaptureReleaseGate.configured())showNavigation();else if(!consumed)showCaptureChoices();}
 @Override protected void onPause(){ownStateCancelled=true;resumed=false;handoff.onPause();super.onPause();}
 @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(runtime!=null)runtime.onActivityResult(code,result,data);}
 @Override protected void onDestroy(){if(overviewRuntime!=null)overviewRuntime.cancel();if(alignmentRuntime!=null)alignmentRuntime.cancel();if(returnReadIntent!=null)returnReadIntent.cancel();if(seriesIntent!=null)seriesIntent.cancel();if(rootIntent!=null)rootIntent.cancel();rikka.shizuku.Shizuku.removeRequestPermissionResultListener(permissionListener);if(autonomousRoute!=null)autonomousRoute.cancel();if(autonomousRun!=null)autonomousRun.cancel();stageDeadlines.removeCallbacksAndMessages(null);if(readOnlyRun!=null)readOnlyRun.cancel();if(cardRun!=null)cardRun.cancel();if(nativeSession!=null)nativeSession.cancel();destroyed=true;if(boundedIntent!=null)boundedIntent.cancel();resumed=false;clearPendingHandoff();handoff.onDestroy();if(click!=null)click.invalidate();if(runtime!=null)runtime.cancel();super.onDestroy();}
}
