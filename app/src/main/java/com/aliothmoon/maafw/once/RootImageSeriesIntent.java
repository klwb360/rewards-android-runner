package com.aliothmoon.maafw.once;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** One original visible intent, at most three independent read-only claims. No business authority. */
final class RootImageSeriesIntent {
    private final RealCaptureClaim.ProcessEpoch epoch;
    private final Object rootOwner,visibleOwner,request;
    private final long issued,sessionEnd;
    private final LongSupplier utcClock;
    private final boolean live;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private int next=1;
    private final Object[] issuedOwners=new Object[RootImageSeriesScope.MAX_FRAMES];
    private BingNavigationSnapshot.Ready firstSource;
    private long handoffRequestMono=-1,handoffHiddenMono=-1;
    private RootImageSeriesIntent(VisibleCaptureAuthority.SeriesApproval a){
        epoch=a.epoch;rootOwner=a.rootOwner;visibleOwner=a.visibleOwner;request=a.request;
        issued=a.issued;sessionEnd=a.sessionEnd;utcClock=a.utcClock;live=a.live;
    }
    static RootImageSeriesIntent fromVisible(VisibleCaptureAuthority.SeriesApproval a,long now){
        if(a==null||!a.consume(now))throw new SecurityException("Original series approval required");
        return new RootImageSeriesIntent(a);
    }
    boolean matchesOwner(Object root,Object visible,Object req){return root==rootOwner&&visible==visibleOwner&&req==request;}
    boolean live(){return live;}long sessionEnd(){return sessionEnd;}Object rootOwner(){return rootOwner;}
    boolean current(long now){try{return !cancelled.get()&&now>=issued&&now<sessionEnd&&RootImageSeriesScope.current(utcClock.getAsLong());}catch(RuntimeException unknown){return false;}}
    void cancel(){cancelled.set(true);}
    synchronized void requestHandoff(long mono){if(mono<0||handoffRequestMono>=0)throw new SecurityException("One handoff only");handoffRequestMono=mono;}
    synchronized void hiddenHandoff(long mono){if(handoffRequestMono<0||handoffHiddenMono>=0||mono<handoffRequestMono)throw new SecurityException("Original hidden callback required");handoffHiddenMono=mono;}
    synchronized String handoffSummary(){return "handoffClock=CLOCK_MONOTONIC requestNs="+handoffRequestMono+" hiddenNs="+handoffHiddenMono;}
    synchronized boolean sourceMatches(int index,BingNavigationSnapshot.Ready source){
        if(!RootImageSeriesScope.validIndex(index)||source==null||source.kind()!=BingNavigationSnapshot.Kind.TEMPLATE)return false;
        if(firstSource==null){if(index!=1)return false;firstSource=source;return true;}
        return firstSource.sameSource(source);
    }
    synchronized FramePermit claimFrame(int index,RealCaptureClaim.ProcessEpoch exact,Object requestOwner,long now,long deadline,
            CaptureSessionRuntime.SeriesCompletion previous,boolean forLive){
        if(forLive!=live||exact!=epoch||requestOwner==null||index!=next||index<1||index>RootImageSeriesScope.MAX_FRAMES
                ||!current(now)||deadline<=now||deadline>sessionEnd||now>Long.MAX_VALUE-RootImageSeriesScope.CAPTURE_TTL
                ||deadline>now+RootImageSeriesScope.CAPTURE_TTL||(index==1&&!RootImageSeriesScope.admissionCurrent(utcClock.getAsLong())))
            throw new SecurityException("Current exact series frame required");
        for(Object used:issuedOwners)if(used==requestOwner)throw new SecurityException("Distinct frame owner required");
        if(index==1){if(previous!=null)throw new SecurityException("First frame has no prior completion");}
        else if(previous==null||!previous.consume(this,index-1,epoch,now))throw new SecurityException("Previous actual clean frame required");
        issuedOwners[index-1]=requestOwner;next++;return new FramePermit(this,index,epoch,requestOwner,now,deadline,live);
    }
    static final class FramePermit {
        final RootImageSeriesIntent series;final int index;final RealCaptureClaim.ProcessEpoch epoch;
        final Object owner;final long issued,deadline;final boolean live;
        private FramePermit(RootImageSeriesIntent s,int i,RealCaptureClaim.ProcessEpoch e,Object o,long now,long end,boolean live){series=s;index=i;epoch=e;owner=o;issued=now;deadline=end;this.live=live;}
        boolean current(RealCaptureClaim.ProcessEpoch e,Object o,long now){return e==epoch&&o==owner&&now>=issued&&now<deadline&&series.current(now);}
    }
}