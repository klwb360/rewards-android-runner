package com.aliothmoon.maafw.once;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** Legacy 12/2/4 counters are unchanged. A live root phase permit separately grants at most 3 pre + 1 post pairs.
 * Each pair is only the two
 * fixed WM/Activity read-only commands. No input, body, UI tree, screenshot or generic shell.
 * These commands cannot establish absence of a WebView's own HTML modal. */
final class ReviewBingNavigation {
    private static final AtomicInteger PAIRS=new AtomicInteger();
    private static final AtomicInteger PREFLIGHT_PAIRS=new AtomicInteger();
    private static final AtomicInteger READ_ONLY_PAIRS=new AtomicInteger();
    private final BingNavigationSnapshot.ReadOnlyGuard readOnlyGuard;
    private final CaptureSessionRuntime.RootSourceReadPermit rootSourcePermit;
    private final CaptureSessionRuntime.RootRecoveryReadPermit rootRecoveryPermit;
    private final CaptureSessionRuntime.RootCurrentReadPermit rootCurrentPermit;
    enum FixedStatus { NOT_INVOKED, AUTHORITY_REJECTED, PROCESS_NULL, STREAM_MISSING, BUFFER_LIMIT, READ_TIMEOUT, EXIT_NONZERO, IO_UNKNOWN, READ_COMPLETE, CLEANUP_UNKNOWN }
    enum PairGate { NOT_INVOKED, PERMIT_REJECTED, AUTHORITY_REJECTED, BUDGET_REJECTED, WINDOWS_READ, ACTIVITIES_READ, POST_AUTHORITY, PARSED }
    record FixedRead(FixedStatus status,int bytes,boolean invoked,boolean exitObserved,int exitCode,boolean quiet,boolean knownClean) {
        static FixedRead notRun(){return new FixedRead(FixedStatus.NOT_INVOKED,0,false,false,-1,true,true);}
    }
    record ReadDiagnostic(int pair,PairGate gate,FixedRead windows,FixedRead activities,
            BingNavigationSnapshot.FirstGate parserGate,BingNavigationSnapshot.ReadOnlyGate readOnlyGate,boolean knownClean,BingNavigationSnapshot.TopDiagnostic top) {
        ReadDiagnostic(int pair,PairGate gate,FixedRead windows,FixedRead activities,BingNavigationSnapshot.FirstGate parserGate,BingNavigationSnapshot.ReadOnlyGate readOnlyGate,boolean knownClean){
            this(pair,gate,windows,activities,parserGate,readOnlyGate,knownClean,BingNavigationSnapshot.TopDiagnostic.notObserved());
        }
        String summary(){return "navigationReadPair="+pair+" readGate="+gate+" windowsRead="+windows+" activitiesRead="+activities
            +" parserFirstGate="+parserGate+" strictMetadataGate="+readOnlyGate+" metadataKnownClean="+knownClean+" topResumedDiagnostic="+top;}
    }
    private volatile FixedRead lastFixed=FixedRead.notRun();
    private volatile ReadDiagnostic lastDiagnostic=new ReadDiagnostic(0,PairGate.NOT_INVOKED,FixedRead.notRun(),FixedRead.notRun(),
        BingNavigationSnapshot.FirstGate.NOT_APPLIED,BingNavigationSnapshot.ReadOnlyGate.NOT_APPLIED,true);
    private volatile BingNavigationSnapshot.RecoveryResult lastRecovery;
    private volatile BingNavigationSnapshot.CurrentTemplateResult lastCurrentTemplate;
    private volatile BingNavigationSnapshot.StatusRecoveryInitial lastStatusRecovery;private boolean statusRecoveryNavigation;
    private volatile BingNavigationSnapshot.TimedTaskGuard lastTimedTaskGuard;
    private int diagnosticPairs;
    ReadDiagnostic lastReadDiagnostic(){return lastDiagnostic;}
    private BingNavigationSnapshot.Result noRead(){lastDiagnostic=new ReadDiagnostic(++diagnosticPairs,PairGate.PERMIT_REJECTED,FixedRead.notRun(),FixedRead.notRun(),BingNavigationSnapshot.FirstGate.NOT_APPLIED,BingNavigationSnapshot.ReadOnlyGate.NOT_APPLIED,knownClean());return BingNavigationSnapshot.Result.unknown();}

    private volatile BingNavigationSnapshot.ReadOnlyGate lastReadOnlyGate=BingNavigationSnapshot.ReadOnlyGate.NOT_APPLIED;
    BingNavigationSnapshot.ReadOnlyGate lastReadOnlyGate(){return lastReadOnlyGate;}
    private final boolean rootPreflight;
    private boolean selectedPendingNavigation,uniquePendingNavigation;
    private static volatile Object quarantine;
    private final Context context;
    private final BingIdentityEvidence.Installed installed;
    private final int bingUid,systemUiUid;
    ReviewBingNavigation(Context context,BingIdentityEvidence.Installed installed,int bingUid,int systemUiUid){
        this(context,installed,bingUid,systemUiUid,false);
    }
    static ReviewBingNavigation forRootPreflight(Context context,BingIdentityEvidence.Installed installed,int bingUid,int systemUiUid){
        return new ReviewBingNavigation(context,installed,bingUid,systemUiUid,true);
    }
    static ReviewBingNavigation forUniquePending(Context context,BingIdentityEvidence.Installed installed,int bingUid,int systemUiUid,int ownTaskId,String ownActivityComponent){
        if(!RootUniqueRefinedScope.ACTIVE||!RootUniqueRefinedScope.current(System.currentTimeMillis()))throw new SecurityException("New unique navigation scope required");
        var guard=new BingNavigationSnapshot.ReadOnlyGuard(android.os.Process.myUid(),ownTaskId,ownActivityComponent);
        if(!guard.valid())throw new SecurityException("Exact own task required");
        var reader=new ReviewBingNavigation(context,installed,bingUid,systemUiUid,false,guard);reader.selectedPendingNavigation=true;reader.uniquePendingNavigation=true;return reader;
    }
    static ReviewBingNavigation forSelectedPending(Context context,BingIdentityEvidence.Installed installed,int bingUid,int systemUiUid,int ownTaskId,String ownActivityComponent){
        if(!RootSelectedPendingScope.ACTIVE||!RootSelectedPendingScope.current(System.currentTimeMillis()))throw new SecurityException("New selected navigation scope required");
        var guard=new BingNavigationSnapshot.ReadOnlyGuard(android.os.Process.myUid(),ownTaskId,ownActivityComponent);
        if(!guard.valid())throw new SecurityException("Exact own task required");
        var reader=new ReviewBingNavigation(context,installed,bingUid,systemUiUid,false,guard);reader.selectedPendingNavigation=true;return reader;
    }
    private ReviewBingNavigation(Context context,BingIdentityEvidence.Installed installed,int bingUid,int systemUiUid,boolean rootPreflight){
        this(context,installed,bingUid,systemUiUid,rootPreflight,null);
    }
    static ReviewBingNavigation forRootReadOnly(Context context,BingIdentityEvidence.Installed installed,int bingUid,int systemUiUid,
            int ownTaskId,String ownActivityComponent){
        return new ReviewBingNavigation(context,installed,bingUid,systemUiUid,false,
            new BingNavigationSnapshot.ReadOnlyGuard(android.os.Process.myUid(),ownTaskId,ownActivityComponent));
    }
    private ReviewBingNavigation(Context context,BingIdentityEvidence.Installed installed,int bingUid,int systemUiUid,
            boolean rootPreflight,BingNavigationSnapshot.ReadOnlyGuard readOnlyGuard){
        this(context,installed,bingUid,systemUiUid,rootPreflight,readOnlyGuard,null);
    }
    static ReviewBingNavigation forRootPhaseSource(Context context,BingIdentityEvidence.Installed installed,int bingUid,int systemUiUid,
            CaptureSessionRuntime.RootSourceReadPermit permit){
        if(permit==null)throw new SecurityException("Exact live source permit required");
        return new ReviewBingNavigation(context,installed,bingUid,systemUiUid,false,permit.claimReader(),permit);
    }
    static ReviewBingNavigation forReturnRead(Context context,BingIdentityEvidence.Installed installed,int bingUid,int systemUiUid,
            CaptureSessionRuntime.RootRecoveryReadPermit permit){
        if(permit==null)throw new SecurityException("Exact new recovery permit required");
        var guard=permit.claimReader();if(guard==null)throw new SecurityException("Exact recovery guard required");
        return new ReviewBingNavigation(context,installed,bingUid,systemUiUid,false,guard,null,permit);
    }
    static ReviewBingNavigation forStatusRecovery(Context context,BingIdentityEvidence.Installed installed,int bingUid,int systemUiUid,CaptureSessionRuntime.RootRecoveryReadPermit permit){
        if(permit==null)throw new SecurityException("New unified status permit required");var guard=permit.claimReader();
        if(guard==null||!guard.valid())throw new SecurityException("Exact hidden own-window guard required");
        var reader=new ReviewBingNavigation(context,installed,bingUid,systemUiUid,false,guard,null,permit);reader.statusRecoveryNavigation=true;return reader;
    }
    private ReviewBingNavigation(Context context,BingIdentityEvidence.Installed installed,int bingUid,int systemUiUid,
            boolean rootPreflight,BingNavigationSnapshot.ReadOnlyGuard readOnlyGuard,CaptureSessionRuntime.RootSourceReadPermit permit){
        this(context,installed,bingUid,systemUiUid,rootPreflight,readOnlyGuard,permit,null);
    }
    static ReviewBingNavigation forCurrentRead(Context context,BingIdentityEvidence.Installed installed,int bingUid,int systemUiUid,
            CaptureSessionRuntime.RootCurrentReadPermit permit){
        if(permit==null)throw new SecurityException("Exact new current-read permit required");
        var guard=permit.claimReader();if(guard==null)throw new SecurityException("Exact current-read guard required");
        return new ReviewBingNavigation(context,installed,bingUid,systemUiUid,false,guard,null,null,permit);
    }
    private ReviewBingNavigation(Context context,BingIdentityEvidence.Installed installed,int bingUid,int systemUiUid,
            boolean rootPreflight,BingNavigationSnapshot.ReadOnlyGuard readOnlyGuard,CaptureSessionRuntime.RootSourceReadPermit permit,
            CaptureSessionRuntime.RootRecoveryReadPermit recoveryPermit){
        this(context,installed,bingUid,systemUiUid,rootPreflight,readOnlyGuard,permit,recoveryPermit,null);
    }
    private ReviewBingNavigation(Context context,BingIdentityEvidence.Installed installed,int bingUid,int systemUiUid,
            boolean rootPreflight,BingNavigationSnapshot.ReadOnlyGuard readOnlyGuard,CaptureSessionRuntime.RootSourceReadPermit permit,
            CaptureSessionRuntime.RootRecoveryReadPermit recoveryPermit,CaptureSessionRuntime.RootCurrentReadPermit currentPermit){
        this.rootPreflight=rootPreflight;this.readOnlyGuard=readOnlyGuard;this.rootSourcePermit=permit;this.rootRecoveryPermit=recoveryPermit;this.rootCurrentPermit=currentPermit;
        if(readOnlyGuard!=null&&!readOnlyGuard.valid())throw new IllegalArgumentException("readonly metadata profile");
        if(context==null||installed==null||bingUid<10000||systemUiUid<10000)throw new IllegalArgumentException("native metadata profile");
        this.context=context.getApplicationContext();this.installed=installed;this.bingUid=bingUid;this.systemUiUid=systemUiUid;
    }
    static boolean knownClean(){return quarantine==null;}
    private boolean process(){return Looper.myLooper()!=Looper.getMainLooper()&&android.os.Process.myUid()>=10000
        &&"com.aliothmoon.maafw.productrewardsworking.capturereview".equals(context.getPackageName())
        &&(context.getPackageName()+":capture_once").equals(Application.getProcessName());}
    /** Scalar revalidation at final UI publication. Does not claim a reader, execute shell,
     * recapture, or revive an expired epoch. Original off-main reader authority is unchanged. */
    boolean currentPublicationAuthorityMatches(BingNavigationSnapshot.Shape shape){
        if(Looper.myLooper()!=Looper.getMainLooper()||rootCurrentPermit==null||quarantine!=null
            ||android.os.Process.myUid()<10000||!"com.aliothmoon.maafw.productrewardsworking.capturereview".equals(context.getPackageName())
            ||!(context.getPackageName()+":capture_once").equals(Application.getProcessName()))return false;
        try{
            return installed.equals(BingPackageMetadata.read(context.getPackageManager()))
                &&Shizuku.pingBinder()&&!Shizuku.isPreV11()&&Shizuku.getUid()==0
                &&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED&&shape!=null&&shape.equals(display());
        }catch(RuntimeException unknown){return false;}
    }
    boolean publicationAuthorityMatches(BingNavigationSnapshot.Shape shape){
        if(Looper.myLooper()!=Looper.getMainLooper()||rootSourcePermit==null||quarantine!=null
            ||android.os.Process.myUid()<10000||!"com.aliothmoon.maafw.productrewardsworking.capturereview".equals(context.getPackageName())
            ||!(context.getPackageName()+":capture_once").equals(Application.getProcessName()))return false;
        try{
            return installed.equals(BingPackageMetadata.read(context.getPackageManager()))
                &&Shizuku.pingBinder()&&!Shizuku.isPreV11()&&Shizuku.getUid()==0
                &&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED&&shape!=null&&shape.equals(display());
        }catch(RuntimeException unknown){return false;}
    }
    boolean authorityMatches(BingNavigationSnapshot.Shape shape){
        if(selectedPendingNavigation&&!(uniquePendingNavigation?RootUniqueRefinedScope.current(System.currentTimeMillis()):RootSelectedPendingScope.current(System.currentTimeMillis())))return false;
        if(!process()||quarantine!=null||!installed.equals(BingPackageMetadata.read(context.getPackageManager())))return false;
        try{
            if(!Shizuku.pingBinder()||Shizuku.isPreV11()||Shizuku.getUid()!=0||Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)return false;
            return shape!=null&&shape.equals(display());
        }catch(RuntimeException unknown){return false;}
    }
    private BingNavigationSnapshot.Shape display(){
        var manager=context.getSystemService(DisplayManager.class);var d=manager==null?null:manager.getDisplay(Display.DEFAULT_DISPLAY);
        if(d==null||d.getState()!=Display.STATE_ON||android.os.Build.VERSION.SDK_INT<34)return null;
        var windows=context.createDisplayContext(d).getSystemService(WindowManager.class);if(windows==null)return null;
        var b=windows.getMaximumWindowMetrics().getBounds();
        return b.left==0&&b.top==0?new BingNavigationSnapshot.Shape(b.width(),b.height(),d.getRotation()):null;
    }
    BingNavigationSnapshot.Result read(BingNavigationSnapshot.Shape shape){return readPair(shape,false);}
    BingNavigationSnapshot.Result readSelected(BingNavigationSnapshot.Shape shape,BingNavigationSnapshot.Ready original){
        if(!selectedPendingNavigation)return noRead();
        return readPair(shape,false,false,false,original);
    }
    BingNavigationSnapshot.TimedTaskGuard readTimedTaskGuard(BingNavigationSnapshot.Shape shape,BingNavigationSnapshot.Ready original){
        lastTimedTaskGuard=null;
        if(!selectedPendingNavigation||original==null){noRead();return BingNavigationSnapshot.TimedTaskGuard.notObserved();}
        readPair(shape,false,false,false,original,true);
        return lastTimedTaskGuard==null?BingNavigationSnapshot.TimedTaskGuard.notObserved():lastTimedTaskGuard;
    }
    BingNavigationSnapshot.Result readTimedReturn(BingNavigationSnapshot.Shape shape,BingNavigationSnapshot.Ready original){
        if(!selectedPendingNavigation||original==null)return noRead();
        return readPair(shape,false,false,false,original,false,true);
    }
    BingNavigationSnapshot.Result readPreTimedReturn(BingNavigationSnapshot.Shape shape,BingNavigationSnapshot.Ready original){
        if(original==null||rootSourcePermit==null||!rootSourcePermit.readPre())return noRead();
        return readPair(shape,true,false,false,original,false,true);
    }
    BingNavigationSnapshot.Result readPostTimedReturn(BingNavigationSnapshot.Shape shape,BingNavigationSnapshot.Ready original){
        if(original==null||rootSourcePermit==null||!rootSourcePermit.readPost())return noRead();
        return readPair(shape,true,false,false,original,false,true);
    }
    BingNavigationSnapshot.Result readPre(BingNavigationSnapshot.Shape shape){
        lastReadOnlyGate=BingNavigationSnapshot.ReadOnlyGate.LEGACY_METADATA;
        if(rootSourcePermit==null||!rootSourcePermit.readPre())return noRead();
        return readPair(shape,true);
    }
    BingNavigationSnapshot.Result readPost(BingNavigationSnapshot.Shape shape){
        lastReadOnlyGate=BingNavigationSnapshot.ReadOnlyGate.LEGACY_METADATA;
        if(rootSourcePermit==null||!rootSourcePermit.readPost())return noRead();
        return readPair(shape,true);
    }
    BingNavigationSnapshot.RecoveryResult readRecovery(BingNavigationSnapshot.Shape shape){
        lastRecovery=null;var r=readPair(shape,false,true);var recovery=lastRecovery;
        return recovery!=null?recovery:new BingNavigationSnapshot.RecoveryResult(r.status(),r.ready(),null,r.firstGate());
    }
    BingNavigationSnapshot.StatusRecoveryInitial readStatusRecovery(BingNavigationSnapshot.Shape shape){
        if(!statusRecoveryNavigation){noRead();return new BingNavigationSnapshot.StatusRecoveryInitial(BingNavigationSnapshot.Status.UNKNOWN,null,null,BingNavigationSnapshot.FirstGate.INPUT,BingNavigationSnapshot.ReadOnlyGate.NOT_APPLIED);}
        lastStatusRecovery=null;var r=readPair(shape,false,true);var initial=lastStatusRecovery;
        return initial!=null?initial:new BingNavigationSnapshot.StatusRecoveryInitial(r.status(),null,null,r.firstGate(),r.readOnlyGate());
    }
    private BingNavigationSnapshot.Result readPair(BingNavigationSnapshot.Shape shape,boolean sourceAuthorized){return readPair(shape,sourceAuthorized,false);}
    BingNavigationSnapshot.CurrentTemplateResult readCurrentTemplate(BingNavigationSnapshot.Shape shape){
        lastCurrentTemplate=null;var r=readPair(shape,false,false,true);var current=lastCurrentTemplate;
        return current!=null?current:new BingNavigationSnapshot.CurrentTemplateResult(r.status(),null,r.firstGate(),r.readOnlyGate());
    }
    private BingNavigationSnapshot.Result readPair(BingNavigationSnapshot.Shape shape,boolean sourceAuthorized,boolean recovery){return readPair(shape,sourceAuthorized,recovery,false);}
    private BingNavigationSnapshot.Result readPair(BingNavigationSnapshot.Shape shape,boolean sourceAuthorized,boolean recovery,boolean currentTemplate){return readPair(shape,sourceAuthorized,recovery,currentTemplate,null);}
    private BingNavigationSnapshot.Result readPair(BingNavigationSnapshot.Shape shape,boolean sourceAuthorized,boolean recovery,boolean currentTemplate,BingNavigationSnapshot.Ready selectedOriginal){
        return readPair(shape,sourceAuthorized,recovery,currentTemplate,selectedOriginal,false);
    }
    private BingNavigationSnapshot.Result readPair(BingNavigationSnapshot.Shape shape,boolean sourceAuthorized,boolean recovery,boolean currentTemplate,BingNavigationSnapshot.Ready selectedOriginal,boolean timedTask){
        return readPair(shape,sourceAuthorized,recovery,currentTemplate,selectedOriginal,timedTask,false);
    }
    private BingNavigationSnapshot.Result readPair(BingNavigationSnapshot.Shape shape,boolean sourceAuthorized,boolean recovery,boolean currentTemplate,BingNavigationSnapshot.Ready selectedOriginal,boolean timedTask,boolean timedReturn){
        int pair=++diagnosticPairs;PairGate gate=PairGate.PERMIT_REJECTED;
        FixedRead wd=FixedRead.notRun(),ad=FixedRead.notRun();BingNavigationSnapshot.Result result=BingNavigationSnapshot.Result.unknown();
        byte[] windows=null,activities=null;BingNavigationSnapshot.TopDiagnostic top=BingNavigationSnapshot.TopDiagnostic.notObserved();
        try{
            if(readOnlyGuard!=null)lastReadOnlyGate=BingNavigationSnapshot.ReadOnlyGate.LEGACY_METADATA;
            if(rootSourcePermit!=null&&(!sourceAuthorized||!rootSourcePermit.current()))return result;
            if(rootRecoveryPermit!=null&&(!rootRecoveryPermit.current()||!rootRecoveryPermit.read()))return result;
            if(rootCurrentPermit!=null&&(!rootCurrentPermit.current()||!rootCurrentPermit.read()))return result;
            if(recovery&&rootRecoveryPermit==null||currentTemplate&&rootCurrentPermit==null)return result;
            gate=PairGate.AUTHORITY_REJECTED;if(!authorityMatches(shape))return result;
            gate=PairGate.BUDGET_REJECTED;
            if(rootSourcePermit==null&&rootRecoveryPermit==null&&rootCurrentPermit==null&&(selectedPendingNavigation?PAIRS.incrementAndGet()>12:readOnlyGuard!=null?READ_ONLY_PAIRS.incrementAndGet()>4:rootPreflight?PREFLIGHT_PAIRS.incrementAndGet()>2:PAIRS.incrementAndGet()>12))return result;
            long lower=SystemClock.elapsedRealtime();gate=PairGate.WINDOWS_READ;
            windows=readFixed(true);wd=lastFixed;if(windows==null)return result;
            gate=PairGate.ACTIVITIES_READ;activities=readFixed(false);ad=lastFixed;if(activities==null)return result;
            if(!timedTask&&!timedReturn)top=BingNavigationSnapshot.topDiagnostic(new String(activities,java.nio.charset.StandardCharsets.UTF_8));
            gate=PairGate.POST_AUTHORITY;
            if(!authorityMatches(shape)||rootSourcePermit!=null&&!rootSourcePermit.current()||rootRecoveryPermit!=null&&!rootRecoveryPermit.current()||rootCurrentPermit!=null&&!rootCurrentPermit.current())return result;
            String w=new String(windows,java.nio.charset.StandardCharsets.UTF_8),a=new String(activities,java.nio.charset.StandardCharsets.UTF_8);
            var profile=new BingNavigationSnapshot.Profile(bingUid,systemUiUid,shape);long observed=SystemClock.elapsedRealtime();
            if(timedReturn){
                result=BingNavigationSnapshot.parseTimedReturn(w,a,profile,readOnlyGuard,selectedOriginal,lower,observed);
            }else if(timedTask){
                lastTimedTaskGuard=BingNavigationSnapshot.parseTimedTaskGuard(w,a,profile,readOnlyGuard,selectedOriginal,lower,observed);
                boolean qualified=lastTimedTaskGuard.matches(selectedOriginal,observed);
                // Deliberately no Ready or normal-navigation success escapes this dedicated path.
                result=new BingNavigationSnapshot.Result(BingNavigationSnapshot.Status.UNKNOWN,null,BingNavigationSnapshot.ReadOnlyGate.NOT_APPLIED,
                    qualified?BingNavigationSnapshot.FirstGate.TIMED_TASK_PRESENT:BingNavigationSnapshot.FirstGate.TIMED_TASK_REJECTED);
            }else if(currentTemplate){
                lastCurrentTemplate=BingNavigationSnapshot.parseCurrentTemplate(w,a,profile,readOnlyGuard,lower,observed);
                result=new BingNavigationSnapshot.Result(lastCurrentTemplate.status(),lastCurrentTemplate.source()==null?null:lastCurrentTemplate.source().ready(),
                    lastCurrentTemplate.readOnlyGate(),lastCurrentTemplate.firstGate());
            }else if(recovery&&statusRecoveryNavigation){
                lastStatusRecovery=BingNavigationSnapshot.parseStatusRecovery(w,a,profile,readOnlyGuard,lower,observed);
                var ready=lastStatusRecovery.template()!=null?lastStatusRecovery.template().ready():lastStatusRecovery.browser()!=null?lastStatusRecovery.browser().browser():null;
                result=new BingNavigationSnapshot.Result(lastStatusRecovery.status(),ready,lastStatusRecovery.readOnlyGate(),lastStatusRecovery.firstGate());
            }else if(recovery){
                lastRecovery=BingNavigationSnapshot.parseReturnRead(w,a,profile,readOnlyGuard,lower,observed);
                result=new BingNavigationSnapshot.Result(lastRecovery.status(),lastRecovery.ready(),lastRecovery.status()==BingNavigationSnapshot.Status.READY?
                    BingNavigationSnapshot.ReadOnlyGate.METADATA_STABLE_ONLY:lastRecovery.readOnlyGate(),lastRecovery.firstGate());
            }else result=selectedPendingNavigation?BingNavigationSnapshot.parseSelectedNavigation(w,a,profile,readOnlyGuard,selectedOriginal,lower,observed):readOnlyGuard==null?BingNavigationSnapshot.parse(w,a,profile,lower,observed)
                :BingNavigationSnapshot.parseRootReadOnly(w,a,profile,readOnlyGuard,lower,observed);
            if(readOnlyGuard!=null&&result.readOnlyGate()!=BingNavigationSnapshot.ReadOnlyGate.NOT_APPLIED)lastReadOnlyGate=result.readOnlyGate();
            gate=PairGate.PARSED;return result;
        }finally{
            if(windows!=null)Arrays.fill(windows,(byte)0);if(activities!=null)Arrays.fill(activities,(byte)0);
            lastDiagnostic=new ReadDiagnostic(pair,gate,wd,ad,result.firstGate(),result.readOnlyGate(),knownClean(),top);
        }
    }
    private boolean seriesDiagnosticsSpent;
    String seriesDiagnostics(BingNavigationSnapshot.Ready source){
        if(seriesDiagnosticsSpent)throw new SecurityException("Series diagnostic read spent");seriesDiagnosticsSpent=true;
        if(rootSourcePermit==null||!rootSourcePermit.seriesDiagnosticAllowed()||source==null||!authorityMatches(source.profile().shape()))
            throw new SecurityException("Exact series source required");
        byte[] client=null,sf=null;long lower=System.nanoTime();
        try{
            client=readFixedCommand(new String[]{"/system/bin/dumpsys","activity",BingNavigationSnapshot.TEMPLATE});
            if(!knownClean())throw new SecurityException("Client metadata resource unknown");
            if(client==null)throw new SecurityException("Client metadata read failed");
            if(!rootSourcePermit.current())throw new SecurityException("Series cancelled during diagnostic");
            sf=readFixedCommand(new String[]{"/system/bin/dumpsys","SurfaceFlinger"});
            if(!knownClean())throw new SecurityException("Surface metadata resource unknown");
            if(sf==null)throw new SecurityException("Surface metadata read failed");
            if(!rootSourcePermit.current()||!authorityMatches(source.profile().shape()))throw new SecurityException("Series source authority changed");
            return "metadataSampleStartNs="+lower+" metadataSampleEndNs="+System.nanoTime()+" atomicWithCapture=false\n"
                +RootSeriesMetadata.parse(client==null?null:new String(client,java.nio.charset.StandardCharsets.UTF_8),
                    sf==null?null:new String(sf,java.nio.charset.StandardCharsets.UTF_8),source).summary();
        }finally{if(client!=null)Arrays.fill(client,(byte)0);if(sf!=null)Arrays.fill(sf,(byte)0);}
    }
    private byte[] readFixed(boolean windows){
        return readFixedCommand(windows?(readOnlyGuard==null?new String[]{"/system/bin/dumpsys","window"}:new String[]{"/system/bin/dumpsys","window","-a"})
            :new String[]{"/system/bin/dumpsys","activity","activities"});
    }
    private byte[] readFixedCommand(String[] command){
        byte[] bytes=new byte[262144];IRemoteProcess process=null;InputStream input=null;
        ParcelFileDescriptor stdout=null,stdin=null,stderr=null;boolean invoked=false,quiet=false,clean=false,exitObserved=false;
        int count=0,exit=-1;FixedStatus status=FixedStatus.AUTHORITY_REJECTED;
        try{
            var binder=Shizuku.getBinder();if(binder==null||!binder.isBinderAlive()||!Shizuku.pingBinder()||Shizuku.isPreV11()
                ||Shizuku.getUid()!=0||Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)return null;
            var service=IShizukuService.Stub.asInterface(binder);if(service==null)return null;
            invoked=true;status=FixedStatus.PROCESS_NULL;process=service.newProcess(command,null,null);
            if(process==null)throw new IllegalStateException();
            status=FixedStatus.STREAM_MISSING;stdout=process.getInputStream();stdin=process.getOutputStream();stderr=process.getErrorStream();
            if(stdout==null)throw new IllegalStateException();input=new ParcelFileDescriptor.AutoCloseInputStream(stdout);stdout=null;status=FixedStatus.IO_UNKNOWN;
            long end=SystemClock.elapsedRealtime()+2000;
            for(;;){int available=input.available();
                if(available>0){if(count==bytes.length){status=FixedStatus.BUFFER_LIMIT;throw new IllegalStateException();}
                    int n=input.read(bytes,count,Math.min(available,bytes.length-count));if(n<0){status=FixedStatus.IO_UNKNOWN;throw new IllegalStateException();}count+=n;}
                quiet=process.waitForTimeout(0,"MILLISECONDS");if(quiet&&input.available()==0)break;
                if(SystemClock.elapsedRealtime()>=end){status=FixedStatus.READ_TIMEOUT;throw new IllegalStateException();}if(available==0)Thread.sleep(5);
            }
            exit=process.exitValue();exitObserved=true;if(exit!=0){status=FixedStatus.EXIT_NONZERO;return null;}
            input.close();input=null;if(stdin!=null){stdin.close();stdin=null;}if(stderr!=null){stderr.close();stderr=null;}clean=true;
            status=FixedStatus.READ_COMPLETE;return Arrays.copyOf(bytes,count);
        }catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            if(status==FixedStatus.AUTHORITY_REJECTED||status==FixedStatus.READ_COMPLETE)status=FixedStatus.IO_UNKNOWN;return null;}
        finally{
            Arrays.fill(bytes,(byte)0);
            if(invoked&&!clean){
                try{if(process!=null&&!quiet){process.destroy();quiet=process.waitForTimeout(500,"MILLISECONDS");}
                    if(input!=null)input.close();if(stdout!=null)stdout.close();if(stdin!=null)stdin.close();if(stderr!=null)stderr.close();clean=quiet;
                }catch(Exception unknown){clean=false;}
                if(!clean)quarantine=new Object[]{process,input,stdout,stdin,stderr};
            }
            boolean cleanKnown=(!invoked||clean)&&knownClean();
            lastFixed=new FixedRead(cleanKnown?status:FixedStatus.CLEANUP_UNKNOWN,count,invoked,exitObserved,exit,quiet||!invoked,cleanKnown);
        }
    }
}


