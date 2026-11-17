package com.aliothmoon.maafw.once;

import android.content.Context;
import android.os.SystemClock;
import java.util.function.BooleanSupplier;

/** Bound read-only source metadata for the explicit live run. No input operation is exposed here. */
final class LiveRewardsNavigation {
    private final ReviewBingNavigation reader;
    private final LiveRunAuthority authority;
    private final BingNavigationSnapshot.Shape shape;
    private final BooleanSupplier hidden;
    private BingNavigationSnapshot.Ready baseline;
    private volatile LiveNavigationDiagnostic lastDiagnostic;
    /** Passive bounded metadata receipts only. This getter never reads or revalidates a source. */
    org.json.JSONObject focusDiagnostics(){
        try{String exact=reader.focusDiagnostics();return exact==null?null:new org.json.JSONObject(exact);}
        catch(Throwable unavailable){return null;}
    }
    boolean hasFocusRejection(){return reader.hasFocusRejection();}
    /** Independent, permanently read-only follow-up. Never calls template/visit or replaces baseline. */
    void diagnoseReadOnlyFocus(LiveRunAuthority.FocusDiagnosticPermit permit)throws Exception{
        if(permit==null||!permit.live()||!permit.matches(authority)||!hasFocusRejection())
            throw new SecurityException("Exact stopped read-only focus diagnostic required");
        for(int pair=0;pair<3;pair++){
            while(permit.current()&&SystemClock.elapsedRealtime()<permit.nextDueElapsed()){
                long wait=Math.min(100,permit.nextDueElapsed()-SystemClock.elapsedRealtime());
                if(wait>0)Thread.sleep(wait);
            }
            if(!permit.current()||!hidden.getAsBoolean()||!knownClean())return;
            var observation=reader.diagnoseReadOnlyFocus(permit,shape);
            if(!observation.continueSampling())return;
        }
    }
    org.json.JSONObject lastNavigationDiagnostic()throws org.json.JSONException{
        try{var exact=lastDiagnostic;return exact==null?null:new org.json.JSONObject(exact.json());}
        catch(Throwable unavailable){return null;}
    }
    LiveRewardsNavigation(Context context,LiveRunAuthority authority,int ownTaskId,String ownComponent,
            BingNavigationSnapshot.Shape shape,BooleanSupplier hidden){
        if(context==null||authority==null||!authority.live()||shape==null||hidden==null)
            throw new IllegalArgumentException("Exact live navigation owner required");
        this.authority=authority;this.shape=shape;this.hidden=hidden;
        try{
            var installed=BingPackageMetadata.read(context.getPackageManager());
            if(installed.state()!=BingIdentityEvidence.PackageState.METADATA_OBSERVED)throw new SecurityException("Bing identity unavailable");
            int bing=context.getPackageManager().getApplicationInfo("com.microsoft.bing",0).uid;
            int system=context.getPackageManager().getApplicationInfo("com.android.systemui",0).uid;
            reader=ReviewBingNavigation.forLiveRun(context,installed,bing,system,
                    new BingNavigationSnapshot.ReadOnlyGuard(android.os.Process.myUid(),ownTaskId,ownComponent),authority,hidden);
        }catch(android.content.pm.PackageManager.NameNotFoundException missing){throw new SecurityException("Reviewed package missing",missing);}
    }
    boolean current(){return authority.current()&&hidden.getAsBoolean()&&reader.authorityMatches(shape)&&knownClean();}
    boolean knownClean(){return ReviewBingNavigation.knownClean();}
    BingNavigationSnapshot.Ready template(){return template(()->true);}
    BingNavigationSnapshot.Ready template(BooleanSupplier extraCurrent){
        lastDiagnostic=null;
        var diagnostic=LiveNavigationDiagnostic.begin(LiveNavigationDiagnostic.Call.TEMPLATE);
        long began=LiveNavigationDiagnostic.clock();boolean metadataInvoked=false;
        try{
            if(extraCurrent==null
                    ||!LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.EXTRA_BEFORE,LiveNavigationDiagnostic.Check.EXTRA_BEFORE,extraCurrent)
                    ||!LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.CURRENT_BEFORE,LiveNavigationDiagnostic.Check.CURRENT_BEFORE,this::current))
                throw new SecurityException("Live template authority unavailable");
            LiveNavigationDiagnostic.stage(diagnostic,LiveNavigationDiagnostic.Stage.METADATA_READ);metadataInvoked=true;
            var read=reader.readLiveRun(shape,null);var r=read.template();
            if(!LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.READ_CLEAN,LiveNavigationDiagnostic.Check.READ_CLEAN,read::clean)
                    ||!LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.RESULT_PRESENT,LiveNavigationDiagnostic.Check.RESULT_PRESENT,()->r!=null)
                    ||!LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.STATUS_READY,LiveNavigationDiagnostic.Check.STATUS_READY,()->r.status()==BingNavigationSnapshot.Status.READY)
                    ||!LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.SOURCE_PRESENT,LiveNavigationDiagnostic.Check.SOURCE_PRESENT,()->r.ready()!=null)
                    ||!LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.SOURCE_TEMPLATE,LiveNavigationDiagnostic.Check.SOURCE_TEMPLATE,()->r.ready().kind()==BingNavigationSnapshot.Kind.TEMPLATE)
                    ||!LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.SOURCE_FRESH,LiveNavigationDiagnostic.Check.SOURCE_FRESH,()->r.ready().fresh(SystemClock.elapsedRealtime()))
                    ||!LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.EXTRA_AFTER,LiveNavigationDiagnostic.Check.EXTRA_AFTER,extraCurrent)
                    ||!LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.CURRENT_AFTER,LiveNavigationDiagnostic.Check.CURRENT_AFTER,this::current))
                throw new SecurityException("Live template source unqualified");
            LiveNavigationDiagnostic.stage(diagnostic,LiveNavigationDiagnostic.Stage.BASELINE_BIND);
            if(!LiveNavigationDiagnostic.check(diagnostic,LiveNavigationDiagnostic.Check.BASELINE_PRESENT,baseline!=null))baseline=r.ready();
            if(!LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.BASELINE_SAME,LiveNavigationDiagnostic.Check.BASELINE_SAME,()->baseline.sameSource(r.ready())))
                throw new SecurityException("Original Rewards source changed");
            LiveNavigationDiagnostic.stage(diagnostic,LiveNavigationDiagnostic.Stage.RETURNED);return r.ready();
        }catch(RuntimeException|Error failure){LiveNavigationDiagnostic.exception(diagnostic,failure);throw failure;}
        finally{saveDiagnostic(diagnostic,metadataInvoked,began);}
    }
    boolean currentTemplateMatches(BingNavigationSnapshot.Ready source){
        try{return source!=null&&source.sameSource(template())&&current();}catch(RuntimeException unknown){return false;}
    }
    BingNavigationSnapshot.LiveVisitResult visit(BingNavigationSnapshot.Ready original){
        lastDiagnostic=null;
        var diagnostic=LiveNavigationDiagnostic.begin(LiveNavigationDiagnostic.Call.VISIT);
        long began=LiveNavigationDiagnostic.clock();boolean metadataInvoked=false;
        try{
            if(!LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.ORIGINAL_PRESENT,LiveNavigationDiagnostic.Check.ORIGINAL_PRESENT,()->original!=null)
                    ||!LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.BASELINE_BIND,LiveNavigationDiagnostic.Check.BASELINE_PRESENT,()->baseline!=null)
                    ||!LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.ORIGINAL_SAME,LiveNavigationDiagnostic.Check.ORIGINAL_SAME,()->baseline.sameSource(original))
                    ||!LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.CURRENT_BEFORE,LiveNavigationDiagnostic.Check.CURRENT_BEFORE,this::current))
                return BingNavigationSnapshot.LiveVisitResult.unknown(BingNavigationSnapshot.LiveVisitGate.INPUT);
            LiveNavigationDiagnostic.stage(diagnostic,LiveNavigationDiagnostic.Stage.METADATA_READ);metadataInvoked=true;
            var result=reader.readLiveRun(shape,original);
            var qualified=LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.READ_CLEAN,LiveNavigationDiagnostic.Check.READ_CLEAN,result::clean)
                    &&LiveNavigationDiagnostic.observe(diagnostic,LiveNavigationDiagnostic.Stage.CURRENT_AFTER,LiveNavigationDiagnostic.Check.CURRENT_AFTER,this::current);
            if(qualified)LiveNavigationDiagnostic.stage(diagnostic,LiveNavigationDiagnostic.Stage.RETURNED);
            return qualified?result.visit():BingNavigationSnapshot.LiveVisitResult.unknown(BingNavigationSnapshot.LiveVisitGate.STRICT_SOURCE);
        }catch(RuntimeException|Error failure){LiveNavigationDiagnostic.exception(diagnostic,failure);throw failure;}
        finally{saveDiagnostic(diagnostic,metadataInvoked,began);}
    }
    private void saveDiagnostic(LiveNavigationDiagnostic.Trace diagnostic,boolean metadataInvoked,long began){
        try{
            if(metadataInvoked)LiveNavigationDiagnostic.metadata(diagnostic,reader.lastLiveNavigationDiagnostic());
            lastDiagnostic=LiveNavigationDiagnostic.finish(diagnostic,LiveNavigationDiagnostic.elapsed(began,LiveNavigationDiagnostic.clock()));
        }catch(Throwable ignored){lastDiagnostic=null;}
    }
}
