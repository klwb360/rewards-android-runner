package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.DailyCheckInRegion;
import com.aliothmoon.maafw.rewards.RewardRegionGate;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Origin;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Row;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Historical local crop replay only. Geometry, OCR and a fake receipt never grant device input. */
final class LocalCardMockPlan {
    static final int QUERIES=9,MAX_PIXELS=2_097_152;
    private static final double MIN_CONFIDENCE=.85;
    record Outcome(String status,String reason,int hypotheticalClicks,int realExecutions,
            int x,int y,int day,int displayedPoints,int ocrQueries,double confidence) { }

    static final class Plan {
        private final DailyCheckInRegion.Geometry geometry;
        private final Box panel,ring;
        private final List<Box> rois;
        private final String reason;
        private final int width,height;
        private final AtomicBoolean recognitionClaimed=new AtomicBoolean(),finished=new AtomicBoolean();
        private Plan(DailyCheckInRegion.Geometry geometry,Box panel,Box ring,List<Box> rois,
                String reason,int width,int height){
            this.geometry=geometry;this.panel=panel;this.ring=ring;this.rois=List.copyOf(rois);
            this.reason=reason;this.width=width;this.height=height;
        }
        boolean admitted(){return reason.equals("READY")&&geometry!=null&&rois.size()==QUERIES;}
        String reason(){return reason;}
        List<Box> rois(){return rois;}
        int width(){return width;}
        int height(){return height;}
        boolean claimRecognition(){return admitted()&&recognitionClaimed.compareAndSet(false,true);}
        boolean matchesGray8(byte[] gray,int w,int h,BooleanSupplier canceled){
            return admitted()&&!stopped(canceled)&&w==width&&h==height&&gray!=null
                    &&gray.length==(long)w*h&&geometry.pixelHash().equals(hash(gray))&&!stopped(canceled);
        }
        /** Each batch contains unmodified native boxes in this crop's coordinate space. */
        Outcome finish(List<List<FrameStructureEvidence.Line>> batches,BooleanSupplier canceled){
            if(!finished.compareAndSet(false,true))return hold("ALREADY_CONSUMED",0);
            int count=batches==null?0:batches.size();
            if(stopped(canceled))return hold("CANCELED",count);
            if(!admitted())return hold(reason,count);
            if(batches==null||batches.size()!=QUERIES)return hold("MISSING_BATCHES",count);
            List<Row> rows=new ArrayList<>();double minimum=1;
            for(int i=0;i<QUERIES;i++){
                if(stopped(canceled))return hold("CANCELED",count);
                var batch=batches.get(i);Box roi=rois.get(i);
                if(batch==null||batch.size()!=1)return hold("ROI_ROW_COUNT",count);
                var line=batch.get(0);
                if(line==null||line.text()==null||line.text().length()>128||!Double.isFinite(line.score())
                        ||line.score()<MIN_CONFIDENCE||line.score()>1||line.width()<=0||line.height()<=0
                        ||line.x()<0||line.y()<0||(long)line.x()+line.width()>width||(long)line.y()+line.height()>height)
                    return hold("ROI_ROW_SHAPE_OR_CONFIDENCE",count);
                Box b=new Box(line.x(),line.y(),line.x()+line.width(),line.y()+line.height());
                if(!roi.contains(b))return hold("ROW_OUTSIDE_OWN_ROI",count);
                // A recognition-only whole-search fallback does not localize the title/digit/label.
                if(b.left()<=roi.left()||b.top()<=roi.top()||b.right()>=roi.right()||b.bottom()>=roi.bottom())
                    return hold("ROW_TOUCHES_ROI",count);
                if(i==0&&!line.text().equals("签入"))return hold("TITLE_NOT_EXACT",count);
                if(i==1&&!positive(line.text()))return hold("NUMBER_NOT_PLAIN",count);
                if(i>=2&&!dayText(line.text(),i-1))return hold("WRONG_DAY",count);
                minimum=Math.min(minimum,line.score());
                rows.add(new Row(line.text(),b,line.score(),Origin.STATIC_OCR));
            }
            if(stopped(canceled))return hold("CANCELED",count);
            var text=new DailyCheckInRegion.TextEvidence(geometry.key(),rows);
            var associated=DailyCheckInRegion.associate(geometry,text);
            if(associated.status()==DailyCheckInRegion.Status.CANDIDATE){
                var session=new DailyCheckInRegion.Session(associated);
                if(stopped(canceled)){session.cancel();return hold("CANCELED",count);}
                var receipt=session.previewOnce();
                if(receipt.outcome()!=DailyCheckInRegion.Outcome.PREVIEW_ONLY||receipt.hypotheticalClicks()!=1
                        ||receipt.realExecutions()!=0||receipt.candidate()==null)return hold("FAKE_RECEIPT_REJECTED",count);
                var candidate=receipt.candidate();
                if(stopped(canceled)){session.cancel();return hold("CANCELED",count);}
                return new Outcome("PREVIEW_ONLY","ONE_FAKE_RECEIPT",1,0,(int)Math.floor(candidate.cropX()),
                        (int)Math.floor(candidate.cropY()),candidate.day(),candidate.displayedPoints(),count,minimum);
            }
            // This fallback carries no Day selection or fake click count. A diagram may show its point.
            var visual=DailyCheckInRegion.previewVisualTarget(geometry,text);
            if(visual.status()==DailyCheckInRegion.Status.CANDIDATE&&!stopped(canceled))
                return new Outcome("VISUAL_PREVIEW_ONLY",associated.status().name(),0,0,
                        (int)Math.floor(visual.cropX()),(int)Math.floor(visual.cropY()),-1,visual.displayedPoints(),count,minimum);
            return hold("ASSOCIATION_"+associated.status().name(),count);
        }
    }

    static Plan prepare(byte[] gray,int[] argb,int width,int height,BooleanSupplier canceled){
        if(stopped(canceled))return denied("CANCELED",width,height);
        if(width<32||height<32||width>4096||height>4096||(long)width*height>MAX_PIXELS||gray==null
                ||gray.length!=(long)width*height||argb==null||argb.length!=gray.length)
            return denied("INVALID_LOCAL_CROP",width,height);
        for(int i=0;i<gray.length;i++){
            if((i&4095)==0&&stopped(canceled))return denied("CANCELED",width,height);
            int p=argb[i],g=(77*((p>>>16)&255)+150*((p>>>8)&255)+29*(p&255)+128)>>>8;
            if((p>>>24)!=255||g!=(gray[i]&255))return denied("COLOR_GRAY_MISMATCH",width,height);
        }
        var key=new RewardRegionGate.Key(RewardRegionGate.Source.USER_STATIC_MATERIAL,"local-card-mock-v1",1,"CHECK_IN");
        var geometry=DailyCheckInRegion.observeGray8(key,gray,width,height,canceled);
        if(geometry.status()!=DailyCheckInRegion.Status.OBSERVED)return denied("GEOMETRY_"+geometry.status().name(),width,height);
        if(geometry.surfaces().size()!=1||!geometry.surfaces().get(0).complete())return denied("NO_UNIQUE_COMPLETE_PANEL",width,height);
        Box panel=geometry.surfaces().get(0).box(),ring=null;
        for(Box candidate:geometry.rings())if(panel.overlaps(candidate)){
            if(!panel.contains(candidate)||ring!=null)return denied("AMBIGUOUS_RING",width,height);ring=candidate;
        }
        if(ring==null)return denied("NO_CURRENT_RING",width,height);
        for(var tick:geometry.ticks())if(ring.overlaps(tick.disk()))return denied("CURRENT_RING_HAS_TICK",width,height);
        if(!yellowRing(argb,width,ring,canceled))return denied(stopped(canceled)?"CANCELED":"RING_NOT_YELLOW",width,height);
        Box title=new Box(panel.left(),panel.top(),panel.left()+(int)(.25*panel.width()),panel.top()+(int)(.40*panel.height()));
        if(title.overlaps(ring))return denied("TITLE_RING_OVERLAP",width,height);
        int ix=Math.max(2,(int)Math.ceil(.20*ring.width())),iy=Math.max(2,(int)Math.ceil(.20*ring.height()));
        Box number=new Box(ring.left()+ix,ring.top()+iy,ring.right()-ix,ring.bottom()-iy);
        List<Box> rois=new ArrayList<>();rois.add(title);rois.add(number);
        Box band=new Box(panel.left(),panel.top()+(int)Math.ceil(.72*panel.height()),panel.right(),panel.bottom());
        int dy=(int)Math.ceil(.10*band.height());
        for(int day=0;day<7;day++){
            int left=band.left()+band.width()*day/7,right=band.left()+band.width()*(day+1)/7;
            int dx=(int)Math.ceil(.15*(right-left));
            rois.add(new Box(Math.max(band.left(),left-dx),Math.max(panel.top(),band.top()-dy),
                    Math.min(band.right(),right+dx),band.bottom()));
        }
        for(Box roi:rois)if(roi.width()<4||roi.height()<4||!panel.contains(roi))return denied("INVALID_ROI_GEOMETRY",width,height);
        if(stopped(canceled))return denied("CANCELED",width,height);
        return new Plan(geometry,panel,ring,rois,"READY",width,height);
    }
    private static Plan denied(String reason,int width,int height){return new Plan(null,null,null,List.of(),reason,width,height);}
    private static Outcome hold(String reason,int count){return new Outcome("HOLD",reason,0,0,-1,-1,-1,-1,count,-1);}
    private static boolean stopped(BooleanSupplier canceled){return canceled!=null&&canceled.getAsBoolean();}
    private static boolean positive(String text){
        if(!text.matches("[0-9]{1,4}"))return false;return Integer.parseInt(text)>0;
    }
    private static boolean dayText(String text,int day){return text.equals("Day "+day)||text.equals("Day"+day)||text.equals("第"+day+"天");}
    private static String hash(byte[] pixels){
        byte[] digest;try{digest=MessageDigest.getInstance("SHA-256").digest(pixels);}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        try{StringBuilder value=new StringBuilder(64);for(byte b:digest){value.append(Character.forDigit((b>>>4)&15,16));value.append(Character.forDigit(b&15,16));}return value.toString();}
        finally{Arrays.fill(digest,(byte)0);}
    }
    /** Identical annular color thresholds to the existing overview recognizer. */
    private static boolean yellowRing(int[] argb,int width,Box ring,BooleanSupplier canceled){
        int samples=0,yellow=0;double cx=ring.centerX(),cy=ring.centerY(),rx=ring.width()/2.0,ry=ring.height()/2.0;
        for(int y=ring.top();y<ring.bottom();y++){
            if(stopped(canceled))return false;
            for(int x=ring.left();x<ring.right();x++){
                double dx=(x+.5-cx)/rx,dy=(y+.5-cy)/ry,d=dx*dx+dy*dy;if(d<.70||d>1.05)continue;
                int c=argb[y*width+x],a=(c>>>24)&255,red=(c>>>16)&255,green=(c>>>8)&255,blue=c&255;
                if(((77*red+150*green+29*blue+128)>>>8)>=205)continue;samples++;
                if(a==255&&red>=200&&green>=100&&green<=225&&blue<=170&&red-green>=20&&green-blue>=25)yellow++;
            }
        }
        return samples>=ring.width()/2&&yellow*100L>=samples*80L;
    }
    private LocalCardMockPlan(){}
}
