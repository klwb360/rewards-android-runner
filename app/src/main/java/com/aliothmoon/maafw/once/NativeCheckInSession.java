package com.aliothmoon.maafw.once;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;
import com.aliothmoon.maafw.rewards.DailyCheckInRegion;
import com.aliothmoon.maafw.rewards.LiveCheckInVerification;
import com.aliothmoon.maafw.rewards.RewardRegionGate;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import rikka.shizuku.Shizuku;

/** One visible native check-in session, reusing the real one-frame pipeline twice.
 * Only bounded scalars survive native cleanup. Never restored from Intent/disk.
 * Permission is persistent; action authority is separate, expiring and one-use.
 * Snapshot-based: not a promise that every pixel remains continuously unchanged. */
final class NativeCheckInSession {
    private static final AtomicBoolean CREATED=new AtomicBoolean();
    private final Object scope=new Object(), foregroundIdentity=new Object();
    private final String key=UUID.randomUUID().toString();
    private final Path files;
    private final Context context;
    private final long expires;
    private final AtomicBoolean revoked=new AtomicBoolean();
    private final boolean[] phaseSpent=new boolean[2];
    private BingForegroundSnapshot.Snapshot foreground;
    private volatile LiveCheckInVerification.Target target;
    private String beforeFrameHash;
    private volatile Capture activeCapture;
    private int baselineWidth,baselineHeight,baselineRotation;
    private volatile long actionElapsed=-1;
    private volatile boolean tapAttempted, needsAfter, finished;
    private String report="尚未执行。";
    private NativeCheckInSession(DormantCaptureConfirmationActivity activity) {
        context=activity.getApplicationContext();
        files=activity.getNoBackupFilesDir().toPath();
        long now=SystemClock.elapsedRealtime();
        long remaining=NativeCheckInScope.EXPIRES_UTC-System.currentTimeMillis();
        if(remaining<=0)throw new SecurityException("Expired");
        expires=now+Math.min(NativeCheckInScope.SESSION_TTL,remaining);
    }
    static NativeCheckInSession confirmed(DormantCaptureConfirmationActivity activity) {
        if(activity==null || !activity.isResumedForApproval()
            || !NativeCheckInScope.current(System.currentTimeMillis()) || !permissionReady()
            || !CREATED.compareAndSet(false,true)) throw new SecurityException("Visible one-use approval required");
        return new NativeCheckInSession(activity);
    }
    static boolean permissionReady() {
        try{return Shizuku.pingBinder()&&!Shizuku.isPreV11()&&Shizuku.getUid()==0
            &&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}
        catch(RuntimeException absent){return false;}
    }
    synchronized void reserve(NativeCheckInScope.Phase phase) {
        if(phase==null || revoked.get() || finished || phaseSpent[phase.ordinal()]
            || SystemClock.elapsedRealtime()>=expires
            || phase==NativeCheckInScope.Phase.AFTER&&!needsAfter)throw new SecurityException("Scope unavailable");
        phaseSpent[phase.ordinal()]=true;
    }
    RealCaptureClaim.BurnStore store(NativeCheckInScope.Phase phase) {
        return NativeCheckInScope.captureStore(files,phase);
    }
    Capture startCapture(NativeCheckInScope.Phase phase, int width,int height) {
        if(revoked.get() || finished || !phaseSpent[phase.ordinal()] || activeCapture!=null
            || !permissionReady() || SystemClock.elapsedRealtime()>=expires)return null;
        var observed=ReviewBingForeground.read(context);
        if(observed==null || !ReviewBingForeground.knownClean())return null;
        if(foreground==null && phase==NativeCheckInScope.Phase.BEFORE)foreground=observed;
        else if(!observed.equals(foreground))return null;
        Display display=context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if(display==null || display.getState()!=Display.STATE_ON)return null;
        if(phase==NativeCheckInScope.Phase.BEFORE){baselineWidth=width;baselineHeight=height;baselineRotation=display.getRotation();}
        else if(width!=baselineWidth||height!=baselineHeight||display.getRotation()!=baselineRotation)return null;
        // Lower bound precedes source acquisition. Its age overestimates actual frame age.
        return activeCapture=new Capture(this,phase,width,height,display.getRotation(),SystemClock.elapsedRealtime());
    }
    static final class Observation {
        private final Capture capture;
        private final LiveCheckInVerification.Target target;
        private final LiveCheckInVerification.Outcome verification;
        private final CheckInSuccessModal.Result modal;
        private final String frameHash;
        private final String summary;
        private Observation(Capture c,LiveCheckInVerification.Target t,LiveCheckInVerification.Outcome v,CheckInSuccessModal.Result m,String hash,String s){capture=c;target=t;verification=v;modal=m;frameHash=hash;summary=s;}
    }
    static final class Capture {
        private final NativeCheckInSession session;
        final NativeCheckInScope.Phase phase;
        final int width,height,rotation;
        final long acquiredLowerBound,frameId;
        private Capture(NativeCheckInSession s,NativeCheckInScope.Phase p,int w,int h,int r,long time){
            session=s;phase=p;width=w;height=h;rotation=r;acquiredLowerBound=time;frameId=p.ordinal()+1;
        }
        Prepared prepare(byte[] gray,int w,int h,java.util.List<FrameStructureEvidence.Line> lines,BooleanSupplier canceled) {
            if(w<=0||h<=0||w!=width||h!=height||gray==null||(long)w*h!=gray.length||lines==null||canceled==null||canceled.getAsBoolean()||session.revoked.get())return null;
            var k=new RewardRegionGate.Key(RewardRegionGate.Source.REAL_DISPLAY_NATIVE,session.key,frameId,"CHECKIN");
            Object binding=new Object();var b=new FrameStructureEvidence.Binding(binding,binding,binding,binding);
            if(!OcrRowsAssessment.assess(b,b,lines,w,h).shapeValid())return null;
            var modal=CheckInSuccessModal.observe(lines,w,h,canceled);
            if(canceled.getAsBoolean()||session.revoked.get())return null;
            if(modal!=null)return new Prepared(this,modal,pixelHash(gray)); // No CV, ROI, Frame or Target for an observed modal.
            var geometry=DailyCheckInRegion.observeGray8(k,gray,w,h,canceled);
            var frame=new LiveCheckInVerification.Frame(session.scope,session.foregroundIdentity,frameId,
                acquiredLowerBound,session.expires,geometry,new DailyCheckInRegion.TextEvidence(k,convert(lines)));
            return canceled.getAsBoolean()||session.revoked.get()?null:new Prepared(this,frame);
        }
    }
    /** Transient stack-local native preparation. Never retained beyond recognize(). */
    static final class Prepared {
        private final Capture capture;
        private final LiveCheckInVerification.Frame frame;
        private final com.aliothmoon.maafw.rewards.CheckInRingRoiPlan.Plan plan;
        private final LiveCheckInVerification.Target direct;
        private final CheckInSuccessModal.Result modal;
        private final String frameHash;
        private Prepared(Capture c,CheckInSuccessModal.Result m,String hash){capture=c;modal=java.util.Objects.requireNonNull(m);frameHash=hash;frame=null;direct=null;plan=null;}
        private Prepared(Capture c,LiveCheckInVerification.Frame f){
            capture=c;frame=f;modal=null;frameHash=f.geometry().pixelHash();
            direct=c.phase==NativeCheckInScope.Phase.BEFORE?LiveCheckInVerification.target(f,SystemClock.elapsedRealtime()):null;
            plan=c.phase==NativeCheckInScope.Phase.BEFORE&&direct==null
                ?com.aliothmoon.maafw.rewards.CheckInRingRoiPlan.prepare(f.geometry(),f.text()):null;
        }
        RewardRegionLocator.Box roi(){return plan==null?null:plan.roi();}
        Observation finish(java.util.List<FrameStructureEvidence.Line> roiRows,BooleanSupplier canceled){
            if(canceled.getAsBoolean()||capture.session.revoked.get())return null;
            long now=SystemClock.elapsedRealtime();
            String summary="scope="+capture.session.key+" phase="+capture.phase+" frameId="+capture.frameId
                +" fullDisplay="+capture.width+"x"+capture.height+" rotation="+capture.rotation
                +" sourceStartElapsed="+capture.acquiredLowerBound+" expiresElapsed="+capture.session.expires;
            if(modal!=null)return new Observation(capture,null,null,modal,frameHash,summary+" "+modal.summary()
                +" roiRequested=false targetCreated=false modalBeforePreventsInput="+(capture.phase==NativeCheckInScope.Phase.BEFORE));
            summary+=" fullframeVisual="+DailyCheckInRegion.previewVisualTarget(frame.geometry(),frame.text()).status()
                +" roiRequested="+(plan!=null)+" roiRows="+(roiRows==null?0:roiRows.size())
                +" nativeRows="+frame.text().rows().size()+" geometryStatus="+frame.geometry().status()
                +" surfaces="+frame.geometry().surfaces().size()+" rings="+frame.geometry().rings().size()
                +" ticks="+frame.geometry().ticks().size();
            if(plan!=null)summary+=" planPanel="+box(plan.panel())+" planRing="+box(plan.ring())
                +" planRoi="+box(plan.roi())+" nonlocalNumericRow="+plan.nonlocalNumericRow();
            if(roiRows!=null)for(var row:roiRows)summary+=" roiExact10="+row.text().equals("10")
                +" roiScore="+row.score()+" roiNativeBox="+row.x()+","+row.y()+","+(row.x()+row.width())+","+(row.y()+row.height());
            if(capture.phase==NativeCheckInScope.Phase.AFTER){
                var detail=LiveCheckInVerification.compareDetailed(capture.session.target,frame,capture.session.actionElapsed,now);
                return new Observation(capture,null,detail.outcome(),null,frameHash,
                    summary+afterScalars(capture.session.target,frame,detail));
            }
            var result=direct;
            if(result==null&&plan!=null&&roiRows!=null){
                var proof=com.aliothmoon.maafw.rewards.CheckInRingRoiPlan.accept(plan,convert(roiRows));
                result=LiveCheckInVerification.targetFromRoi(frame,proof,now);
            }
            if(result!=null)summary+=" panel="+box(result.panel())+" ring="+box(result.ring())
                +" targetXY="+result.x()+","+result.y()+" numberSource="+result.numberSource()
                +" numberBox="+box(result.nativeNumber());
            if(result==null)summary+=beforeFailureScalars(frame);
            return new Observation(capture,result,null,null,frameHash,summary);
        }
    }
    /** Failed-target diagnostics use only the already-computed frame, never another OCR/CV pass. */
    private static String beforeFailureScalars(LiveCheckInVerification.Frame frame) {
        RewardRegionLocator.Box panel=null,ring=null;int panels=0,rings=0,rows=0;
        for(var surface:frame.geometry().surfaces())for(var row:frame.text().rows())
            if(surface.complete()&&row.text().equals("签入")&&row.confidence()>=.85
                &&surface.box().contains(row.box())){panel=surface.box();panels++;}
        if(panels!=1)return " diagnosticTitlePanelCount="+panels;
        for(var candidate:frame.geometry().rings())if(panel.contains(candidate)){ring=candidate;rings++;}
        if(rings!=1)return " diagnosticTitlePanelCount=1 diagnosticPanelRingCount="+rings;
        StringBuilder out=new StringBuilder(" diagnosticPanel=").append(box(panel))
            .append(" diagnosticRing=").append(box(ring));
        for(var row:frame.text().rows())if(ring.overlaps(row.box())){
            rows++;if(rows>6)continue;
            String type=row.text().isEmpty()?"EMPTY":row.text().equals("10")?"ASCII10"
                :row.text().matches("[0-9]+")?"PURE_DIGITS":"OTHER";
            out.append(" diagnosticRowClass=").append(type).append(" diagnosticRowBox=").append(box(row.box()))
                .append(" diagnosticRowScore=").append(row.confidence());
        }
        return out.append(" diagnosticIntersectingRowCount=").append(rows)
            .append(" diagnosticRowsTruncated=").append(rows>6).toString();
    }
    /** Bounded diagnostics only. The comparator remains the sole completion decision.
     * Native rows are inspected here only for counts, never copied to the returned text. */
    private static String afterScalars(LiveCheckInVerification.Target before,
            LiveCheckInVerification.Frame after,LiveCheckInVerification.Verification detail) {
        StringBuilder out=new StringBuilder(" afterOutcome=").append(detail.outcome())
            .append(" afterPanelValid=").append(detail.panelValid())
            .append(" afterOldRingCount=").append(detail.oldRingCount())
            .append(" afterTargetTickCount=").append(detail.targetTickCount())
            .append(" afterLocalConflict=").append(detail.localConflict())
            .append(" localSemanticCoverage=").append(detail.panelValid()&&detail.targetTickCount()>=0
                ?"BOUNDED_OBSERVED_ROWS_NOT_EXHAUSTIVE":"NOT_EVALUATED");
        if(before==null||after==null||after.geometry()==null)return out.toString();
        var g=after.geometry();var ring=before.ring();var panel=before.panel();
        out.append(" beforePanel=").append(box(panel)).append(" beforeRing=").append(box(ring));
        int samePanel=0,panelTicks=0,localRows=0,lowLocalRows=0,ringDetails=0,tickDetails=0;
        for(var surface:g.surfaces())if(surface.complete()&&surface.box().equals(panel))samePanel++;
        for(var current:g.rings())if(ring.overlaps(current)&&ringDetails++<4)
            out.append(" afterOverlappingRing=").append(box(current));
        for(var tick:g.ticks()){
            if(panel.contains(tick.disk()))panelTicks++;
            if(ring.overlaps(tick.disk())&&tickDetails++<4)
                out.append(" afterTargetTickDisk=").append(box(tick.disk()))
                    .append(" afterTargetTickGlyph=").append(box(tick.glyph()))
                    .append(" afterTargetTickSimilarity=").append(tick.similarity());
        }
        if(after.text()!=null&&after.text().rows()!=null)for(var row:after.text().rows())
            if(row.box()!=null&&ring.overlaps(row.box())){
                localRows++;
                if(!Double.isFinite(row.confidence())||row.confidence()<.85)lowLocalRows++;
            }
        return out.append(" afterMatchingCompletePanels=").append(samePanel)
            .append(" afterPanelTickCount=").append(panelTicks)
            .append(" afterTargetOverlappingNativeRows=").append(localRows)
            .append(" afterLowConfidenceLocalRows=").append(lowLocalRows)
            .append(" afterDiagnosticGeometryTruncated=").append(ringDetails>4||tickDetails>4)
            .toString();
    }
    private static java.util.List<RewardRegionLocator.Row> convert(java.util.List<FrameStructureEvidence.Line> lines){
        var rows=new ArrayList<RewardRegionLocator.Row>();
        for(var r:lines)rows.add(new RewardRegionLocator.Row(r.text(),
            new RewardRegionLocator.Box(r.x(),r.y(),r.x()+r.width(),r.y()+r.height()),r.score(),RewardRegionLocator.Origin.STATIC_OCR));
        return rows;
    }
    private static String box(RewardRegionLocator.Box b){return b==null?"NONE":b.left()+","+b.top()+","+b.right()+","+b.bottom();}
    String acceptAfterCleanup(Capture capture,Observation observation,boolean released,
                                           BooleanSupplier ownUiHidden) {
        if(!ownsObservation(capture,activeCapture,observation)||capture.session!=this
            ||!released||!ReviewBingForeground.knownClean()||revoked.get()||ownUiHidden==null
            ||!ownUiHidden.getAsBoolean()||SystemClock.elapsedRealtime()>=expires
            ||!NativeCheckInScope.current(System.currentTimeMillis())) {
            cancel();return report="原生动作已阻止：观察、来源或清理条件未满足。";
        }
        activeCapture=null;
        if(capture.phase==NativeCheckInScope.Phase.AFTER){
            boolean actionAndTiming=capture.frameId==2&&target!=null
                &&LiveCheckInVerification.matchesForAction(target,scope,foregroundIdentity,1,SystemClock.elapsedRealtime())
                &&target.expiresElapsed()==expires&&actionElapsed>=target.capturedElapsed()
                &&target.width()==capture.width&&target.height()==capture.height&&capture.rotation==baselineRotation
                &&distinctFrameHashes(beforeFrameHash,observation.frameHash)
                &&afterTimingEligible(tapAttempted,needsAfter,true,actionElapsed,
                capture.acquiredLowerBound,SystemClock.elapsedRealtime(),expires);
            needsAfter=false;finished=true;
            if(!actionAndTiming||!completionSnapshotValid(capture,ownUiHidden))
                return report="操作后帧的动作顺序、新鲜性、来源或窗口条件未满足，不能确认完成。";
            return report=observation.summary+"\n"+(observation.modal!=null&&observation.modal.complete()
                ?"新鲜操作后原生帧显示签到成功弹层，完成态已观察到。未选择提醒或关闭，未核总积分，不证明此次点击是唯一原因。"
                :observation.verification==LiveCheckInVerification.Outcome.OBSERVED_SAME_LOCATION_MARKED_COMPLETE_UNATTRIBUTED
                ?"同一签到面板、同一圈位置已出现唯一勾选，任务完成态已观察到。未核总积分，也不证明此次点击是唯一原因。"
                :"未观察到明确签到完成态；不再点击，不自动重试。");
        }
        if(observation.modal!=null){
            finished=true;target=null;needsAfter=false;
            if(!completionSnapshotValid(capture,ownUiHidden))return report="弹层观察的来源或新鲜性未满足；未生成目标、未点击。";
            return report=observation.summary+"\n"+(observation.modal.complete()
                ?"点击前已观察到明确签到成功弹层，本次只读结束；没有生成输入目标，也没有再次签到。"
                :"点击前存在尚不明确的成功弹层候选，本次只读结束；没有生成输入目标，也没有点击。");
        }
        target=observation.target;beforeFrameHash=observation.frameHash;
        if(target==null){finished=true;return report=observation.summary+"\n原生目标不明确（圈、数字或完整面板有冲突）；未点击。";}
        int x=(int)Math.round(target.x()),y=(int)Math.round(target.y());
        var tap=new ReviewShizukuTapActor.TapTarget(x,y,capture.width,capture.height,capture.rotation);
        var actor=ReviewShizukuTapActor.forApprovedBusiness20261111(context);
        ReviewShizukuTapActor.Result result=actor.tap(tap,new ReviewShizukuTapActor.DispatchGate(){
            public boolean consume(ReviewShizukuTapActor.TapTarget exact)throws Exception{
                if(tapAttempted)return false;
                tapAttempted=true; // Any gate attempt is spent, including failed/uncertain burn.
                if(exact!=tap||!validSnapshot(capture,ownUiHidden))return false;
                NativeCheckInScope.burn(files,"TAP","fullDisplay="+capture.width+"x"+capture.height+" maxAccountActions=1\n");
                return true;
            }
            public boolean stillValid(ReviewShizukuTapActor.TapTarget exact) {
                if(exact!=tap||!tapAttempted||!validSnapshot(capture,ownUiHidden))return false;
                actionElapsed=SystemClock.elapsedRealtime();return true;
            }
        });
        needsAfter=result.invocationAttempted() && result.kind()==ReviewShizukuTapActor.Kind.COMMAND_EXITED && result.exitCode()==0 && !revoked.get();
        if(!needsAfter)finished=true;
        return report=observation.summary+"\nactorKind="+result.kind()+" actorReason="+result.reason()
            +" exit="+result.exitCode()+" invocationAttempted="+result.invocationAttempted()
            +" authorityConsumed="+result.authorityConsumed()+" actionElapsed="+actionElapsed+"\n"+(needsAfter
            ?"已发出一次签到触摸并收到命令退出；这不是完成证明。需要新一帧核验签到成功弹层或同一位置的勾选。"
            :"没有取得可核验的一次动作回执。状态="+result.kind()+"；不重试，也不宣称任务完成。");
    }
    private boolean validSnapshot(Capture capture,BooleanSupplier ownUiHidden) {
        if(revoked.get()||finished||!ownUiHidden.getAsBoolean()||!permissionReady()
            ||!LiveCheckInVerification.matchesForAction(target,scope,foregroundIdentity,1,SystemClock.elapsedRealtime())
            ||!NativeCheckInScope.current(System.currentTimeMillis())
            ||!NativeCheckInScope.fresh(capture.acquiredLowerBound,SystemClock.elapsedRealtime(),expires))return false;
        var fresh=ReviewBingForeground.read(context);
        return foreground.equals(fresh)&&ReviewBingForeground.knownClean()&&!revoked.get()&&ownUiHidden.getAsBoolean()
            &&NativeCheckInScope.current(System.currentTimeMillis())
            &&NativeCheckInScope.fresh(capture.acquiredLowerBound,SystemClock.elapsedRealtime(),expires);
    }
    private static String pixelHash(byte[] gray){
        byte[] digest;
        try{digest=java.security.MessageDigest.getInstance("SHA-256").digest(gray);}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        try{StringBuilder out=new StringBuilder(64);for(byte b:digest){out.append(Character.forDigit((b>>>4)&15,16));out.append(Character.forDigit(b&15,16));}return out.toString();}
        finally{java.util.Arrays.fill(digest,(byte)0);}
    }
    static boolean distinctFrameHashes(String before,String after){
        return before!=null&&after!=null&&before.length()==64&&after.length()==64&&!before.equals(after);
    }
    static boolean ownsObservation(Capture capture,Capture active,Observation observation){
        return capture!=null&&capture==active&&observation!=null&&observation.capture==capture;
    }
    static boolean afterTimingEligible(boolean attempted,boolean awaiting,boolean hasBefore,long action,
                                       long captured,long now,long expires){
        return attempted&&awaiting&&hasBefore&&action>=0&&captured>action
            &&NativeCheckInScope.fresh(captured,now,expires);
    }
    private boolean completionSnapshotValid(Capture capture,BooleanSupplier hidden){
        if(revoked.get()||!hidden.getAsBoolean()||!NativeCheckInScope.current(System.currentTimeMillis())
            ||!NativeCheckInScope.fresh(capture.acquiredLowerBound,SystemClock.elapsedRealtime(),expires))return false;
        var fresh=ReviewBingForeground.read(context);
        if(!java.util.Objects.equals(foreground,fresh)||fresh==null||!ReviewBingForeground.knownClean())return false;
        if(android.os.Build.VERSION.SDK_INT<30)return false;
        var manager=context.getSystemService(DisplayManager.class);
        var display=manager==null?null:manager.getDisplay(Display.DEFAULT_DISPLAY);
        if(display==null||display.getState()!=Display.STATE_ON||display.getRotation()!=capture.rotation)return false;
        var windows=context.createDisplayContext(display).getSystemService(android.view.WindowManager.class);
        if(windows==null)return false;
        var bounds=windows.getMaximumWindowMetrics().getBounds();
        return bounds.left==0&&bounds.top==0&&bounds.width()==capture.width&&bounds.height()==capture.height
            &&!revoked.get()&&hidden.getAsBoolean()&&NativeCheckInScope.current(System.currentTimeMillis())
            &&NativeCheckInScope.fresh(capture.acquiredLowerBound,SystemClock.elapsedRealtime(),expires);
    }
    long remainingForAfterMillis(){
        return needsAfter()?Math.max(0,expires-SystemClock.elapsedRealtime()):0;
    }
    boolean needsAfter(){return needsAfter&&!revoked.get()&&!finished&&NativeCheckInScope.admissionCurrent(System.currentTimeMillis())&&SystemClock.elapsedRealtime()<expires;}
    void cancel(){revoked.set(true);}
}
