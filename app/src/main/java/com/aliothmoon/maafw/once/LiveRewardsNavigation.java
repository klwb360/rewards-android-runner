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
        if(extraCurrent==null||!extraCurrent.getAsBoolean()||!current())throw new SecurityException("Live template authority unavailable");
        var read=reader.readLiveRun(shape,null);var r=read.template();
        if(!read.clean()||r==null||r.status()!=BingNavigationSnapshot.Status.READY||r.ready()==null
                ||r.ready().kind()!=BingNavigationSnapshot.Kind.TEMPLATE||!r.ready().fresh(SystemClock.elapsedRealtime())
                ||!extraCurrent.getAsBoolean()||!current())throw new SecurityException("Live template source unqualified");
        if(baseline==null)baseline=r.ready();
        if(!baseline.sameSource(r.ready()))throw new SecurityException("Original Rewards source changed");
        return r.ready();
    }
    boolean currentTemplateMatches(BingNavigationSnapshot.Ready source){
        try{return source!=null&&source.sameSource(template())&&current();}catch(RuntimeException unknown){return false;}
    }
    BingNavigationSnapshot.LiveVisitResult visit(BingNavigationSnapshot.Ready original){
        if(original==null||baseline==null||!baseline.sameSource(original)||!current())
            return BingNavigationSnapshot.LiveVisitResult.unknown(BingNavigationSnapshot.LiveVisitGate.INPUT);
        var result=reader.readLiveRun(shape,original);
        return result.clean()&&current()?result.visit():BingNavigationSnapshot.LiveVisitResult.unknown(BingNavigationSnapshot.LiveVisitGate.STRICT_SOURCE);
    }
}
