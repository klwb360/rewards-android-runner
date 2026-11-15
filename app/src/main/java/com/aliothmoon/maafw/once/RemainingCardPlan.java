package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.DailyCheckInRegion;
import com.aliothmoon.maafw.rewards.RewardRegionGate;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Origin;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Row;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Pixel-planned bounded OCR for an exact same-run crop. No device input capability. */
final class RemainingCardPlan {
    enum Role { WEEKLY_TITLE, NUMBER, DAY1, DAY2, DAY3, DAY4, DAY5, DAY6, DAY7, ACTIVITY_TITLE, POINTS }
    record Outcome(String status,String reason,int hypotheticalClicks,int realExecutions,int x,int y,
            int day,int displayedPoints,int ocrQueries,double confidence,Box boundary,Box target) { }
    static final class Plan {
        private final RemainingCardChoice choice;
        private final int width,height;
        private final String hash,reason;
        private final Box boundary,indicator;
        private final List<Box> rois;
        private final List<Role> roles;
        private final RewardRegionLocator.Geometry activityGeometry;
        private final boolean completedIndicator;
        private final BooleanSupplier provenance;
        private final AtomicBoolean claimed=new AtomicBoolean(),finished=new AtomicBoolean();
        private Plan(RemainingCardChoice choice,int width,int height,String hash,String reason,Box boundary,Box indicator,
                List<Box> rois,List<Role> roles,RewardRegionLocator.Geometry geometry,boolean complete,BooleanSupplier provenance){
            this.choice=choice;this.width=width;this.height=height;this.hash=hash;this.reason=reason;this.boundary=boundary;
            this.indicator=indicator;this.rois=List.copyOf(rois);this.roles=List.copyOf(roles);activityGeometry=geometry;
            completedIndicator=complete;this.provenance=provenance;
        }
        boolean admitted(){return reason.equals("READY")&&boundary!=null&&!rois.isEmpty()&&provenance.getAsBoolean();}
        String reason(){return reason;}int width(){return width;}int height(){return height;}
        List<Box> rois(){return rois;}List<Role> roles(){return roles;}Box boundary(){return boundary;}
        RemainingCardChoice choice(){return choice;}
        RewardRegionGate.Source source(){return admitted()?RewardRegionGate.Source.REAL_DISPLAY_NATIVE:null;}
        boolean claimRecognition(){return admitted()&&claimed.compareAndSet(false,true);}
        boolean matchesGray8(byte[] gray,int w,int h,BooleanSupplier stop){return admitted()&&!stopped(stop)
                &&w==width&&h==height&&gray!=null&&gray.length==(long)w*h&&hash.equals(CardNativeMockReceipt.digest(gray))
                &&!stopped(stop)&&provenance.getAsBoolean();}
        Outcome hold(String why,int count){return new Outcome("HOLD",why,0,0,-1,-1,-1,-1,count,-1,boundary,null);}
        Outcome finish(List<List<FrameStructureEvidence.Line>> batches,BooleanSupplier stop){
            if(!finished.compareAndSet(false,true))return hold("ALREADY_CONSUMED",0);
            int count=batches==null?0:batches.size();
            if(stopped(stop)||!provenance.getAsBoolean())return hold("CANCELED",count);
            if(!admitted())return hold(reason,count);
            if(batches==null||batches.size()!=rois.size())return hold("MISSING_BATCHES",count);
            List<Row> rows=new ArrayList<>();double minimum=1;
            for(int i=0;i<rois.size();i++){
                if(stopped(stop)||!provenance.getAsBoolean())return hold("CANCELED",count);
                var batch=batches.get(i);Role role=roles.get(i);Box roi=rois.get(i);
                if(batch==null)return hold("ROI_ROWS_UNAVAILABLE",count);
                int maximum=role==Role.ACTIVITY_TITLE?3:1;
                if(batch.isEmpty()||batch.size()>maximum)return hold("ROI_ROW_COUNT",count);
                for(var line:batch){
                    if(!valid(line,width,height)||line.score()<.85)return hold("ROI_ROW_SHAPE_OR_CONFIDENCE",count);
                    Box b=new Box(line.x(),line.y(),line.x()+line.width(),line.y()+line.height());
                    if(!strict(roi,b))return hold(roi.contains(b)?"ROW_TOUCHES_ROI":"ROW_OUTSIDE_OWN_ROI",count);
                    if(!token(role,line.text()))return hold("ROI_TOKEN_CLASS",count);
                    if(!completedIndicator&&role==Role.ACTIVITY_TITLE&&conflict(line.text()))return hold("STATE_CONFLICT",count);
                    for(var prior:rows)if(prior.box().overlaps(b))return hold("ROW_OVERLAP",count);
                    rows.add(new Row(line.text(),b,line.score(),Origin.STATIC_OCR));minimum=Math.min(minimum,line.score());
                }
            }
            if(stopped(stop)||!provenance.getAsBoolean())return hold("CANCELED",count);
            if(choice.weekly()){
                var line=batches.get(1).get(0);Box number=box(line);
                return new Outcome("VISUAL_PREVIEW_ONLY","READ_ONLY_WEEK_PROGRESS",0,0,
                        (int)number.centerX(),(int)number.centerY(),-1,Integer.parseInt(line.text()),count,minimum,boundary,number);
            }
            var points=batches.get(1).get(0);
            if(completedIndicator){
                var skipped=RewardRegionLocator.observeCompletedItems(activityGeometry,rows);
                if(skipped.size()!=1||!skipped.get(0).cardBox().equals(boundary))return hold("NUMBER_TICK_ASSOCIATION",count);
                if(stopped(stop)||!provenance.getAsBoolean())return hold("CANCELED",count);
                return new Outcome("ITEM_COMPLETE_SKIP","NUMBER_AND_TICK",0,0,-1,-1,-1,
                        skipped.get(0).displayedNumber(),count,minimum,boundary,null);
            }
            Integer value=plus(points.text());if(value==null)return hold("POINTS_NOT_PLUS",count);
            if(stopped(stop)||!provenance.getAsBoolean())return hold("CANCELED",count);
            Box target=box(points);
            return new Outcome("PREVIEW_ONLY","ONE_FAKE_RECEIPT",1,0,(int)target.centerX(),(int)target.centerY(),-1,
                    value,count,minimum,boundary,target);
        }
    }
    static Plan prepare(byte[] gray,int[] argb,int width,int height,CardNativeMockReceipt receipt,Object owner,
            RemainingCardChoice choice,BooleanSupplier stop){
        BooleanSupplier proof=()->receipt!=null&&receipt.claimedBy(owner);
        if(stopped(stop))return denied(choice,width,height,"CANCELED",null,proof);
        if(choice==null||owner==null||receipt==null||receipt.width()!=width||receipt.height()!=height
                ||!receipt.claimedBy(owner)||!receipt.claimPlan(owner))return denied(choice,width,height,"CAPTURE_RECEIPT_UNAVAILABLE",null,proof);
        if(width<32||height<32||width>4096||height>4096||(long)width*height>LocalCardMockPlan.MAX_PIXELS
                ||gray==null||argb==null||gray.length!=(long)width*height||argb.length!=gray.length)
            return denied(choice,width,height,"INVALID_LOCAL_CROP",null,proof);
        for(int i=0;i<argb.length;i++){
            if((i&4095)==0&&(stopped(stop)||!proof.getAsBoolean()))return denied(choice,width,height,"CANCELED",null,proof);
            int p=argb[i],g=(77*((p>>>16)&255)+150*((p>>>8)&255)+29*(p&255)+128)>>>8;
            if((p>>>24)!=255||g!=(gray[i]&255))return denied(choice,width,height,"COLOR_GRAY_MISMATCH",null,proof);
        }
        var key=new RewardRegionGate.Key(RewardRegionGate.Source.REAL_DISPLAY_NATIVE,"remaining-card-v1",1,choice.key());
        if(choice.weekly())return weekly(gray,argb,width,height,choice,key,proof,stop);
        var geometry=RewardRegionLocator.observeVisibleMoreGray8(key,gray,width,height,stop);
        if(geometry.status()!=RewardRegionLocator.Status.OBSERVED||geometry.cards().size()!=1)
            return denied(choice,width,height,"NO_UNIQUE_CARD",null,proof);
        var card=geometry.cards().get(0);Box b=card.box();
        if(!card.fullyVisible()||!strict(new Box(0,0,width,height),b))return denied(choice,width,height,"CARD_PARTIAL",null,proof);
        boolean complete=card.badges().isEmpty()&&card.tickMarks().size()==1;
        Box indicator,points;
        if(complete){
            var tick=card.tickMarks().get(0);indicator=tick.diskBox();
            if(!b.contains(indicator)||indicator.left()<b.left()+.70*b.width())return denied(choice,width,height,"TICK_POSITION",b,proof);
            int pad=Math.max(3,(int)Math.ceil(.08*indicator.height()));
            points=new Box(Math.max(b.left()+2,indicator.left()-(int)Math.ceil(1.8*indicator.width())),
                    Math.max(b.top()+2,indicator.top()-pad),indicator.left()-2,Math.min(b.bottom()-2,indicator.bottom()+pad));
        }else{
            if(!card.tickMarks().isEmpty()||card.badges().size()!=1)return denied(choice,width,height,"BADGE_OR_TICK_AMBIGUOUS",b,proof);
            indicator=card.badges().get(0).box();
            double rx=(indicator.left()-b.left())/(double)b.width(),rw=indicator.width()/(double)b.width();
            double rh=indicator.height()/(double)b.height(),ry=(indicator.centerY()-b.top())/b.height();
            if(!b.contains(indicator)||rx<.70||rw<.16||rw>.26||rh<.24||rh>.51||ry<.38||ry>.62)
                return denied(choice,width,height,"BADGE_GEOMETRY",b,proof);
            points=new Box(indicator.left()+2,indicator.top()+2,indicator.right()-2,indicator.bottom()-2);
        }
        Box title=titleRoi(gray,width,b,complete?points:indicator,complete,stop);
        if(title==null)return denied(choice,width,height,"TITLE_PIXEL_GEOMETRY",b,proof);
        List<Box> rois=List.of(title,points);
        for(Box roi:rois)if(roi.width()<4||roi.height()<4||!strict(b,roi))return denied(choice,width,height,"INVALID_ROI_GEOMETRY",b,proof);
        if(title.overlaps(points)||stopped(stop)||!proof.getAsBoolean())return denied(choice,width,height,"CANCELED_OR_ROI_OVERLAP",b,proof);
        return new Plan(choice,width,height,CardNativeMockReceipt.digest(gray),"READY",b,indicator,rois,
                List.of(Role.ACTIVITY_TITLE,Role.POINTS),geometry,complete,proof);
    }
    private static Plan weekly(byte[] gray,int[] argb,int w,int h,RemainingCardChoice choice,
            RewardRegionGate.Key key,BooleanSupplier proof,BooleanSupplier stop){
        var geometry=DailyCheckInRegion.observeGray8(key,gray,w,h,stop);
        if(geometry.status()!=DailyCheckInRegion.Status.OBSERVED||geometry.surfaces().size()!=1||!geometry.surfaces().get(0).complete())
            return denied(choice,w,h,"NO_UNIQUE_WEEK_PANEL",null,proof);
        Box panel=geometry.surfaces().get(0).box(),ring=null;
        for(Box observed:geometry.rings())if(panel.overlaps(observed)){
            if(!panel.contains(observed)||ring!=null)return denied(choice,w,h,"AMBIGUOUS_RING",panel,proof);ring=observed;
        }
        if(ring==null)return denied(choice,w,h,"NO_CURRENT_RING",panel,proof);
        for(var tick:geometry.ticks())if(ring.overlaps(tick.disk()))return denied(choice,w,h,"CURRENT_RING_HAS_TICK",panel,proof);
        if(!yellow(argb,w,ring,stop))return denied(choice,w,h,"RING_NOT_YELLOW",panel,proof);
        var columns=CheckInCoinColumns.locate(argb,w,h,panel,ring,stop);
        if(!columns.ready())return denied(choice,w,h,columns.status(),panel,proof);
        Box title=new Box(panel.left(),panel.top(),panel.left()+(int)(.35*panel.width()),panel.top()+(int)(.40*panel.height()));
        if(title.overlaps(ring))return denied(choice,w,h,"TITLE_RING_OVERLAP",panel,proof);
        int ix=Math.max(2,(int)Math.ceil(.20*ring.width())),iy=Math.max(2,(int)Math.ceil(.20*ring.height()));
        Box number=new Box(ring.left()+ix,ring.top()+iy,ring.right()-ix,ring.bottom()-iy);
        List<Box> rois=new ArrayList<>();rois.add(title);rois.add(number);rois.addAll(columns.dayRois());
        List<Role> roles=List.of(Role.WEEKLY_TITLE,Role.NUMBER,Role.DAY1,Role.DAY2,Role.DAY3,Role.DAY4,Role.DAY5,Role.DAY6,Role.DAY7);
        for(Box roi:rois)if(roi.width()<4||roi.height()<4||!panel.contains(roi))return denied(choice,w,h,"INVALID_ROI_GEOMETRY",panel,proof);
        if(stopped(stop)||!proof.getAsBoolean())return denied(choice,w,h,"CANCELED",panel,proof);
        return new Plan(choice,w,h,CardNativeMockReceipt.digest(gray),"READY",panel,ring,rois,roles,null,false,proof);
    }
    private static Box titleRoi(byte[] gray,int width,Box card,Box indicator,boolean completed,BooleanSupplier stop){
        int left=card.left()+(int)Math.ceil((completed?.02:.18)*card.width());
        int right=indicator.left()-Math.max(4,(int)Math.ceil(.015*card.width()));
        int top=card.top()+Math.max(2,(int)Math.ceil(.04*card.height()));
        int bottom=card.top()+(int)Math.floor((completed?.88:.56)*card.height());
        if(right-left<20||bottom-top<10)return null;
        int minX=right,minY=bottom,maxX=left,maxY=top,pixels=0;
        for(int y=top;y<bottom;y++){
            if(stopped(stop))return null;
            for(int x=left;x<right;x++)if((gray[y*width+x]&255)<100){
                minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x+1);maxY=Math.max(maxY,y+1);pixels++;
            }
        }
        if(pixels<20||maxX-minX<8||maxY-minY<8)return null;
        int px=Math.max(6,(int)Math.ceil(.02*card.width())),py=Math.max(5,(int)Math.ceil(.025*card.height()));
        return new Box(Math.max(card.left()+2,minX-px),Math.max(card.top()+2,minY-py),
                Math.min(indicator.left()-2,maxX+px),Math.min(card.bottom()-2,maxY+py));
    }
    private static Plan denied(RemainingCardChoice choice,int w,int h,String why,Box boundary,BooleanSupplier proof){
        return new Plan(choice,w,h,"",why,boundary,null,List.of(),List.of(),null,false,proof);
    }
    static boolean valid(FrameStructureEvidence.Line row,int w,int h){return row!=null&&row.text()!=null&&row.text().length()<=128
            &&Double.isFinite(row.score())&&row.score()>=0&&row.score()<=1&&row.x()>=0&&row.y()>=0
            &&row.width()>0&&row.height()>0&&(long)row.x()+row.width()<=w&&(long)row.y()+row.height()<=h;}
    static boolean token(Role role,String value){
        if(value==null||value.length()>128)return false;
        if(role==Role.WEEKLY_TITLE)return value.equals("每日活动");
        if(role==Role.ACTIVITY_TITLE)return value.codePointCount(0,value.length())>=2&&value.codePoints().anyMatch(Character::isLetter);
        if(role==Role.NUMBER)return number(value)!=null;
        if(role==Role.POINTS)return number(value)!=null||plus(value)!=null;
        int day=role.ordinal()-1;return value.equals("Day "+day)||value.equals("Day"+day)||value.equals("第"+day+"天");
    }
    private static boolean conflict(String text){
        String value=text.toLowerCase(java.util.Locale.ROOT);
        return value.contains("已完成")||value.contains("已领取")||value.contains("明天")||value.contains("锁定")||value.contains("登录")
                ||value.contains("completed")||value.contains("claimed")||value.contains("tomorrow")||value.contains("locked")||value.contains("sign in");
    }
    private static Integer number(String text){return text.matches("[0-9]{1,4}")&&Integer.parseInt(text)>0?Integer.parseInt(text):null;}
    private static Integer plus(String text){var m=java.util.regex.Pattern.compile("\\+[ \\u00a0\\u2009\\u202f]*([0-9]{1,4})").matcher(text);
        return m.matches()&&Integer.parseInt(m.group(1))>0?Integer.parseInt(m.group(1)):null;}
    private static Box box(FrameStructureEvidence.Line row){return new Box(row.x(),row.y(),row.x()+row.width(),row.y()+row.height());}
    private static boolean strict(Box outer,Box inner){return inner.left()>outer.left()&&inner.top()>outer.top()&&inner.right()<outer.right()&&inner.bottom()<outer.bottom();}
    private static boolean stopped(BooleanSupplier stop){return stop!=null&&stop.getAsBoolean();}
    private static boolean yellow(int[] argb,int width,Box ring,BooleanSupplier stop){
        int samples=0,yellow=0;double cx=ring.centerX(),cy=ring.centerY(),rx=ring.width()/2.0,ry=ring.height()/2.0;
        for(int y=ring.top();y<ring.bottom();y++){
            if(stopped(stop))return false;
            for(int x=ring.left();x<ring.right();x++){
                double dx=(x+.5-cx)/rx,dy=(y+.5-cy)/ry,d=dx*dx+dy*dy;if(d<.70||d>1.05)continue;
                int c=argb[y*width+x],r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
                if(((77*r+150*g+29*b+128)>>>8)>=205)continue;samples++;
                if((c>>>24)==255&&r>=200&&g>=100&&g<=225&&b<=170&&r-g>=20&&g-b>=25)yellow++;
            }
        }
        return samples>=ring.width()/2&&yellow*100L>=samples*80L;
    }
    private RemainingCardPlan(){}
}
