package com.aliothmoon.maafw.once;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Exact UI bracket and same-owned-frame candidate. Does not grant task input. */
final class OverviewFrameCapture {
    private final Object owner, session, epoch;
    private final int index;
    private final OverviewUiProtocol.Report ui;
    private final BingNavigationSnapshot.Ready uiBefore, uiAfter, imageBefore;
    private final long uiBeforeAt, lower, deadline;
    private final BooleanSupplier revoked;
    private final OverviewFramePreview preview;
    private boolean transportClaimed, prepared, sealAttempted;
    private Candidate candidate;
    private BingNavigationSnapshot.Ready imageAfter;
    private long sealedAt=-1, preparedAt=-1;
    private OverviewFrameCapture(int index,Object owner,Object session,Object epoch,OverviewUiProtocol.Report ui,
            BingNavigationSnapshot.Ready before,long beforeAt,BingNavigationSnapshot.Ready after,
            BingNavigationSnapshot.Ready imageBefore,long lower,long deadline,BooleanSupplier revoked,
            OverviewFramePreview preview) {
        this.index=index;this.owner=owner;this.session=session;this.epoch=epoch;this.ui=ui;uiBefore=before;uiBeforeAt=beforeAt;
        uiAfter=after;this.imageBefore=imageBefore;this.lower=lower;this.deadline=deadline;
        this.revoked=revoked;this.preview=preview;
    }
    static OverviewFrameCapture begin(int index,Object owner,Object session,Object epoch,String nonce,
            OverviewUiProtocol.Report ui,BingNavigationSnapshot.Ready before,long beforeAt,
            BingNavigationSnapshot.Ready after,BingNavigationSnapshot.Ready imageBefore,long lower,
            long deadline,BooleanSupplier revoked,OverviewFramePreview preview) {
        if(!OverviewScanScope.validFrame(index)||owner==null||session==null||epoch==null||ui==null||!ui.qualified()||before==null||after==null
                ||imageBefore==null||revoked==null||preview==null||deadline<=lower
                ||!NativeUiStructureProtocol.validNonce(nonce)||!nonce.equals(ui.source().nonce()))return null;
        var result=new OverviewFrameCapture(index,owner,session,epoch,ui,before,beforeAt,after,imageBefore,lower,deadline,revoked,preview);
        return result.current(lower)?result:null;
    }
    Object owner(){return owner;}
    int width(){return imageBefore.profile().shape().width();}
    int height(){return imageBefore.profile().shape().height();}
    int rotation(){return imageBefore.profile().shape().rotation();}
    long lower(){return lower;}long deadline(){return deadline;}
    OverviewFramePreview preview(){return preview;}
    OverviewUiProtocol.Report ui(){return ui;}
    synchronized boolean claimTransport(){boolean first=!transportClaimed&&!prepared&&!sealAttempted;transportClaimed=true;return first;}
    boolean current(long now){
        var source=ui.source();var shape=imageBefore.profile().shape();
        if(now<lower||now>=deadline||!NativeProgressPublication.frameFresh(now,lower)||stopped(revoked)
                ||!ui.qualified()||uiBefore.kind()!=BingNavigationSnapshot.Kind.TEMPLATE
                ||!uiBefore.sameSource(uiAfter)||!uiBefore.sameSource(imageBefore)
                ||uiBefore.observed()>uiBeforeAt||uiBeforeAt>source.started()
                ||!uiBefore.fresh(source.started())||source.finished()>uiAfter.lower()
                ||!uiAfter.fresh(now)||!NativeProgressPublication.frameFresh(now,source.started())
                ||!imageBefore.fresh(now)||uiAfter.observed()>imageBefore.lower()
                ||imageBefore.observed()>lower||source.displayId()!=0
                ||source.left()<0||source.top()<0||source.right()>shape.width()||source.bottom()>shape.height()
                ||source.right()<=source.left()||source.bottom()<=source.top()
                ||source.rotationBefore()!=-1&&source.rotationBefore()!=shape.rotation()
                ||source.rotationAfter()!=-1&&source.rotationAfter()!=shape.rotation())return false;
        for(var r:ui.overview().regions())if(r.coarse().right()>shape.width()||r.coarse().bottom()>shape.height())return false;
        return true;
    }
    synchronized Candidate prepare(Object recognizer,OnceBackend.Owner backendOwner,BackendPorts.FrameView actual,
            byte[] gray,List<FrameStructureEvidence.Line> rows,BooleanSupplier cancel,long observedAt) {
        boolean first=!prepared;prepared=true;byte[] scratch=null;
        try{
            if(!first||!transportClaimed||sealAttempted||recognizer==null||backendOwner==null||actual==null
                    ||gray==null||rows==null||stopped(cancel)||!current(observedAt))return null;
            long size=(long)width()*height();
            if(actual.width()!=width()||actual.height()!=height()||actual.size()!=size||gray.length!=size)return null;
            scratch=new byte[Math.min(gray.length,4096)];
            for(int offset=0;offset<gray.length;offset+=4096){
                if(stopped(cancel)||stopped(revoked))return null;
                int length=Math.min(scratch.length,gray.length-offset);actual.copyGrayRange(offset,scratch,0,length);
                for(int i=0;i<length;i++)if(scratch[i]!=gray[offset+i])return null;
            }
            if(!preview.matchesGray(gray))return null;
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(gray);
            var nativeRows=new java.util.ArrayList<com.aliothmoon.maafw.rewards.RewardRegionLocator.Row>();
            for(var row:rows){
                if(row==null)return null;
                nativeRows.add(new com.aliothmoon.maafw.rewards.RewardRegionLocator.Row(row.text(),
                        new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(row.x(),row.y(),
                                Math.addExact(row.x(),row.width()),Math.addExact(row.y(),row.height())),row.score(),
                        com.aliothmoon.maafw.rewards.RewardRegionLocator.Origin.STATIC_OCR));
            }
            OverviewFrameResult result=preview.withPixels((w,h,argb)->OverviewRegionRecognizer.observe(
                    index,ui.overview().regions(),gray,argb,w,h,nativeRows,()->stopped(cancel)||stopped(revoked)));
            if(result==null||stopped(cancel)||!current(observedAt)||!preview.matchesGray(gray))return null;
            candidate=new Candidate(this,recognizer,backendOwner,actual,observedAt,cancel,hash,result);
            return candidate;
        }catch(Exception invalid){return null;}
        finally{if(scratch!=null)Arrays.fill(scratch,(byte)0);}
    }
    private boolean afterCurrent(BingNavigationSnapshot.Ready after,long observedAt,long now){
        return after!=null&&observedAt>=lower&&observedAt<=now&&after.lower()>=observedAt&&after.fresh(now)
                &&uiBefore.sameSource(after)&&imageBefore.sameSource(after)&&current(now);
    }
    synchronized boolean seal(Candidate exact,Object recognizer,OnceBackend.Owner backendOwner,
            long entered,long returned,BingNavigationSnapshot.Ready after,long now){
        boolean first=!sealAttempted;sealAttempted=true;Candidate saved=candidate;
        try{
            if(!first||exact==null||exact!=saved||!exact.matches(this,recognizer,backendOwner)
                    ||entered<lower||returned<entered||exact.observedAt<returned
                    ||stopped(exact.cancel)||!afterCurrent(after,exact.observedAt,now))return false;
            imageAfter=after;preparedAt=exact.observedAt;sealedAt=now;return true;
        }finally{if(first){if(saved!=null)saved.discardIdentity();candidate=null;}}
    }
    boolean matchesOwners(Object owner,Object session,Object epoch){return this.owner==owner&&this.session==session&&this.epoch==epoch;}
    synchronized boolean publicationCurrent(long now){return sealedAt>=0&&now>=sealedAt&&preparedAt>=0&&afterCurrent(imageAfter,preparedAt,now);}
    private static boolean stopped(BooleanSupplier stop){try{return stop==null||stop.getAsBoolean();}catch(RuntimeException unknown){return true;}}
    static final class Candidate {
        private OverviewFrameCapture capture;private Object recognizer;private OnceBackend.Owner owner;
        private Object frameIdentity;private final long observedAt;private BooleanSupplier cancel;
        private final byte[] graySha256;private final OverviewFrameResult result;
        private Candidate(OverviewFrameCapture capture,Object recognizer,OnceBackend.Owner owner,Object frame,
                long observedAt,BooleanSupplier cancel,byte[] hash,OverviewFrameResult result){
            this.capture=capture;this.recognizer=recognizer;this.owner=owner;frameIdentity=frame;
            this.observedAt=observedAt;this.cancel=cancel;graySha256=hash.clone();this.result=result;
        }
        synchronized boolean matches(OverviewFrameCapture capture,Object recognizer,OnceBackend.Owner owner){
            return this.capture!=null&&this.capture==capture&&this.recognizer==recognizer&&this.owner==owner&&frameIdentity!=null;
        }
        private synchronized void discardIdentity(){capture=null;recognizer=null;owner=null;frameIdentity=null;cancel=null;}
        byte[] graySha256(){return graySha256.clone();}
        OverviewFrameResult result(){return result;}
    }
}
