package com.aliothmoon.maafw.once;

import android.content.Context;
import android.graphics.Bitmap;
import com.sun.jna.*;
import com.sun.jna.ptr.*;
import java.io.*;
import java.util.Arrays;

/** Internal one-shot OCR, not an authorization or capture entry point.
 * One synchronous worker owns all native calls. No callbacks admit native work.
 * recognize's returned flag is a lease boundary, not native quiescence evidence.
 * A blocked/throwing native call leaves the entire graph in the static anchor.
 * Process-global privacy requires an isolated worker process, without other Maa users.
 */
final class MaaRecognizer implements BackendPorts.Recognizer, CleanupEvidence.Provider, ReviewShizukuPngBinding.AlignmentRecognizer {
    public interface Api extends Library, MaaImageBufferAudit.ImageApi {
        String MaaVersion();
        Pointer MaaStringBufferCreate(); long MaaStringBufferSize(Pointer p); Pointer MaaStringBufferGet(Pointer p); byte MaaStringBufferClear(Pointer p); void MaaStringBufferDestroy(Pointer p);
        byte MaaGlobalSetOption(int key, Pointer value, long size);
        Pointer MaaResourceCreate(); void MaaResourceDestroy(Pointer p);
        long MaaResourcePostBundle(Pointer p, String path); int MaaResourceStatus(Pointer p, long id);
        Pointer MaaTaskerCreate(); void MaaTaskerDestroy(Pointer p);
        byte MaaTaskerBindResource(Pointer t, Pointer r);
        long MaaTaskerPostRecognition(Pointer t, String type, String params, Pointer image);
        int MaaTaskerStatus(Pointer t, long id); byte MaaTaskerRunning(Pointer t);
        long MaaTaskerPostStop(Pointer t);
        byte MaaTaskerGetTaskDetail(Pointer t,long id,Pointer entry,Pointer nodes,LongByReference count,IntByReference status);
        byte MaaTaskerGetNodeDetail(Pointer t,long id,Pointer name,LongByReference reco,LongByReference action,ByteByReference done);
        byte MaaTaskerGetRecognitionDetail(Pointer t,long id,Pointer name,Pointer algorithm,ByteByReference hit,Pointer box,Pointer detail,Pointer raw,Pointer draws);
        Pointer MaaImageBufferCreate(); void MaaImageBufferDestroy(Pointer p);
        byte MaaImageBufferSetEncoded(Pointer p,Pointer bytes,long size);
    }
    enum Stage { NEW, PRIVACY_READY, LIBRARY_READY, MODELS_READY, RESOURCE_LOADING, RESOURCE_READY, IMAGE_READY, TASK_POSTED, TASK_QUIET, DETAIL_READ }
    record Diagnostic(Stage stage, int taskStatus, boolean taskQuiet, boolean taskerDestroyed, boolean released) { }
    private final CleanupEvidence.Ledger receipts = new CleanupEvidence.Ledger();
    @Override public CleanupEvidence.Snapshot evidenceSnapshot(OnceBackend.Owner expected) { return receipts.snapshot(expected); }
    private void waiting(CleanupEvidence.Item item) { receipts.set(item, CleanupEvidence.State.WAITING); }
    private void confirmed(CleanupEvidence.Item item) { receipts.set(item, CleanupEvidence.State.CONFIRMED); }
    private volatile Stage stage=Stage.NEW;
    private volatile int lastTaskStatus;
    private volatile boolean taskQuiet, taskerDestroyed;
    synchronized Diagnostic diagnostic(){return new Diagnostic(stage,lastTaskStatus,taskQuiet,taskerDestroyed,complete);}
    private static MaaRecognizer anchor;
    private final Context context;
    private final MaterialReplayObservation.Kind materialKind;
    private final long materialSequence;
    private boolean liveCheckin;
    private boolean currentStatus;
    private CurrentCheckInStatus.Result currentObservation;
    static MaaRecognizer currentCheckInStatus(Context context){
        MaaRecognizer r=liveCheckIn(context);r.currentStatus=true;return r;
    }
    synchronized CurrentCheckInStatus.Result currentCheckInObservation(OnceBackend.Owner expected){
        return expected==owner&&observation()!=null?currentObservation:null;
    }
    enum RootMarker { BING, REWARDS, MORE, OWN_ROOT_TITLE, OWN_PREFLIGHT_PROMPT, PROJECTION_PROMPT, LOCK_SCREEN_PROMPT, REWARDS_ERROR }
    record RootMarkerCount(RootMarker marker,int candidates,int highConfidence) { }
    record RootMarkerDiagnostic(int nativeRows,java.util.List<RootMarkerCount> markers) {
        RootMarkerDiagnostic { markers=java.util.List.copyOf(markers); }
        String summary(){boolean known=false;for(var marker:markers)known|=marker.highConfidence()>0;
            return "rootPageMarkerState="+(known?"KNOWN_MARKER_CANDIDATES":"UNKNOWN")+" markerHighThreshold=0.85 markerSemantics=DIAGNOSTIC_ONLY targetIdentity=UNPROVEN nativeRows="+nativeRows+" markers="+markers;}
    }
    private boolean rootPreflight;
    private boolean imageAuditEnabled;
    private boolean imageSeriesStatsEnabled;
    private volatile MaaImageBufferAudit imageAudit;
    /** Read-only diagnostic only. Runtime must not mint action/pass/continuation from this factory. */
    static MaaRecognizer rootImageBufferAudit(Context context,ReadOnlyTwoStageRun.Capture capture){
        MaaRecognizer r=rootPreflight(context,capture);r.imageAuditEnabled=true;return r;
    }
    /** Separate series diagnostic only; each invocation still has one image and one OCR query. */
    static MaaRecognizer rootImageBufferSeriesAudit(Context context,ReadOnlyTwoStageRun.Capture capture){
        MaaRecognizer r=rootImageBufferAudit(context,capture);r.imageSeriesStatsEnabled=true;return r;
    }
    /** Fixed family current-state diagnostics only; never a page-pass/action factory. */
    static MaaRecognizer rootCurrentStatusAudit(Context context,ReadOnlyTwoStageRun.Capture capture){
        if(capture==null||!capture.currentStatusOnly())throw new IllegalArgumentException("Fixed current-state holder required");
        return rootImageBufferSeriesAudit(context,capture);
    }
    /** One native frame plus one full OCR batch; this factory creates no live admission or actor. */
    static MaaRecognizer rootUiRegionAlignmentAudit(Context context,UiImageAlignmentCapture capture){
        MaaRecognizer r=new MaaRecognizer(context);r.liveCheckin=true;r.rootPreflight=true;
        r.imageAuditEnabled=true;r.imageSeriesStatsEnabled=true;
        r.alignmentCapture=java.util.Objects.requireNonNull(capture);return r;
    }
    private UiImageAlignmentCapture alignmentCapture;
    private UiImageAlignmentCapture.Candidate alignmentCandidate;
    @Override public synchronized UiImageAlignmentCapture.Candidate takeAlignmentCandidate(OnceBackend.Owner expected){
        if(expected==null||expected!=owner||observation()==null)return null;
        var value=alignmentCandidate;alignmentCandidate=null;
        return value!=null&&value.matches(alignmentCapture,this,expected)?value:null;
    }
    @Override public synchronized MaaImageBufferAudit.Result alignmentAudit(OnceBackend.Owner expected){
        return imageBufferAudit(expected);
    }
    /** Pure scalar read. Positive comparison is withheld until this native graph is actually destroyed. */
    synchronized MaaImageBufferAudit.Result imageBufferAudit(OnceBackend.Owner expected){
        if(!imageAuditEnabled||expected==null||expected!=owner||imageAudit==null)return null;
        return imageAudit.result(complete&&returned&&observationStop!=null&&!observationStop.requested(),
            complete&&image==null,taskerDestroyed);
    }
    private MaaImageBufferAudit.Binding auditBinding(BackendPorts.FrameView frame){
        Object request=alignmentCapture!=null?alignmentCapture.owner():readOnlyCapture!=null?readOnlyCapture.owner:nativePreparedCapture==null?null:nativePreparedCapture.requestOwner();
        Object holder=alignmentCapture!=null?alignmentCapture:readOnlyCapture!=null?readOnlyCapture:nativePreparedCapture;
        return new MaaImageBufferAudit.Binding(this,owner,request,holder,frame,tasker,image);
    }
    private RootMarkerDiagnostic rootMarkers;
    static MaaRecognizer rootPreflight(Context context,ReadOnlyTwoStageRun.Capture capture){
        MaaRecognizer r=readOnlyQuote(context,capture);r.rootPreflight=true;return r;
    }
    synchronized RootMarkerDiagnostic rootMarkerDiagnostic(OnceBackend.Owner expected){return expected==owner&&observation()!=null?rootMarkers:null;}
    static RootMarkerDiagnostic rootMarkers(java.util.List<FrameStructureEvidence.Line> rows){
        if(rows==null||rows.size()>128)return new RootMarkerDiagnostic(-1,java.util.List.of());
        int[] count=new int[RootMarker.values().length],high=new int[count.length];
        for(var row:rows){
            if(row==null||row.text()==null)continue;
            String text=row.text().trim().replaceAll("[\\s\\u00a0\\u2009\\u202f]+"," ").toLowerCase(java.util.Locale.ROOT);
            for(RootMarker marker:RootMarker.values()){
                boolean hit=switch(marker){
                    case BING->text.equals("bing")||text.equals("microsoft bing");
                    case REWARDS->text.equals("rewards")||text.equals("microsoft rewards")||text.equals("奖励");
                    case MORE->text.equals("更多活动");
                    case OWN_ROOT_TITLE->text.equals("root 整屏原生验证")||text.equals("root 原生验证")||text.contains("任务卡只读诊断")||text.contains("任务卡原生验证");
                    case OWN_PREFLIGHT_PROMPT->text.contains("只读预检：1帧，零输入")||text.contains("开始独立整屏只读预检")||text.contains("只读任务卡诊断：1帧，零输入");
                    case PROJECTION_PROMPT->text.contains("开始录制或投射")||text.contains("开始录制或投屏")||text.contains("start recording or casting")||text.equals("立即开始");
                    case LOCK_SCREEN_PROMPT->text.contains("滑动解锁")||text.contains("向上滑动解锁")||text.contains("swipe to unlock")||text.equals("输入pin码");
                    case REWARDS_ERROR->text.contains("出现问题")||text.contains("尝试刷新页面")||text.equals("image load error");
                };
                if(hit){count[marker.ordinal()]++;if(Double.isFinite(row.score())&&row.score()>=.85&&row.score()<=1)high[marker.ordinal()]++;}
            }
        }
        java.util.List<RootMarkerCount> result=new java.util.ArrayList<>();
        for(RootMarker marker:RootMarker.values())result.add(new RootMarkerCount(marker,count[marker.ordinal()],high[marker.ordinal()]));
        return new RootMarkerDiagnostic(rows.size(),result);
    }
    private ReadOnlyTwoStageRun.Capture readOnlyCapture;
    private ReadOnlyTwoStageRun.Observation readOnlyObservation;
    static MaaRecognizer readOnlyQuote(Context context,ReadOnlyTwoStageRun.Capture capture){
        MaaRecognizer r=new MaaRecognizer(context);r.liveCheckin=true;r.readOnlyCapture=java.util.Objects.requireNonNull(capture);return r;
    }
    synchronized ReadOnlyTwoStageRun.Observation takeReadOnlyObservation(OnceBackend.Owner expected){
        if(expected!=owner||observation()==null)return null;
        var value=readOnlyObservation;readOnlyObservation=null;return value;
    }
    private NativeRewardCardSession.PreparedCapture nativePreparedCapture;
    private NativeRewardCardSession.PreparedObservation nativePreparedObservation;
    /** New bounded candidate only: no Session adoption/action occurs inside Maa. */
    static MaaRecognizer rootPreparedRewardCardAudit(Context context,NativeRewardCardSession.PreparedCapture capture){
        MaaRecognizer r=new MaaRecognizer(context);r.liveCheckin=true;r.rootPreflight=true;r.imageAuditEnabled=true;r.imageSeriesStatsEnabled=true;
        r.nativePreparedCapture=java.util.Objects.requireNonNull(capture);return r;
    }
    synchronized NativeRewardCardSession.PreparedObservation preparedRewardCardObservation(OnceBackend.Owner expected){
        return expected==owner&&observation()!=null?nativePreparedObservation:null;
    }
    private NativeRewardCardSession.Capture nativeCardCapture;
    private NativeRewardCardSession.Observation nativeCardObservation;
    /** Shares only the full native all[] mode, never a check-in scope or check-in actor. */
    static MaaRecognizer liveRewardCard(Context context,NativeRewardCardSession.Capture capture){
        MaaRecognizer r=new MaaRecognizer(context);
        r.liveCheckin=true;r.nativeCardCapture=java.util.Objects.requireNonNull(capture);return r;
    }
    synchronized NativeRewardCardSession.Observation nativeRewardCardObservation(OnceBackend.Owner expected){
        return expected==owner&&observation()!=null?nativeCardObservation:null;
    }
    private String liveSummary;
    private NativeCheckInSession.Capture nativeCapture;
    private NativeCheckInSession.Observation nativeObservation;
    static MaaRecognizer liveCheckIn(Context context,NativeCheckInSession.Capture capture){
        MaaRecognizer r=liveCheckIn(context);r.nativeCapture=java.util.Objects.requireNonNull(capture);return r;
    }
    synchronized NativeCheckInSession.Observation nativeCheckInObservation(OnceBackend.Owner expected){
        return expected==owner&&observation()!=null?nativeObservation:null;
    }
    static MaaRecognizer liveCheckIn(Context context){MaaRecognizer r=new MaaRecognizer(context);r.liveCheckin=true;return r;}
    synchronized String liveCheckInSummary(OnceBackend.Owner expected){return expected==owner && observation()!=null?liveSummary:null;}
    private BackendPorts.FrameView materialFrame;
    private MaterialReplayObservation materialObservation;
    private java.util.List<FrameStructureEvidence.Line> materialRows, roiRows;
    private boolean readingLabelRoi;
    private String materialRoiDiagnostic = "DAY_ROI_NOT_REQUESTED";
    synchronized MaterialReplayObservation materialObservation(OnceBackend.Owner expected) {
        return expected == owner && observation() != null ? materialObservation : null;
    }
    synchronized String materialRoiDiagnostic(OnceBackend.Owner expected) {
        return expected == owner && observation() != null ? materialRoiDiagnostic : "DAY_ROI_NOT_OBSERVED";
    }
    private OnceBackend.Owner owner;
    private boolean entered, returned, releaseStarted, complete, uncertain;
    private Api api;
    private volatile RewardsObservation provisional;
    private BackendPorts.StopSignal observationStop;
    /** Caller must also require backend successful terminal and same owner.
     * Native cleanup cannot prove frame cleanup or final cancellation policy. */
    synchronized RewardsObservation observation() {
        if (!complete || observationStop == null || observationStop.requested()) {
            if (observationStop != null && observationStop.requested()) provisional = null;
            return null;
        }
        return provisional;
    }
    private Pointer resource, tasker, image, detailBuffer;
    private final Object structureEpoch=new Object(), structureFrame=new Object();
    private FrameStructureEvidence.Binding structureBinding;
    private FrameStructureEvidence.Result structure;
    private FramePageEvidence.Result pageEvidence;
    synchronized FramePageEvidence.Result pageEvidence(OnceBackend.Owner expected) {
        return expected==owner && observation()!=null ? pageEvidence : null;
    }
    private int frameWidth,frameHeight;
    private final PerceptionDiagnostics perception=new PerceptionDiagnostics();
    synchronized String perceptionDiagnostic(OnceBackend.Owner expected){
        return perception.published(expected!=null&&expected==owner,complete,
                observationStop==null||observationStop.requested());
    }
    synchronized FrameStructureEvidence.Result structure(OnceBackend.Owner expected) {
        return expected==owner && observation()!=null ? structure : null;
    }
    private long resourceId, taskId;
    private boolean stopPosted;
    private MaaOnceModelStore models;
    private Bitmap bitmap;
    private int[] row;
    private Encoded encoded;
    private Memory nativeEncoded, nodes;
    private static final long POLL_NANOS = 60_000_000_000L;

    MaaRecognizer(Context context) { this(context, null, 0); }
    MaaRecognizer(Context context, MaterialReplayObservation.Kind kind, long sequence) {
        this.context = context.getApplicationContext(); materialKind = kind; materialSequence = sequence;
        if (kind != null && sequence < 1) throw new IllegalArgumentException();
    }
    private static void need(boolean ok) throws IOException { if (!ok) throw new IOException(); }
    private static boolean terminal(int status) { return status == 3000 || status == 4000; }
    private static void pause() throws InterruptedException { Thread.sleep(20); }
    private void healthy() throws IOException { MaaOnceNativePrivacy.check(); }
    private void checkStop(BackendPorts.StopSignal stop) throws IOException { if(stop.requested())throw new BackendPorts.Cancelled(); healthy(); }

    @Override public BackendPorts.Recognition recognize(OnceBackend.Owner own, BackendPorts.FrameView frame,
                                                       BackendPorts.StopSignal stop) throws Exception {
        synchronized (this) {
            if (own == null || entered) throw new IllegalStateException();
            owner = own; receipts.bind(own); waiting(CleanupEvidence.Item.RECOGNIZE_RETURN); entered = true; observationStop = stop;
        }
        boolean recognized = false;
        try {
            synchronized (MaaRecognizer.class) {
                if (anchor != null) throw new IOException();
                anchor = this;
            }
            need(android.os.Process.myUid() >= 10000);
            if(stop.requested())throw new BackendPorts.Cancelled();
            // Install before loading Maa; sinks contain no frame/result pointers.
            MaaOnceNativePrivacy.install(context); stage=Stage.PRIVACY_READY;
            api = Native.load(new File(context.getApplicationInfo().nativeLibraryDir,"libMaaFramework.so").getAbsolutePath(), Api.class);
            options();
            String version = api.MaaVersion();
            need("v5.15.0".equals(version) || "5.15.0".equals(version)); stage=Stage.LIBRARY_READY;
            checkStop(stop);
            waiting(CleanupEvidence.Item.MODELS_CLOSE); models = MaaOnceModelStore.open(context.getNoBackupFilesDir().toPath());
            try { models.prepare(); } catch (Exception e) { uncertain=true; throw e; }
            for (int i=0;i<MaaOnceModelPolicy.count();i++) {
                checkStop(stop);
                try (InputStream in=context.getAssets().open("bundle/model/ocr/"+MaaOnceModelPolicy.name(i))) { models.write(i,in); }
            }
            stage=Stage.MODELS_READY;
            waiting(CleanupEvidence.Item.RESOURCE_DESTROY); resource = api.MaaResourceCreate(); need(resource != null);
            checkStop(stop);
            resourceId = api.MaaResourcePostBundle(resource, models.bundlePath()); need(resourceId > 0);
            stage=Stage.RESOURCE_LOADING;
            awaitResource(stop); stage=Stage.RESOURCE_READY;
            checkStop(stop);
            waiting(CleanupEvidence.Item.TASKER_DESTROY); tasker = api.MaaTaskerCreate(); need(tasker != null);
            need(api.MaaTaskerBindResource(tasker,resource) != 0);
            prepareImage(frame, stop); stage=Stage.IMAGE_READY;
            frameWidth=frame.width();frameHeight=frame.height();perception.frame(frameWidth,frameHeight);
            if (materialKind != null || liveCheckin) materialFrame = frame;
            structureBinding=new FrameStructureEvidence.Binding(this,own,structureEpoch,structureFrame);
            boolean[] hits = new boolean[RewardsQuery.values().length];
            boolean hit = false;
            for (RewardsQuery query : RewardsQuery.values()) {
                checkStop(stop);
                // Prior task is truly quiet before reuse; reset evidence before posting.
                taskQuiet=false; waiting(CleanupEvidence.Item.TASKER_RUNNING_FALSE); lastTaskStatus=0; taskId=0;
                perception.posted(query);
                taskId=api.MaaTaskerPostRecognition(tasker,"OCR",liveCheckin?"{\"expected\":[\".+\"],\"threshold\":0.3}":query.parameters(),image);
                need(taskId>0); stage=Stage.TASK_POSTED;
                if(imageAuditEnabled)imageAudit.posted(auditBinding(frame),taskId);
                int status=awaitTask(stop);perception.terminal(query,status);
                checkStop(stop);
                hits[query.ordinal()]=detail(status,query==RewardsQuery.REWARDS_MARKER);
                perception.detail(query,hits[query.ordinal()]); stage=Stage.DETAIL_READ;
                if(imageAuditEnabled){
                    checkStop(stop);
                    imageAudit.after(api,auditBinding(frame),taskId,taskQuiet,stop);
                    checkStop(stop);
                }
                hit |= hits[query.ordinal()];
                checkStop(stop);
                if(liveCheckin)break; // One full native OCR batch, not four duplicate screen queries.
            }
            if(rootPreflight)rootMarkers=rootMarkers(materialRows);
            if(alignmentCapture!=null)refineUiImageAlignment(stop);
            if(readOnlyCapture!=null) refineReadOnlyQuote(stop);
            if(nativePreparedCapture!=null) refinePreparedRewardCard(stop);
            if(nativeCardCapture!=null) refineNativeRewardCard(stop);
            if(nativeCapture!=null) refineNativeCheckIn(stop);
            if (materialKind == MaterialReplayObservation.Kind.CHECKIN) refineCheckInLabels(stop);
            provisional=RewardsObservation.classify(hits[RewardsQuery.REWARDS_MARKER.ordinal()],
                    hits[RewardsQuery.SIGN_IN_PROMPT.ordinal()],hits[RewardsQuery.DAILY_SET.ordinal()],
                    hits[RewardsQuery.PROGRESS_HINT.ordinal()]);
            checkStop(stop);
            recognized=true;
            return new BackendPorts.Recognition(own,hit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); uncertain = true; throw new IOException();
        } catch (RuntimeException | Error e) {
            // Unknown native outcome or wrapper failure: no destructive retry.
            uncertain = true; throw e;
        } finally {
            materialFrame = null;
            materialRows = null; roiRows = null; readingLabelRoi = false;
            if (!recognized || stop.requested()) provisional=null;
            if (!recognized || stop.requested()) materialObservation=null;
            if (!recognized || stop.requested()) nativeCardObservation=null;
            if (!recognized || stop.requested()) nativePreparedObservation=null;
            if (!recognized || stop.requested()) readOnlyObservation=null;
            if (!recognized || stop.requested()) alignmentCandidate=null;
            if (!recognized || stop.requested()) rootMarkers=null;
            synchronized (this) { returned = true; confirmed(CleanupEvidence.Item.RECOGNIZE_RETURN); }
        }
    }
    private void options() throws IOException {
        try (Memory off=new Memory(4); Memory empty=new Memory(1); Memory no=new Memory(1); Memory cache=new Memory(8)) {
            off.clear(); empty.clear(); no.clear(); cache.clear();
            need(api.MaaGlobalSetOption(4,off,4)!=0);
            need(api.MaaGlobalSetOption(1,empty,0)!=0);
            for(int key:new int[]{2,6,7}) need(api.MaaGlobalSetOption(key,no,1)!=0);
            need(api.MaaGlobalSetOption(9,cache,8)!=0);
        }
    }
    private void awaitResource(BackendPorts.StopSignal stop) throws Exception {
        long end=System.nanoTime()+POLL_NANOS;
        for (;;) {
            int status=api.MaaResourceStatus(resource,resourceId);
            if(terminal(status)){need(status==3000);return;}
            need(status==1000 || status==2000);
            checkStop(stop); need(System.nanoTime()<end); pause();
        }
    }
    private int awaitTask(BackendPorts.StopSignal stop) throws Exception {
        long end=System.nanoTime()+POLL_NANOS;
        boolean cancelled=false;
        for (;;) {
            cancelled |= stop.requested();
            if(cancelled) postStop(); // Same worker; native stop call must actually return.
            int status=api.MaaTaskerStatus(tasker,taskId); lastTaskStatus=status;
            if(cancelled) {
                // PostStop can invalidate the original recognition record (status 0).
                // No task status, including 0, proves quiet. Only the actual Running
                // query after stop returns can permit this cancelled method to exit.
                if(api.MaaTaskerRunning(tasker)==0) {
                    confirmed(CleanupEvidence.Item.TASKER_RUNNING_FALSE); taskQuiet=true;stage=Stage.TASK_QUIET;
                    throw new BackendPorts.Cancelled(); // Never fetch recognition details.
                }
            } else {
                if(terminal(status) && api.MaaTaskerRunning(tasker)==0) {
                    confirmed(CleanupEvidence.Item.TASKER_RUNNING_FALSE); taskQuiet=true;stage=Stage.TASK_QUIET;return status;
                }
                need(status==1000 || status==2000 || terminal(status));
            }
            healthy(); need(System.nanoTime()<end); pause();
        }
    }
    private void postStop() throws IOException {
        if (!stopPosted) {
            stopPosted=true; // Never retry an uncertain/failed stop invocation.
            // An idle tasker may return invalid id; return alone is not quiet evidence.
            waiting(CleanupEvidence.Item.TASKER_RUNNING_FALSE);
            waiting(CleanupEvidence.Item.POST_STOP_RETURN);
            api.MaaTaskerPostStop(tasker);
            confirmed(CleanupEvidence.Item.POST_STOP_RETURN);
        }
    }
    private void prepareImage(BackendPorts.FrameView frame,BackendPorts.StopSignal stop) throws Exception {
        int w=frame.width(), h=frame.height();
        need(w>0 && h>0 && w<=4096 && h<=4096 && (long)w*h<=4*1024*1024 && frame.size()==w*h);
        waiting(CleanupEvidence.Item.ROW_WIPE); row=new int[w]; waiting(CleanupEvidence.Item.BITMAP_WIPE_RECYCLE); bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        for(int y=0;y<h;y++) {
            checkStop(stop);
            for(int x=0;x<w;x++){int g=frame.unsignedByte(y*w+x);need(g>=0&&g<=255);row[x]=0xff000000|(g<<16)|(g<<8)|g;}
            bitmap.setPixels(row,0,w,0,y,w,1);
        }
        waiting(CleanupEvidence.Item.ENCODED_WIPE); encoded=new Encoded(); need(bitmap.compress(Bitmap.CompressFormat.PNG,100,encoded));
        need(encoded.count>0); waiting(CleanupEvidence.Item.NATIVE_ENCODED_WIPE_CLOSE); nativeEncoded=new Memory(encoded.count);
        nativeEncoded.write(0,encoded.data,0,encoded.count);
        waiting(CleanupEvidence.Item.IMAGE_DESTROY); image=api.MaaImageBufferCreate(); need(image!=null);
        need(api.MaaImageBufferSetEncoded(image,nativeEncoded,encoded.count)!=0);
        if(imageAuditEnabled){
            checkStop(stop);need(rootPreflight&&liveCheckin&&(readOnlyCapture!=null||nativePreparedCapture!=null||alignmentCapture!=null));
            imageAudit=new MaaImageBufferAudit(auditBinding(frame),imageSeriesStatsEnabled);
            imageAudit.before(api,auditBinding(frame),stop);checkStop(stop);
        }
    }
    private boolean detail(int expectedStatus,boolean readStructure) throws IOException {
        if(nodes==null) { waiting(CleanupEvidence.Item.NODES_WIPE_CLOSE); nodes=new Memory(128); }
        nodes.clear(); // Reuse one bounded allocation across the fixed catalogue.
        LongByReference count=new LongByReference(16); IntByReference status=new IntByReference();
        need(api.MaaTaskerGetTaskDetail(tasker,taskId,null,nodes,count,status)!=0);
        need(status.getValue()==expectedStatus && count.getValue()>0 && count.getValue()<=16);
        boolean hit=false;
        if(readStructure)need(count.getValue()==1);
        for(int i=0;i<count.getValue();i++) {
            LongByReference reco=new LongByReference(); ByteByReference done=new ByteByReference(), matched=new ByteByReference();
            need(api.MaaTaskerGetNodeDetail(tasker,nodes.getLong(i*8L),null,reco,new LongByReference(),done)!=0);
            // completed can be false for a genuine OCR non-match; require the
            // recognition record itself, not an action-success flag.
            need(reco.getValue()>0);
            need(api.MaaTaskerGetRecognitionDetail(tasker,reco.getValue(),null,null,matched,null,null,null,null)!=0);
            hit |= matched.getValue()!=0;
            if(readStructure)readStructure(reco.getValue(),matched.getValue()!=0);
        }
        // A genuine non-match is valid; a failed task with a hit is inconsistent.
        need(expectedStatus==3000 || !hit);
        return hit;
    }
    private void readStructure(long reco,boolean matched) throws IOException {
        waiting(CleanupEvidence.Item.OCR_DETAIL_CLEAR_DESTROY);
        detailBuffer=api.MaaStringBufferCreate();need(detailBuffer!=null);
        need(api.MaaTaskerGetRecognitionDetail(tasker,reco,null,null,new ByteByReference(),null,detailBuffer,null,null)!=0);
        long size=api.MaaStringBufferSize(detailBuffer);
        if(size<=0||size>65536){perception.sizeRejected(size);throw new IOException();}
        Pointer ptr=api.MaaStringBufferGet(detailBuffer);need(ptr!=null);
        byte[] bytes=ptr.getByteArray(0,(int)size);
        try {
            OcrDetailDecoder.Result decoded=OcrDetailDecoder.decode(bytes,structureBinding,frameWidth,frameHeight,
                    readingLabelRoi ? rows -> roiRows = rows : liveCheckin ? this::observeLive : materialKind == null ? null : this::observeMaterial);
            if (!readingLabelRoi) perception.decoded(decoded);
            need(decoded.transportAccepted());
            // Empty all[] is a valid no-text non-match, never a matching page.
            need(!matched || decoded.getParse()!=OcrDetailDecoder.Parse.EMPTY);
            if (!readingLabelRoi) { structure=decoded.getStructure();pageEvidence=decoded.getPage(); }
        } finally {java.util.Arrays.fill(bytes,(byte)0);}
        need(api.MaaStringBufferClear(detailBuffer)!=0);
        api.MaaStringBufferDestroy(detailBuffer);detailBuffer=null;confirmed(CleanupEvidence.Item.OCR_DETAIL_CLEAR_DESTROY);
    }
    private void observeLive(java.util.List<FrameStructureEvidence.Line> rows) {
        if(imageAuditEnabled)imageAudit.observeRows(rows);
        if(currentStatus){
            if(materialFrame==null||observationStop==null||observationStop.requested())return;
            byte[] gray=new byte[frameWidth*frameHeight];
            try{
                for(int i=0;i<gray.length;i++){
                    if((i&4095)==0&&observationStop.requested())return;
                    gray[i]=(byte)materialFrame.unsignedByte(i);
                }
                currentObservation=CurrentCheckInStatus.observe(gray,frameWidth,frameHeight,rows,observationStop::requested);
            }finally{Arrays.fill(gray,(byte)0);}
            return;
        }
        if(nativeCapture!=null||nativeCardCapture!=null||readOnlyCapture!=null||nativePreparedCapture!=null||alignmentCapture!=null){materialRows=java.util.List.copyOf(rows);return;}
        if(materialFrame==null || observationStop==null || observationStop.requested())return;
        byte[] gray=new byte[frameWidth*frameHeight];
        try {
            for(int i=0;i<gray.length;i++){
                if((i&4095)==0 && observationStop.requested())return;
                gray[i]=(byte)materialFrame.unsignedByte(i);
            }
            liveSummary=LiveCheckInObservation.observe(gray,frameWidth,frameHeight,rows,observationStop::requested);
        } finally {Arrays.fill(gray,(byte)0);}
    }
    /** Run only after readStructure cleared/destroyed its Maa detail buffer. The retained rows
     * and GRAY8 copy still refer to this one OwnedFrame; no second OCR or recapture is used. */
    private void refineReadOnlyQuote(BackendPorts.StopSignal stop)throws Exception{
        checkStop(stop);if(materialFrame==null||materialRows==null)return;
        byte[] gray=new byte[frameWidth*frameHeight];
        try{
            for(int i=0;i<gray.length;i++){if((i&4095)==0)checkStop(stop);gray[i]=(byte)materialFrame.unsignedByte(i);}
            if(imageAuditEnabled)imageAudit.observeGeometry(gray,frameWidth,frameHeight,stop);
            readOnlyObservation=readOnlyCapture.prepare(gray,frameWidth,frameHeight,materialRows,stop::requested);checkStop(stop);
        }finally{Arrays.fill(gray,(byte)0);materialRows=null;}
    }
    /** Same materialFrame and all[] rows used by the audited native OCR query. */
    private void refineUiImageAlignment(BackendPorts.StopSignal stop)throws Exception{
        checkStop(stop);if(materialFrame==null||materialRows==null)return;
        byte[] gray=new byte[frameWidth*frameHeight];
        try{
            for(int i=0;i<gray.length;i++){if((i&4095)==0)checkStop(stop);gray[i]=(byte)materialFrame.unsignedByte(i);}
            imageAudit.observeGeometry(gray,frameWidth,frameHeight,stop);
            alignmentCandidate=alignmentCapture.prepare(this,owner,materialFrame,gray,materialRows,
                stop::requested,android.os.SystemClock.elapsedRealtime());
            checkStop(stop);
        }finally{Arrays.fill(gray,(byte)0);materialRows=null;}
    }
    private void refinePreparedRewardCard(BackendPorts.StopSignal stop)throws Exception{
        checkStop(stop);if(materialFrame==null||materialRows==null)return;
        byte[] gray=new byte[frameWidth*frameHeight];
        try{
            for(int i=0;i<gray.length;i++){if((i&4095)==0)checkStop(stop);gray[i]=(byte)materialFrame.unsignedByte(i);}
            imageAudit.observeGeometry(gray,frameWidth,frameHeight,stop);
            nativePreparedObservation=nativePreparedCapture.prepare(gray,frameWidth,frameHeight,materialRows,stop::requested);checkStop(stop);
        }finally{Arrays.fill(gray,(byte)0);materialRows=null;}
    }
    private void refineNativeRewardCard(BackendPorts.StopSignal stop) throws Exception {
        checkStop(stop);
        if(materialFrame==null||materialRows==null)return;
        byte[] gray=new byte[frameWidth*frameHeight];
        try {
            for(int i=0;i<gray.length;i++){
                if((i&4095)==0)checkStop(stop);
                gray[i]=(byte)materialFrame.unsignedByte(i);
            }
            nativeCardObservation=nativeCardCapture.prepare(gray,frameWidth,frameHeight,materialRows,stop::requested);
            checkStop(stop);
        } finally {Arrays.fill(gray,(byte)0);materialRows=null;}
    }
    private void refineNativeCheckIn(BackendPorts.StopSignal stop) throws Exception {
        checkStop(stop);
        if(materialFrame==null||materialRows==null)return;
        byte[] gray=new byte[frameWidth*frameHeight];
        try {
            for(int i=0;i<gray.length;i++){
                if((i&4095)==0)checkStop(stop);
                gray[i]=(byte)materialFrame.unsignedByte(i);
            }
            var prepared=nativeCapture.prepare(gray,frameWidth,frameHeight,materialRows,stop::requested);
            if(prepared==null)return;
            var roi=prepared.roi();
            roiRows=null;
            if(roi!=null){
                need(roi.left()>=0&&roi.top()>=0&&roi.width()>0&&roi.height()>0
                    &&roi.right()<=frameWidth&&roi.bottom()<=frameHeight);
                checkStop(stop);
                taskQuiet=false;waiting(CleanupEvidence.Item.TASKER_RUNNING_FALSE);lastTaskStatus=0;taskId=0;
                String params="{\"expected\":[\".+\"],\"threshold\":0.3,\"only_rec\":false,\"roi\":["
                    +roi.left()+","+roi.top()+","+roi.width()+","+roi.height()+"]}";
                taskId=api.MaaTaskerPostRecognition(tasker,"OCR",params,image);
                need(taskId>0);stage=Stage.TASK_POSTED;
                int status=awaitTask(stop);checkStop(stop);
                readingLabelRoi=true;
                try{detail(status,true);}finally{readingLabelRoi=false;}
                checkStop(stop);
            }
            nativeObservation=prepared.finish(roiRows,stop::requested);
        } finally {Arrays.fill(gray,(byte)0);roiRows=null;readingLabelRoi=false;}
    }
    private void observeMaterial(java.util.List<FrameStructureEvidence.Line> rows) {
        if (materialFrame == null || observationStop == null || observationStop.requested()) return;
        materialRows = java.util.List.copyOf(rows);
        byte[] gray = new byte[frameWidth * frameHeight];
        try {
            for (int i = 0; i < gray.length; i++) {
                if ((i & 4095) == 0 && observationStop.requested()) return;
                gray[i] = (byte)materialFrame.unsignedByte(i);
            }
            materialObservation = MaterialReplayObservation.observe(materialKind, materialSequence, gray,
                    frameWidth, frameHeight, rows, observationStop::requested);
        } finally { Arrays.fill(gray, (byte)0); }
    }
    /** Bounded same-image search, not annotation injection. v5.15.0 OCR all[].box already
     * includes the ROI origin (OCRer.cpp); validate those global boxes without adding it again. */
    private void refineCheckInLabels(BackendPorts.StopSignal stop) throws Exception {
        checkStop(stop);
        if (materialObservation != null && materialObservation.hasVisualPreview()) {
            materialRoiDiagnostic="DAY_ROI_NOT_REQUIRED_FOR_VISUAL_PREVIEW queries=0 rawRowsRetained=true";
            return;
        }
        if (materialFrame == null || materialRows == null) { materialRoiDiagnostic="DAY_ROI_NO_NATIVE_ROWS"; return; }
        byte[] gray = new byte[frameWidth * frameHeight];
        try {
            for (int i=0;i<gray.length;i++) {
                if ((i & 4095) == 0) checkStop(stop);
                gray[i]=(byte)materialFrame.unsignedByte(i);
            }
            var plan=CheckInLabelRoiPlan.prepare(gray,frameWidth,frameHeight,materialRows,stop::requested);
            materialRoiDiagnostic="DAY_ROI_PLAN="+plan.reason()+" lowInBand="+plan.lowInBand()+" lowOutsideBand="+plan.lowOutsideBand()+
                    " "+plan.rowDiagnostics();
            if (!plan.admitted()) return;
            if ("FULL_LABELS_ALREADY_AVAILABLE".equals(plan.reason())) return;
            java.util.List<java.util.List<FrameStructureEvidence.Line>> batches=new java.util.ArrayList<>();
            StringBuilder roiMeasurements=new StringBuilder();
            for (var roi:plan.rois()) {
                checkStop(stop);
                // Refuse native's automatic normalization/clipping and zero-size extension.
                need(roi.left()>=0&&roi.top()>=0&&roi.width()>0&&roi.height()>0
                        &&roi.right()<=frameWidth&&roi.bottom()<=frameHeight);
                String params="{\"expected\":[\".+\"],\"threshold\":0.3,\"only_rec\":false,\"roi\":["+
                        roi.left()+","+roi.top()+","+roi.width()+","+roi.height()+"]}";
                taskQuiet=false; waiting(CleanupEvidence.Item.TASKER_RUNNING_FALSE); lastTaskStatus=0;taskId=0;
                taskId=api.MaaTaskerPostRecognition(tasker,"OCR",params,image);
                need(taskId>0);stage=Stage.TASK_POSTED;
                int status=awaitTask(stop);checkStop(stop);
                readingLabelRoi=true;roiRows=null;
                try { detail(status,true); }
                finally { readingLabelRoi=false; }
                batches.add(roiRows==null?java.util.List.of():roiRows);
                appendRoiMeasurement(roiMeasurements,batches.size(),roi,roiRows);
                roiRows=null;checkStop(stop);
                if (batches.get(batches.size()-1).size()!=1) break;
            }
            var replacement=CheckInLabelRoiPlan.replace(plan,materialRows,batches,stop::requested);
            materialRoiDiagnostic+=" queries="+batches.size()+" acceptedLabels="+replacement.acceptedLabels()+
                    " replacement="+replacement.reason()+" boxSpace=ORIGINAL_IMAGE_NO_OFFSET "+roiMeasurements;
            if(materialRoiDiagnostic.length()>1500)materialRoiDiagnostic=materialRoiDiagnostic.substring(0,1500);
            checkStop(stop);
            if (replacement.accepted()) materialObservation=MaterialReplayObservation.observe(materialKind,materialSequence,
                    gray,frameWidth,frameHeight,replacement.rows(),stop::requested);
        } finally { Arrays.fill(gray,(byte)0);roiRows=null;readingLabelRoi=false; }
    }
    /** Synthetic diagnostic only: classes, coordinates and score buckets, never OCR strings. */
    private static void appendRoiMeasurement(StringBuilder out,int day,
            com.aliothmoon.maafw.rewards.RewardRegionLocator.Box roi,
            java.util.List<FrameStructureEvidence.Line> rows) {
        if(out.length()>700)return;
        out.append("roi").append(day).append("Rows=").append(rows==null?-1:rows.size());
        if(rows!=null&&rows.size()==1){
            var r=rows.get(0);int right=r.x()+r.width(),bottom=r.y()+r.height();
            boolean exact=r.text().equals("Day "+day)||r.text().equals("Day"+day)||r.text().equals("第"+day+"天");
            out.append(" exactDay=").append(exact).append(" confidenceBucket=").append((int)Math.floor(r.score()*10))
                    .append(" box=").append(r.x()).append(',').append(r.y()).append(',').append(right).append(',').append(bottom)
                    .append(" search=").append(roi.left()).append(',').append(roi.top()).append(',').append(roi.right()).append(',').append(roi.bottom())
                    .append(" wholeSearch=").append(r.x()==roi.left()&&r.y()==roi.top()&&right==roi.right()&&bottom==roi.bottom());
        }
        out.append(';');
    }
    @Override public void release(OnceBackend.Owner own) throws Exception {
        synchronized(this) {
            if(own!=owner || !returned || releaseStarted) throw new IllegalStateException();
            releaseStarted=true;
            if(imageAudit!=null&&!imageAudit.scratchClean())uncertain=true;
            if(uncertain) { provisional=null; receipts.unknownOutstanding(); return; }
        }
        try {
            // No other worker/callback can enter this API. recognize has returned,
            // admission is single-shot, and this release exclusively owns all calls.
            if(tasker!=null) {
                postStop();
                long end=System.nanoTime()+POLL_NANOS;
                while(api.MaaTaskerRunning(tasker)!=0){need(System.nanoTime()<end);pause();}
                confirmed(CleanupEvidence.Item.TASKER_RUNNING_FALSE); taskQuiet=true;
                api.MaaTaskerDestroy(tasker);confirmed(CleanupEvidence.Item.TASKER_DESTROY); tasker=null; taskerDestroyed=true;
            }
            if(resource!=null && resourceId>0) {
                long end=System.nanoTime()+POLL_NANOS;
                while(!terminal(api.MaaResourceStatus(resource,resourceId))){need(System.nanoTime()<end);pause();}
            }
            if(detailBuffer!=null){need(api.MaaStringBufferClear(detailBuffer)!=0);api.MaaStringBufferDestroy(detailBuffer);detailBuffer=null;confirmed(CleanupEvidence.Item.OCR_DETAIL_CLEAR_DESTROY);}
            // v5.15 has no MaaResourceRunning export. The resource destructor's
            // actual return is the final join boundary, never Status alone.
            if(resource!=null){api.MaaResourceDestroy(resource);confirmed(CleanupEvidence.Item.RESOURCE_DESTROY);resource=null;}
            if(image!=null){api.MaaImageBufferDestroy(image);confirmed(CleanupEvidence.Item.IMAGE_DESTROY);image=null;}
            if(nativeEncoded!=null){nativeEncoded.clear();nativeEncoded.close();confirmed(CleanupEvidence.Item.NATIVE_ENCODED_WIPE_CLOSE);nativeEncoded=null;}
            if(nodes!=null){nodes.clear();nodes.close();confirmed(CleanupEvidence.Item.NODES_WIPE_CLOSE);nodes=null;}
            if(encoded!=null){Arrays.fill(encoded.data,(byte)0);confirmed(CleanupEvidence.Item.ENCODED_WIPE);encoded=null;}
            if(row!=null){Arrays.fill(row,0);confirmed(CleanupEvidence.Item.ROW_WIPE);row=null;}
            if(bitmap!=null){bitmap.eraseColor(0);bitmap.recycle();confirmed(CleanupEvidence.Item.BITMAP_WIPE_RECYCLE);bitmap=null;}
            if(models!=null){models.close();confirmed(CleanupEvidence.Item.MODELS_CLOSE);models=null;}
            if(observationStop.requested()) provisional=null;
            // A failed creator can leave no handle: never infer its destructor returned.
            receipts.unknownOutstanding();
            synchronized(this){complete=true;}
            synchronized(MaaRecognizer.class){if(anchor==this)anchor=null;}
        } catch(InterruptedException e){provisional=null;Thread.currentThread().interrupt();uncertain=true;receipts.unknownOutstanding();throw new IOException();}
        catch(Exception | Error e){provisional=null;uncertain=true;receipts.unknownOutstanding();throw e;}
    }
    @Override public synchronized BackendPorts.Completion completion(OnceBackend.Owner own) {
        if(own==null || own!=owner) throw new IllegalStateException();
        boolean yes=complete;
        return new BackendPorts.Completion(owner,yes?BackendPorts.Released.CONFIRMED:BackendPorts.Released.UNKNOWN,
                yes?BackendPorts.Quiet.CONFIRMED:BackendPorts.Quiet.UNKNOWN);
    }
    private static final class Encoded extends OutputStream {
        final byte[] data=new byte[20*1024*1024]; int count;
        @Override public void write(int b)throws IOException{need(count<data.length);data[count++]=(byte)b;}
        @Override public void write(byte[] b,int off,int n)throws IOException {
            need(b!=null && off>=0 && n>=0 && off<=b.length && n<=b.length-off && n<=data.length-count);
            System.arraycopy(b,off,data,count,n);count+=n;
        }
    }
}






