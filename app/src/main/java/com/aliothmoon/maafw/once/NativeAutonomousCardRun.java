package com.aliothmoon.maafw.once;

import android.content.Context;
import android.os.SystemClock;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One visible run of the exact first trivia body, not a generic webpage agent.
 * Runtime owns capture/FGS cleanup and trusted Activity lifecycle. This wrapper owns
 * source identity, one TAP/RETURN budget and a native return-ready gate for AFTER. */
final class NativeAutonomousCardRun {
    private static final AtomicBoolean CREATED=new AtomicBoolean();
    private final java.util.function.LongSupplier elapsed,wall;
    private final boolean testOnly;
    private final boolean bounded;private final boolean selectedPending,uniquePending;
    private volatile NativeRewardCardSession.PreparedCapture currentPrepared;
    private final int[] preparedAttempts=new int[2];
    private long preparedSequence,phaseStart;
    private volatile boolean businessAccepting;
    private final NativeRewardCardSession.Authority authority;
    private final Context context;
    private final Path files;
    private final String runId=UUID.randomUUID().toString();
    private final long expires;
    private final AtomicBoolean revoked=new AtomicBoolean();
    private final boolean[] reserved=new boolean[2];
    private final ReviewBingNavigation metadata;
    private final FixturePorts fixturePorts;
    private final ReviewShizukuBackActor backActor;
    private final NativeRewardCardSession session;
    private volatile BingNavigationSnapshot.Ready original;
    private volatile boolean operationActive,accepted,resourcesClean=true,returned,afterReserved;
    private volatile long phaseEnd,lastPhaseEnd,afterLower=-1;
    private volatile NativeRewardCardSession.Phase phase,lastPhase;
    private volatile String terminalSummary="autonomousTerminal=NOT_ACCEPTED";
    private NativeAutonomousCardRun(DormantCaptureConfirmationActivity activity){
        elapsed=SystemClock::elapsedRealtime;wall=System::currentTimeMillis;testOnly=false;bounded=false;selectedPending=false;uniquePending=false;fixturePorts=null;
        context=activity.getApplicationContext();files=activity.getNoBackupFilesDir().toPath();
        long now=now(),remaining=AutonomousRewardCardScope.EXPIRES_UTC-utc();
        if(remaining<=0)throw new SecurityException("Autonomous grant expired");expires=now+Math.min(AutonomousRewardCardScope.SESSION_TTL,remaining);
        var installed=BingPackageMetadata.read(context.getPackageManager());
        if(installed.state()!=BingIdentityEvidence.PackageState.METADATA_OBSERVED)throw new SecurityException("Bing metadata unavailable");
        int bingUid,systemUid;
        try{bingUid=context.getPackageManager().getApplicationInfo("com.microsoft.bing",0).uid;
            systemUid=context.getPackageManager().getApplicationInfo("com.android.systemui",0).uid;}
        catch(android.content.pm.PackageManager.NameNotFoundException missing){throw new SecurityException("Exact native package metadata unavailable");}
        metadata=new ReviewBingNavigation(context,installed,bingUid,systemUid);
        backActor=ReviewShizukuBackActor.forApprovedBusiness20261111(context);
        authority=createAuthority();
        session=new NativeRewardCardSession(runId,expires,authority,ReviewShizukuTapActor.forApprovedBusiness20261111(context),
            LiveRewardCardObservation.TargetSpec.moreTriviaChallenge(),createReturner());
    }
    private NativeAutonomousCardRun(DormantCaptureConfirmationActivity activity,long parentEnd){this(activity,parentEnd,false);}
    private NativeAutonomousCardRun(DormantCaptureConfirmationActivity activity,long parentEnd,boolean selected){this(activity,parentEnd,selected,false);}
    private NativeAutonomousCardRun(DormantCaptureConfirmationActivity activity,long parentEnd,boolean selected,boolean unique){
        elapsed=SystemClock::elapsedRealtime;wall=System::currentTimeMillis;testOnly=false;bounded=true;selectedPending=selected;uniquePending=unique;fixturePorts=null;
        context=activity.getApplicationContext();files=activity.getNoBackupFilesDir().toPath();long now=now();
        if(parentEnd<=now||parentEnd-now>RootBoundedBusinessScope.SESSION_TTL||!(unique?RootUniqueRefinedScope.admissionCurrent(utc()):selected?RootSelectedPendingScope.admissionCurrent(utc()):RootBoundedBusinessScope.admissionCurrent(utc())))
            throw new SecurityException("New bounded parent required");
        expires=Math.min(parentEnd,unique?RootUniqueRefinedScope.sessionDeadline(now,utc()):selected?RootSelectedPendingScope.sessionDeadline(now,utc()):RootBoundedBusinessScope.sessionDeadline(now,utc()));
        var installed=BingPackageMetadata.read(context.getPackageManager());
        if(installed.state()!=BingIdentityEvidence.PackageState.METADATA_OBSERVED)throw new SecurityException("Bing metadata unavailable");
        int bingUid,systemUid;try{bingUid=context.getPackageManager().getApplicationInfo("com.microsoft.bing",0).uid;
            systemUid=context.getPackageManager().getApplicationInfo("com.android.systemui",0).uid;
        }catch(android.content.pm.PackageManager.NameNotFoundException missing){throw new SecurityException("Exact native package metadata unavailable");}
        metadata=unique?ReviewBingNavigation.forUniquePending(context,installed,bingUid,systemUid,activity.getTaskId(),activity.getComponentName().flattenToString()):selected?ReviewBingNavigation.forSelectedPending(context,installed,bingUid,systemUid,activity.getTaskId(),activity.getComponentName().flattenToString()):new ReviewBingNavigation(context,installed,bingUid,systemUid);backActor=ReviewShizukuBackActor.forApprovedBusiness20261111(context);
        authority=createAuthority();session=new NativeRewardCardSession(runId,expires,authority,ReviewShizukuTapActor.forApprovedBusiness20261111(context),
            unique?LiveRewardCardObservation.TargetSpec.moreUniquePending():selected?LiveRewardCardObservation.TargetSpec.moreTriviaSelectedPendingB():LiveRewardCardObservation.TargetSpec.moreTriviaChallengeBounded(),createReturner());
    }
    static NativeAutonomousCardRun confirmedUniquePending(DormantCaptureConfirmationActivity activity,long parentEnd){
        if(activity==null||!activity.isResumedForApproval()||!RootUniqueRefinedScope.ACTIVE||!RootUniqueRefinedScope.admissionCurrent(System.currentTimeMillis())
            ||!NativeCheckInSession.permissionReady()||!CREATED.compareAndSet(false,true))throw new SecurityException("Fresh unique-pending approval required");
        return new NativeAutonomousCardRun(activity,parentEnd,false,true);
    }
    static NativeAutonomousCardRun confirmedSelectedPending(DormantCaptureConfirmationActivity activity,long parentEnd){
        if(activity==null||!activity.isResumedForApproval()||!RootSelectedPendingScope.ACTIVE||!RootSelectedPendingScope.admissionCurrent(System.currentTimeMillis())
            ||!NativeCheckInSession.permissionReady()||!CREATED.compareAndSet(false,true))throw new SecurityException("Fresh exclusive selected-pending approval required");
        return new NativeAutonomousCardRun(activity,parentEnd,true);
    }
    static NativeAutonomousCardRun confirmedBounded(DormantCaptureConfirmationActivity activity,long parentEnd){
        if(activity==null||!activity.isResumedForApproval()||!RootBoundedBusinessScope.ACTIVE
            ||!RootBoundedBusinessScope.admissionCurrent(System.currentTimeMillis())||!NativeCheckInSession.permissionReady()
            ||!CREATED.compareAndSet(false,true))throw new SecurityException("New focused bounded business approval required");
        return new NativeAutonomousCardRun(activity,parentEnd);
    }
    /** Contract-only seam: no Context/files/metadata and only failing actor backends.
     * Uses the same real Run authority delegate and reserve/deadline logic as production.
     * It cannot acquire a frame, burn a real slot or execute privileged input. */
    NativeAutonomousCardRun(java.util.function.LongSupplier elapsed,java.util.function.LongSupplier wall){
        if(elapsed==null||wall==null)throw new IllegalArgumentException("test clocks");
        this.elapsed=elapsed;this.wall=wall;testOnly=true;bounded=false;selectedPending=false;uniquePending=false;context=null;files=null;metadata=null;fixturePorts=null;
        long remaining=AutonomousRewardCardScope.EXPIRES_UTC-utc();expires=now()+Math.min(AutonomousRewardCardScope.SESSION_TTL,remaining);
        backActor=new ReviewShizukuBackActor(()->{throw new SecurityException("No test back backend");});
        authority=createAuthority();
        session=new NativeRewardCardSession(runId,expires,authority,new ReviewShizukuTapActor(()->{throw new SecurityException("No test tap backend");}),
            LiveRewardCardObservation.TargetSpec.moreTriviaChallenge(),createReturner());
    }
    /** Explicit non-live ports. No Context, live actor factory, persistent store or OS command. */
    interface FixturePorts {
        BingNavigationSnapshot.Shape shape();
        boolean authorityMatches(BingNavigationSnapshot.Shape shape);
        BingNavigationSnapshot.Result observe(BingNavigationSnapshot.Shape shape);
        boolean knownClean();
        void consumeTap(ReviewShizukuTapActor.TapTarget target)throws Exception;
        void consumeReturn(ReviewShizukuBackActor.BackTarget target)throws Exception;
        void tapped(ReviewShizukuTapActor.TapTarget target);
        void backed(ReviewShizukuBackActor.BackTarget target);
        default BingNavigationSnapshot.Result observeTimedReturn(BingNavigationSnapshot.Ready original,long now){return BingNavigationSnapshot.Result.unknown();}
        default void pollPause() throws InterruptedException {}
        default BingNavigationSnapshot.TimedTaskGuard timedTaskGuard(BingNavigationSnapshot.Ready original,long now){return BingNavigationSnapshot.TimedTaskGuard.notObserved();}
    }
    static NativeAutonomousCardRun fixture(java.util.function.LongSupplier elapsed,java.util.function.LongSupplier wall,FixturePorts ports){
        return new NativeAutonomousCardRun(elapsed,wall,ports);
    }
    private NativeAutonomousCardRun(java.util.function.LongSupplier elapsed,java.util.function.LongSupplier wall,FixturePorts ports){
        if(elapsed==null||wall==null||ports==null||ports.shape()==null)throw new IllegalArgumentException("non-live fixture ports");
        this.elapsed=elapsed;this.wall=wall;testOnly=true;bounded=false;selectedPending=false;uniquePending=false;context=null;files=null;metadata=null;fixturePorts=ports;
        long remaining=AutonomousRewardCardScope.EXPIRES_UTC-utc();expires=now()+Math.min(50_000L,remaining);
        backActor=new ReviewShizukuBackActor(()->new ReviewShizukuBackActor.Session(){
            public ReviewShizukuBackActor.DisplayShape display(){var s=ports.shape();return new ReviewShizukuBackActor.DisplayShape(s.width(),s.height(),s.rotation());}
            public boolean stillReady(){return ports.knownClean()&&ports.authorityMatches(ports.shape());}
            public ReviewShizukuBackActor.PendingBack dispatch(ReviewShizukuBackActor.BackTarget target,ReviewShizukuBackActor.FinalGate gate)throws Exception{
                gate.requireValid();ports.backed(target);
                return new ReviewShizukuBackActor.PendingBack(){public boolean waitForExit(long millis){return true;}public int exitCode(){return 0;}public void destroy(){}public void close(){}};
            }
        });
        authority=createAuthority();
        ReviewShizukuTapActor tap=new ReviewShizukuTapActor(()->new ReviewShizukuTapActor.Session(){
            public ReviewShizukuTapActor.DisplayShape display(){var s=ports.shape();return new ReviewShizukuTapActor.DisplayShape(s.width(),s.height(),s.rotation());}
            public boolean stillReady(){return ports.knownClean()&&ports.authorityMatches(ports.shape());}
            public ReviewShizukuTapActor.PendingTap dispatch(ReviewShizukuTapActor.TapTarget target,ReviewShizukuTapActor.FinalGate gate)throws Exception{
                gate.requireValid();ports.tapped(target);
                return new ReviewShizukuTapActor.PendingTap(){public boolean waitForExit(long millis){return true;}public int exitCode(){return 0;}public void destroy(){}public void close(){}};
            }
        });
        session=new NativeRewardCardSession(runId,expires,authority,tap,LiveRewardCardObservation.TargetSpec.moreTriviaChallenge(),createReturner());
    }
    static NativeAutonomousCardRun fixtureBounded(java.util.function.LongSupplier elapsed,java.util.function.LongSupplier wall,FixturePorts ports,long parentEnd){
        return new NativeAutonomousCardRun(elapsed,wall,ports,parentEnd);
    }
    private NativeAutonomousCardRun(java.util.function.LongSupplier elapsed,java.util.function.LongSupplier wall,FixturePorts ports,long parentEnd){this(elapsed,wall,ports,parentEnd,false);}
    static NativeAutonomousCardRun fixtureSelectedPending(java.util.function.LongSupplier elapsed,java.util.function.LongSupplier wall,FixturePorts ports,long parentEnd){return new NativeAutonomousCardRun(elapsed,wall,ports,parentEnd,true);}
    static NativeAutonomousCardRun fixtureSelectedPendingTimed(java.util.function.LongSupplier elapsed,java.util.function.LongSupplier wall,FixturePorts ports,long parentEnd){return new NativeAutonomousCardRun(elapsed,wall,ports,parentEnd,true,true);}
    private boolean timedFixture;
    private NativeAutonomousCardRun(java.util.function.LongSupplier elapsed,java.util.function.LongSupplier wall,FixturePorts ports,long parentEnd,boolean selected){this(elapsed,wall,ports,parentEnd,selected,false);}
    static NativeAutonomousCardRun fixtureUniquePending(java.util.function.LongSupplier elapsed,java.util.function.LongSupplier wall,FixturePorts ports,long parentEnd){return new NativeAutonomousCardRun(elapsed,wall,ports,parentEnd,false,true,true);}
    private NativeAutonomousCardRun(java.util.function.LongSupplier elapsed,java.util.function.LongSupplier wall,FixturePorts ports,long parentEnd,boolean selected,boolean timed){this(elapsed,wall,ports,parentEnd,selected,timed,false);}
    private NativeAutonomousCardRun(java.util.function.LongSupplier elapsed,java.util.function.LongSupplier wall,FixturePorts ports,long parentEnd,boolean selected,boolean timed,boolean unique){
        timedFixture=timed;
        if(elapsed==null||wall==null||ports==null||ports.shape()==null)throw new IllegalArgumentException("non-live bounded ports");
        this.elapsed=elapsed;this.wall=wall;testOnly=true;bounded=true;selectedPending=selected;uniquePending=unique;context=null;files=null;metadata=null;fixturePorts=ports;
        long now=now();if(parentEnd<=now||parentEnd-now>50_000L||!(unique?RootUniqueRefinedScope.current(utc()):selected?RootSelectedPendingScope.current(utc()):RootBoundedBusinessScope.current(utc())))throw new SecurityException("bounded fixture TTL");
        expires=parentEnd;
        backActor=new ReviewShizukuBackActor(()->new ReviewShizukuBackActor.Session(){
            public ReviewShizukuBackActor.DisplayShape display(){var s=ports.shape();return new ReviewShizukuBackActor.DisplayShape(s.width(),s.height(),s.rotation());}
            public boolean stillReady(){return ports.knownClean()&&ports.authorityMatches(ports.shape());}
            public ReviewShizukuBackActor.PendingBack dispatch(ReviewShizukuBackActor.BackTarget target,ReviewShizukuBackActor.FinalGate gate)throws Exception{
                gate.requireValid();ports.backed(target);return new ReviewShizukuBackActor.PendingBack(){public boolean waitForExit(long millis){return true;}public int exitCode(){return 0;}public void destroy(){}public void close(){}};
            }
        });
        authority=createAuthority();ReviewShizukuTapActor tap=new ReviewShizukuTapActor(()->new ReviewShizukuTapActor.Session(){
            public ReviewShizukuTapActor.DisplayShape display(){var s=ports.shape();return new ReviewShizukuTapActor.DisplayShape(s.width(),s.height(),s.rotation());}
            public boolean stillReady(){return ports.knownClean()&&ports.authorityMatches(ports.shape());}
            public ReviewShizukuTapActor.PendingTap dispatch(ReviewShizukuTapActor.TapTarget target,ReviewShizukuTapActor.FinalGate gate)throws Exception{
                gate.requireValid();ports.tapped(target);return new ReviewShizukuTapActor.PendingTap(){public boolean waitForExit(long millis){return true;}public int exitCode(){return 0;}public void destroy(){}public void close(){}};
            }
        });
        session=new NativeRewardCardSession(runId,expires,authority,tap,unique?LiveRewardCardObservation.TargetSpec.moreUniquePending():selected?LiveRewardCardObservation.TargetSpec.moreTriviaSelectedPendingB():LiveRewardCardObservation.TargetSpec.moreTriviaChallengeBounded(),createReturner());
    }
    boolean uniquePendingOnly(){return uniquePending;}
    boolean selectedPendingOnly(){return selectedPending;}
    private int sourceTaskId(){return uniquePending?RootUniqueRefinedScope.SOURCE_TASK_ID:RootSelectedPendingScope.SOURCE_TASK_ID;}
    boolean boundedOnly(){return bounded;}
    boolean resourcesKnownClosed(){return resourcesClean&&!businessAccepting&&currentPrepared==null;}
    boolean fixtureOnly(){return testOnly;}
    private boolean metadataMatches(BingNavigationSnapshot.Shape shape){return fixturePorts!=null?fixturePorts.authorityMatches(shape):metadata!=null&&metadata.authorityMatches(shape);}
    private boolean timedStrategy(){return (selectedPending||uniquePending)&&(!testOnly||timedFixture);}
    BingNavigationSnapshot.Ready timedAfterSource(){return timedStrategy()&&returned&&current()?original:null;}
    private BingNavigationSnapshot.Result timedReturnRead(BingNavigationSnapshot.Ready source){return fixturePorts!=null?fixturePorts.observeTimedReturn(source,now()):metadata==null?BingNavigationSnapshot.Result.unknown():metadata.readTimedReturn(source.profile().shape(),source);}
    private BingNavigationSnapshot.Result metadataRead(BingNavigationSnapshot.Shape shape){
        if(timedStrategy()&&returned&&phase==NativeRewardCardSession.Phase.AFTER)return timedReturnRead(original);
        return fixturePorts!=null?fixturePorts.observe(shape):metadata==null?BingNavigationSnapshot.Result.unknown():(selectedPending||uniquePending)?metadata.readSelected(shape,original):metadata.read(shape);
    }
    private boolean metadataKnownClean(){return fixturePorts!=null?fixturePorts.knownClean():ReviewBingNavigation.knownClean();}
    private long now(){return elapsed.getAsLong();}
    private long utc(){return wall.getAsLong();}
    private NativeBingReturnController createReturner(){var returnPorts=new NativeBingReturnController.Ports(){
            public long elapsedRealtime(){return now();}
            public boolean authorityCurrent(BingNavigationSnapshot.Shape shape){return operationCurrent()&&metadataMatches(shape)&&operationCurrent();}
            public BingNavigationSnapshot.Ready originalSource(){return original;}
            public BingNavigationSnapshot.Result observe(BingNavigationSnapshot.Shape shape){return metadataRead(shape);}
            public BingNavigationSnapshot.Result observeTimedReturn(BingNavigationSnapshot.Ready source){return timedReturnRead(source);}
            public void consumeReturn(ReviewShizukuBackActor.BackTarget exact)throws Exception{
                if(!operationCurrent())throw new SecurityException("Return grant unavailable");
                if(fixturePorts!=null){fixturePorts.consumeReturn(exact);return;}
                if(files==null)throw new SecurityException("Return grant unavailable");
                if(uniquePending)RootUniqueRefinedScope.burnAction(files,"RETURN","display="+exact.fullDisplayWidth()+"x"+exact.fullDisplayHeight()+" rotation="+exact.rotation()+" fixedKey=4\n");
                else if(selectedPending)RootSelectedPendingScope.burnAction(files,"RETURN","display="+exact.fullDisplayWidth()+"x"+exact.fullDisplayHeight()+" rotation="+exact.rotation()+" fixedKey=4\n");
                else if(bounded)RootBoundedBusinessScope.burnAction(files,"RETURN","display="+exact.fullDisplayWidth()+"x"+exact.fullDisplayHeight()+" rotation="+exact.rotation()+" fixedKey=4\n");
                else AutonomousRewardCardScope.burn(files,"RETURN","display="+exact.fullDisplayWidth()+"x"+exact.fullDisplayHeight()+" rotation="+exact.rotation()+" fixedKey=4\n");
            }
            public BingNavigationSnapshot.TimedTaskGuard timedTaskGuard(BingNavigationSnapshot.Ready source){return fixturePorts!=null?fixturePorts.timedTaskGuard(source,now()):metadata==null?BingNavigationSnapshot.TimedTaskGuard.notObserved():metadata.readTimedTaskGuard(source.profile().shape(),source);}
            public boolean metadataClean(){return metadataKnownClean();}
            public String diagnostic(){return metadata==null?"metadata=FIXTURE":metadata.lastReadDiagnostic().summary();}
            public void pollPause()throws InterruptedException{if(fixturePorts==null)Thread.sleep(selectedPending?40:100);else fixturePorts.pollPause();}
        };
        return timedStrategy()?NativeBingReturnController.timedAfterTap(runId,returnPorts,backActor)
            :new NativeBingReturnController(runId,returnPorts,backActor,selectedPending);
    }
    private NativeRewardCardSession.Authority createAuthority(){return new NativeRewardCardSession.Authority(){
            public long elapsedRealtime(){return now();}
            public boolean scopeCurrent(){return current()&&(phase==null||operationCurrent());}
            public boolean sourceStillMatches(int w,int h,int rotation){return source(w,h,rotation);}
            public void consumeOneTap(ReviewShizukuTapActor.TapTarget exact)throws Exception{
                if(!operationCurrent())throw new SecurityException("Task tap grant unavailable");
                if(fixturePorts!=null){fixturePorts.consumeTap(exact);return;}
                if(files==null)throw new SecurityException("Task tap grant unavailable");
                if(uniquePending)RootUniqueRefinedScope.burnAction(files,"TAP","display="+exact.fullDisplayWidth()+"x"+exact.fullDisplayHeight()+" rotation="+exact.rotation()+" maxTaskTap=1\n");
                else if(selectedPending)RootSelectedPendingScope.burnAction(files,"TAP","display="+exact.fullDisplayWidth()+"x"+exact.fullDisplayHeight()+" rotation="+exact.rotation()+" maxTaskTap=1\n");
                else if(bounded)RootBoundedBusinessScope.burnAction(files,"TAP","display="+exact.fullDisplayWidth()+"x"+exact.fullDisplayHeight()+" rotation="+exact.rotation()+" maxTaskTap=1\n");
                else AutonomousRewardCardScope.burn(files,"TAP","display="+exact.fullDisplayWidth()+"x"+exact.fullDisplayHeight()+" rotation="+exact.rotation()+" maxTaskTap=1\n");
            }
        };}
    NativeRewardCardSession.Authority authorityForContractTest(){if(!testOnly)throw new SecurityException("test-only");return authority;}
    NativeRewardCardSession sessionForContractTest(){if(!testOnly)throw new SecurityException("test-only");return session;}
    static NativeAutonomousCardRun confirmed(DormantCaptureConfirmationActivity activity){
        if(activity==null||!activity.isResumedForApproval()||!AutonomousRewardCardScope.admissionCurrent(System.currentTimeMillis())
            ||!NativeCheckInSession.permissionReady()||!CREATED.compareAndSet(false,true))throw new SecurityException("New focused autonomous approval required");
        return new NativeAutonomousCardRun(activity);
    }
    boolean current(){return !revoked.get()&&(uniquePending?RootUniqueRefinedScope.current(utc()):selectedPending?RootSelectedPendingScope.current(utc()):bounded?RootBoundedBusinessScope.current(utc()):AutonomousRewardCardScope.current(utc()))&&now()<expires;}
    private boolean operationCurrent(){return current()&&operationActive&&now()<phaseEnd;}
    synchronized void reserve(NativeRewardCardSession.Phase selected){
        if(selected==null||reserved[selected.ordinal()]||!current()||selected==NativeRewardCardSession.Phase.AFTER&&!needsAfter())throw new SecurityException("Autonomous phase unavailable");
        reserved[selected.ordinal()]=true;if(selected==NativeRewardCardSession.Phase.AFTER)afterReserved=true;phase=selected;accepted=false;operationActive=true;
        phaseStart=now();phaseEnd=uniquePending?RootUniqueRefinedScope.deadline(phaseStart,utc(),expires):selectedPending?RootSelectedPendingScope.deadline(phaseStart,utc(),expires):bounded?RootBoundedBusinessScope.deadline(phaseStart,utc(),expires):AutonomousRewardCardScope.deadline(phaseStart,utc(),expires);
    }
    RealCaptureClaim.BurnStore store(NativeRewardCardSession.Phase selected){
        if(selected==null)throw new IllegalArgumentException("phase");if(selectedPending||uniquePending)throw new SecurityException("Phase-index store required");return (id,metadata)->AutonomousRewardCardScope.burn(files,selected.name(),metadata);
    }
    long deadline(long now){if(!operationCurrent()||now<0||now>=phaseEnd)throw new SecurityException("Phase deadline unavailable");return phaseEnd;}
    NativeRewardCardSession.Capture startCapture(NativeRewardCardSession.Phase selected,int w,int h){
        if(bounded)return null;
        if(selected==null||phase!=selected||!reserved[selected.ordinal()]||!operationCurrent())return null;
        if(fixturePorts!=null){var shape=fixturePorts.shape();return shape.width()==w&&shape.height()==h?session.startCapture(selected,w,h,shape.rotation()):null;}
        var manager=context.getSystemService(android.hardware.display.DisplayManager.class);
        var display=manager==null?null:manager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if(display==null||display.getState()!=android.view.Display.STATE_ON)return null;
        return session.startCapture(selected,w,h,display.getRotation());
    }
    private boolean source(int w,int h,int rotation){
        if(!operationCurrent())return false;
        var read=metadataRead(new BingNavigationSnapshot.Shape(w,h,rotation));
        if(read.status()!=BingNavigationSnapshot.Status.READY||read.ready().kind()!=BingNavigationSnapshot.Kind.TEMPLATE
            ||!read.ready().fresh(now())||(selectedPending||uniquePending)&&!read.ready().task().equals(Integer.toString(sourceTaskId()))||!operationCurrent())return false;
        var prepared=currentPrepared;if(prepared!=null&&!prepared.source().sameSource(read.ready()))return false;
        if(original==null){if(phase!=NativeRewardCardSession.Phase.BEFORE)return false;original=read.ready();}
        return original.sameSource(read.ready());
    }
    static LiveRewardCardObservation.Card select(NativeRewardCardSession.Observation observation){return observation==null?null:observation.configuredTarget();}
    String acceptAfterCleanup(NativeRewardCardSession.Capture capture,NativeRewardCardSession.Observation observation,
            boolean released,BooleanSupplier hidden,long phaseDeadline){
        if(bounded&&!businessAccepting){terminalSummary="autonomousTerminal=REJECTED_LEGACY_ENTRY";cancel();return null;}
        if(capture==null||capture.phase!=phase||phaseDeadline!=phaseEnd||!operationCurrent()||!released||!nativeObservationEligible(observation)){
            terminalSummary="autonomousTerminal=REJECTED_INPUT nativeObservation="+(observation==null?"NONE":observation.reason());cancel();return null;
        }
        try{
            var selected=phase==NativeRewardCardSession.Phase.BEFORE&&!(selectedPending&&observation.visibleSetComplete())?select(observation):null;
            var receipt=session.acceptAfterCleanup(capture,observation,true,hidden,selected,phaseDeadline);
            returned=session.returnReady();resourcesClean=metadataKnownClean()&&backActor.knownClean()
                &&(receipt.actorResult()==null||receipt.actorResult().kind()!=ReviewShizukuTapActor.Kind.DISPATCH_UNKNOWN);
            terminalSummary="autonomousTerminal="+receipt.outcome()+" terminalReason="+receipt.reason()+" metadataKnownClean="+metadataKnownClean()
                +" backResourcesKnown="+backActor.knownClean()+" "+session.returnSummary()
                +" tapKind="+(receipt.actorResult()==null?"NONE":receipt.actorResult().kind())+" tapReason="+(receipt.actorResult()==null?"NONE":receipt.actorResult().reason());
            lastPhase=phase;lastPhaseEnd=phaseDeadline;if(phase==NativeRewardCardSession.Phase.AFTER)afterLower=capture.acquiredLowerBound;
            accepted=resourcesClean&&current()&&now()<phaseDeadline;
            if(!accepted)return null;
            String summary="source="+(uniquePending?(fixturePorts==null?"REAL_NATIVE_UNIQUE_PENDING":"FIXTURE_NATIVE_UNIQUE_PENDING"):(fixturePorts==null?(selectedPending?"REAL_NATIVE_SELECTED_PENDING_B":"REAL_NATIVE_AUTONOMOUS_MORE"):(selectedPending?"FIXTURE_NATIVE_SELECTED_PENDING_B":"FIXTURE_NATIVE_AUTONOMOUS_MORE")))+" scope="+runId+" phase="+phase+" selection="+(uniquePending?(selected==null?"NONE":"UNIQUE_NATIVE_FIVE_BADGE") :selectedPending?(selected==null?"NONE":"EXACT_PENDING_B_TITLE_BODY_POINTS"):"EXACT_NATIVE_TITLE_BODY_POINTS")
                +" outcome="+receipt.outcome()+" reason="+receipt.reason()+" returnReady="+returned+" points=NOT_EVALUATED causalAttribution=UNPROVEN"
                +" fullDisplay="+capture.width+"x"+capture.height+" rotation="+capture.rotation+" frameId="+capture.sequence
                +" sourceStartElapsed="+capture.acquiredLowerBound+" phaseDeadline="+phaseDeadline+" expiresElapsed="+expires;
            if(observation!=null)summary+=" observation="+observation.status()+" observationReason="+observation.reason()+" familyItems="+observation.cards().size();
            if(uniquePending&&observation!=null)summary+=" "+observation.uniqueEvidenceSummary();
            if(selectedPending&&observation!=null)summary+=" "+observation.selectedEvidenceSummary();
            if(observation!=null&&observation.diagnostic()!=null)summary+=" "+observation.diagnostic().summary();
            if(selected!=null)summary+=" selectedCard="+box(selected.cardBox())+" selectedIndicator="+box(selected.indicatorBox())+" nativeNumber="+box(selected.numberBox())+" displayedTaskPoints="+selected.displayedPoints();
            if(receipt.actorResult()!=null){var a=receipt.actorResult();summary+=" tapKind="+a.kind()+" tapReason="+a.reason()+" tapExit="+a.exitCode()+" taskInvocationAttempted="+a.invocationAttempted()+" tapAuthorityConsumed="+a.authorityConsumed();}
            return summary+" "+session.returnSummary()+"\n"+switch(receipt.outcome()){
                case AWAITING_NEW_OBSERVATION->"任务卡触摸与系统窗口返回条件已记录；尚非完成证明。只允许随后全新原生帧核验，不代答或再次点击。";
                case OBSERVED_VISIBLE_MORE_SET_COMPLETE->"新鲜原生帧中，识别的5张可见卡各自显示数字和勾；不命名匿名项，不声明页面或全栏目穷尽，不推断点击因果。";
                case OBSERVED_VISIBLE_KNOWN_SET_COMPLETE->"新鲜原生帧中，两张可见同标题小项各自显示5和勾；空正文不命名A/B，不声称全栏目完成或本次点击因果。";
                case OBSERVED_TASK_COMPLETE_UNATTRIBUTED->"新鲜同scope原生帧已观察到选中任务自身完成态；未核总积分，不推断点击是唯一原因。";
                case SECTION_ALREADY_COMPLETE_SKIP,TASK_ALREADY_COMPLETE_SKIP->"可靠证据显示当前区域或目标已完成，已跳过，没有任务触摸。";
                case HOLD->"证据或返回条件不足，保持HOLD，不重发任务触摸或Back，不代答。";
            };
        }catch(RuntimeException failure){terminalSummary="autonomousTerminal=INTERNAL_HOLD";throw failure;}finally{operationActive=false;}
    }
    static boolean nativeObservationEligible(NativeRewardCardSession.Observation observation){
        if(observation==null||observation.route()!=LiveRewardCardObservation.Route.MORE_VISIBLE_ITEM)return false;
        return observation.reason()!=LiveRewardCardObservation.Reason.INVALID_INPUT
            &&observation.reason()!=LiveRewardCardObservation.Reason.BAD_NATIVE_BATCH&&observation.reason()!=LiveRewardCardObservation.Reason.CANCELED;
    }
    synchronized NativeRewardCardSession.PreparedCapture startPrepared(NativeRewardCardSession.Phase selected,int attempt,Object owner,long lower,BingNavigationSnapshot.Ready strictPre){
        long now=now();if(!bounded||selected==null||phase!=selected||!operationCurrent()||currentPrepared!=null
            ||!(uniquePending?RootUniqueRefinedScope.validIndex(attempt):selectedPending?RootSelectedPendingScope.validIndex(attempt):RootBoundedBusinessScope.validIndex(attempt))||attempt!=preparedAttempts[selected.ordinal()]+1||owner==null||strictPre==null
            ||strictPre.kind()!=BingNavigationSnapshot.Kind.TEMPLATE||(selectedPending||uniquePending)&&!strictPre.task().equals(Integer.toString(sourceTaskId()))||!strictPre.fresh(now)||lower<phaseStart||lower>now||now-lower>NativeRewardCardSession.MAX_FRAME_AGE)return null;
        preparedAttempts[selected.ordinal()]=attempt;
        var p=session.prepareCandidate(selected,attempt,owner,lower,++preparedSequence,phaseEnd,strictPre);
        if(p==null){cancel();return null;}currentPrepared=p;return p;
    }
    synchronized boolean candidateCurrent(NativeRewardCardSession.PreparedCapture p){return p!=null&&currentPrepared==p&&operationCurrent();}
    synchronized void cancelPrepared(NativeRewardCardSession.PreparedCapture p){if(p!=null&&currentPrepared==p)cancel();}
    private synchronized void closePrepared(NativeRewardCardSession.PreparedCapture p){if(currentPrepared==p){p.close();currentPrepared=null;}}
    private synchronized void boundedStop(NativeRewardCardSession.PreparedCapture p,String reason){
        terminalSummary="autonomousTerminal=HOLD boundedReason="+reason;closePrepared(p);operationActive=false;revoked.set(true);session.cancel();
    }
    boolean retireUnpresented(NativeRewardCardSession.PreparedCapture p,NativeRewardCardSession.PreparedObservation o,CaptureSessionRuntime.BoundedCompletion completion){
        if(!candidateCurrent(p)||o==null||!o.matches(p)||completion==null){boundedStop(p,"BAD_PREPARED");return false;}
        var disposition=completion.consume(this,p,o,now());
        if(disposition!=CaptureSessionRuntime.BoundedCompletion.Disposition.UNPRESENTED||!candidateCurrent(p)
            ||!o.validNativeShape()||o.nativeRowCount()<=0||o.nativeNonemptyRows()<=0||o.nativeMoreCandidates()!=0||o.nativeWrongPageCandidates()!=0
            ||o.status()!=LiveRewardCardObservation.Status.HOLD||o.reason()!=LiveRewardCardObservation.Reason.MORE_HEADING_NOT_UNIQUE){
            boundedStop(p,"UNPRESENTED_PROOF_REJECTED");return false;
        }
        synchronized(this){if(!candidateCurrent(p)){boundedStop(p,"PREPARED_REVOKED");return false;}
            closePrepared(p);if(p.attempt()>=(uniquePending?RootUniqueRefinedScope.MAX_PER_PHASE:selectedPending?RootSelectedPendingScope.MAX_PER_PHASE:RootBoundedBusinessScope.MAX_PER_PHASE)){boundedStop(p,"UNPRESENTED_LIMIT");return false;}}
        terminalSummary="autonomousTerminal=UNPRESENTED_ONLY nextObservation=true phase="+p.phase()+" attempt="+p.attempt()+" phaseDeadline="+p.phaseDeadline();return true;
    }
    String acceptPreparedAfterCleanup(NativeRewardCardSession.PreparedCapture p,NativeRewardCardSession.PreparedObservation o,
            CaptureSessionRuntime.BoundedCompletion completion,BooleanSupplier hidden,BooleanSupplier candidateCanceled){
        if(!candidateCurrent(p)||o==null||!o.matches(p)||completion==null||hidden==null||candidateCanceled==null){boundedStop(p,"BAD_PREPARED");return null;}
        try{
            var disposition=completion.consume(this,p,o,now());
            if(disposition!=CaptureSessionRuntime.BoundedCompletion.Disposition.POSITIVE||!candidateCurrent(p)||candidateCanceled.getAsBoolean()
                ||!o.validNativeShape()||o.status()==LiveRewardCardObservation.Status.HOLD){boundedStop(p,"ADOPT_PROOF_REJECTED");return null;}
            if(uniquePending&&(!o.uniquePendingOnly()||!o.uniqueProofReady())){boundedStop(p,"UNIQUE_NATIVE_PROOF_REQUIRED");return null;}
            if(selectedPending&&(!o.selectedPendingOnly()||!o.selectedProofReady())){boundedStop(p,"SELECTED_NATIVE_PROOF_REQUIRED");return null;}
            if(selectedPending&&p.phase()==NativeRewardCardSession.Phase.BEFORE&&!o.frame().selectedBeforeB()&&!o.frame().selectedAllComplete()){boundedStop(p,"EXACT_PENDING_B_AND_OTHER_TICK_REQUIRED");return null;}
            if(!selectedPending&&!uniquePending&&p.phase()==NativeRewardCardSession.Phase.BEFORE&&o.status()==LiveRewardCardObservation.Status.OBSERVED){
                var target=o.configuredTarget();
                if(target==null||!target.complete()&&!LiveRewardCardObservation.pendingBoundedFirstFamily(o.frame())){
                    boundedStop(p,"EXACT_PENDING_A_AND_B_REQUIRED");return null;
                }
            }
            // From this point a source/navigation/actor call is possible. Unknown never inherits old true.
            resourcesClean=false;businessAccepting=true;
            var adopted=session.adoptPrepared(p,o,()->hidden.getAsBoolean()&&!candidateCanceled.getAsBoolean());
            if(adopted==null){resourcesClean=metadataKnownClean()&&backActor.knownClean();boundedStop(p,"ADOPT_REJECTED");return null;}
            return acceptAfterCleanup(adopted.capture(),adopted.observation(),true,
                ()->hidden.getAsBoolean()&&!candidateCanceled.getAsBoolean(),p.phaseDeadline());
        }catch(RuntimeException|Error failure){resourcesClean=false;boundedStop(p,"PREPARED_INTERNAL_UNKNOWN");throw failure;}
        finally{closePrepared(p);businessAccepting=false;}
    }
    String terminalSummary(){return terminalSummary;}
    private static String box(com.aliothmoon.maafw.rewards.RewardRegionLocator.Box b){return b==null?"NONE":b.left()+","+b.top()+","+b.right()+","+b.bottom();}
    boolean returnReady(){return returned&&current();}
    boolean needsAfter(){return returnReady()&&!afterReserved&&session.needsAfter()
        &&(uniquePending?RootUniqueRefinedScope.current(utc()):selectedPending?RootSelectedPendingScope.current(utc()):bounded?RootBoundedBusinessScope.current(utc()):AutonomousRewardCardScope.admissionCurrent(utc()));}
    long remainingForAfterMillis(){return needsAfter()?Math.max(0,expires-now()):0;}
    /** Main publication fence is scalar only. BEFORE is historical tap/return metadata;
     * AFTER must still be a fresh native task result, even after Session has finished. */
    boolean publicationCurrent(long phaseDeadline){
        long now=now();
        return accepted&&resourcesClean&&current()&&phaseDeadline==lastPhaseEnd&&now<phaseDeadline
            &&(lastPhase!=NativeRewardCardSession.Phase.AFTER||afterLower>=0&&now>=afterLower&&now-afterLower<=NativeRewardCardSession.MAX_FRAME_AGE);
    }
    synchronized void cancel(){revoked.set(true);operationActive=false;var p=currentPrepared;if(p!=null){p.close();currentPrepared=null;}session.cancel();}
}


