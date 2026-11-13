package com.aliothmoon.maafw.rewards;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.aliothmoon.maafw.rewards.RewardRegionGate.*;

/**
 * Bounded GRAY8 static-material observer. Four-neighbour filled-surface components are visual
 * boundaries, not Android UI nodes. No runtime wiring, capture, OCR engine, input or account API.
 * Thresholds reproduce the independently measured four uploaded crops, not a general UI model.
 */
public final class RewardRegionLocator {
    private RewardRegionLocator() {}
    public enum BadgeTone { DARK, PALE }
    public enum Origin { STATIC_OCR, MANUAL_ANNOTATION, SYNTHETIC }
    public enum Status { OBSERVED, NO_CARDS, CANCELED, INVALID_INPUT, TOO_MANY_COMPONENTS,
        SKIP_COMPLETE, UNKNOWN_PROGRESS, BAD_SECTION, INCOMPLETE_CARD, AMBIGUOUS_BADGE,
        BAD_OCR, AMBIGUOUS_TOKEN, MISSING_TITLE, COMPLETION_MARKER, UNRESOLVED_STATE, SKIPPED_VISIBLE_ITEMS, CANDIDATES }
    public record Box(int left,int top,int right,int bottom) {
        public int width() { return right-left; }
        public int height() { return bottom-top; }
        public double centerX() { return ((double)left+right)/2; }
        public double centerY() { return ((double)top+bottom)/2; }
        public boolean contains(Box b) {
            return b!=null && b.left>=left && b.top>=top && b.right<=right && b.bottom<=bottom;
        }
        public boolean overlaps(Box b) {
            return left<b.right && right>b.left && top<b.bottom && bottom>b.top;
        }
        public double[] normalized(int width,int height) {
            return new double[]{(double)left/width,(double)top/height,(double)right/width,(double)bottom/height};
        }
    }
    public record Badge(Box box,BadgeTone tone) {}
    public record TickMark(Box diskBox,Box glyphBox,double referenceSimilarity) {}
    public record Card(Box box,boolean fullyVisible,List<Badge> badges,List<TickMark> tickMarks) {
        public Card { badges=List.copyOf(badges);tickMarks=List.copyOf(tickMarks); }
    }
    public record SkippedItem(String visualCardId,Box cardBox,int displayedNumber,Box numberBox,
                              TickMark tick,Origin numberOrigin,String rule) {
        public boolean credited(){return false;}
    }
    public static final class Geometry {
        private final Key key;
        private final int width,height;
        private final Status status;
        private final List<Card> cards;
        private Geometry(Key key,int width,int height,Status status,List<Card> cards) {
            this.key=key;this.width=width;this.height=height;this.status=status;this.cards=List.copyOf(cards);
        }
        public Key key(){return key;} public int width(){return width;} public int height(){return height;}
        public Status status(){return status;} public List<Card> cards(){return cards;}
    }
    private record Component(Box box,int area) {
        double fill(){return (double)area/(box.width()*box.height());}
    }
    private static boolean valid(Box b,int w,int h) {
        return b!=null && b.left>=0 && b.top>=0 && b.left<w && b.top<h && b.right<=w && b.bottom<=h && b.right>b.left && b.bottom>b.top;
    }
    private static boolean matches(int value,int mode) {
        return switch(mode){case 0,4 -> value>=252;case 1,3 -> value<160;default -> value>=241 && value<=250;};
    }
    private static List<Component> components(byte[] gray,int w,int h,Box roi,int mode,BooleanSupplier cancel) {
        int rw=roi.width(),rh=roi.height(),area=rw*rh;
        boolean[] seen=new boolean[area]; int[] queue=new int[area];
        List<Component> out=new ArrayList<>();
        for(int y=0;y<rh;y++) for(int x=0;x<rw;x++) {
            int seed=y*rw+x;
            if((seed&255)==0 && cancel.getAsBoolean())return null;
            if(seen[seed] || !matches(gray[(y+roi.top)*w+x+roi.left]&255,mode))continue;
            int head=0,tail=0,minX=x,maxX=x,minY=y,maxY=y;queue[tail++]=seed;seen[seed]=true;
            while(head<tail) {
                int at=queue[head++],px=at%rw,py=at/rw;
                if((head&1023)==0 && cancel.getAsBoolean())return null;
                minX=Math.min(minX,px);maxX=Math.max(maxX,px);minY=Math.min(minY,py);maxY=Math.max(maxY,py);
                for(int direction=0;direction<4;direction++) {
                    int nx=px+(direction==0?-1:direction==1?1:0);
                    int ny=py+(direction==2?-1:direction==3?1:0);
                    if(nx<0||nx>=rw||ny<0||ny>=rh)continue;
                    int next=ny*rw+nx;
                    if(!seen[next] && matches(gray[(ny+roi.top)*w+nx+roi.left]&255,mode)){
                        seen[next]=true;queue[tail++]=next;
                    }
                }
            }
            Box b=new Box(minX+roi.left,minY+roi.top,maxX+roi.left+1,maxY+roi.top+1);
            boolean eligible=mode==0?b.width()>.70*w && b.height()>.10*h:
                mode==4?b.width()>.70*w && b.height()>=.10*w:
                mode==3?b.width()>.025*w&&b.width()<.09*w&&b.height()>.025*w&&b.height()<.09*w
                    &&b.width()/(double)b.height()>=.75&&b.width()/(double)b.height()<=1.3:
                b.width()>.10*w && b.width()<.30*w && b.height()>.03*h && b.height()<.40*h;
            if(eligible) {
                Component c=new Component(b,tail);
                if(c.fill()>((mode==0||mode==4)?.65:.60))out.add(c);
                if(out.size()>32)return out;
            }
        }
        return out;
    }
    // A literal shape reference derived from the fifth supplied crop, not trained weights.
    // A match is only local visual evidence; numeral ownership and the user's rule are separate.
    private static final String[] TICK_REFERENCE={
        "00000000011","00000000111","00000001110","10000011100",
        "11000111000","11101110000","01111100000","00111000000"};
    private static TickMark tickShape(byte[] gray,int w,Box disk,BooleanSupplier cancel){
        int rw=disk.width(),rh=disk.height();boolean[] seen=new boolean[rw*rh];int[] queue=new int[rw*rh];
        Box glyph=null;
        for(int y=0;y<rh;y++)for(int x=0;x<rw;x++){
            int seed=y*rw+x;if(seen[seed]||(gray[(y+disk.top)*w+x+disk.left]&255)<160)continue;
            int head=0,tail=0,minX=x,maxX=x,minY=y,maxY=y;boolean edge=false;
            queue[tail++]=seed;seen[seed]=true;
            while(head<tail){
                if((head&255)==0&&cancel.getAsBoolean())return null;
                int p=queue[head++],px=p%rw,py=p/rw;
                minX=Math.min(minX,px);maxX=Math.max(maxX,px);minY=Math.min(minY,py);maxY=Math.max(maxY,py);
                edge|=px==0||py==0||px==rw-1||py==rh-1;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                    if(dx==0&&dy==0)continue;int nx=px+dx,ny=py+dy;
                    if(nx<0||nx>=rw||ny<0||ny>=rh)continue;int at=ny*rw+nx;
                    if(!seen[at]&&(gray[(ny+disk.top)*w+nx+disk.left]&255)>=160){
                        seen[at]=true;queue[tail++]=at;
                    }
                }
            }
            if(!edge&&tail>=5){
                if(glyph!=null)return null;
                glyph=new Box(minX+disk.left,minY+disk.top,maxX+disk.left+1,maxY+disk.top+1);
            }
        }
        if(glyph==null||glyph.width()<.25*rw||glyph.width()>.65*rw||glyph.height()<.20*rh||glyph.height()>.60*rh)return null;
        int intersection=0,union=0;
        for(int y=0;y<8;y++)for(int x=0;x<11;x++){
            int px=glyph.left+Math.min(glyph.width()-1,(int)((x+.5)*glyph.width()/11));
            int py=glyph.top+Math.min(glyph.height()-1,(int)((y+.5)*glyph.height()/8));
            boolean actual=(gray[py*w+px]&255)>=160,expected=TICK_REFERENCE[y].charAt(x)=='1';
            if(actual&&expected)intersection++;if(actual||expected)union++;
        }
        double score=union==0?0:(double)intersection/union;
        return score>=.80?new TickMark(disk,glyph,score):null;
    }
    /** Input pixels are copied; no reference to the image survives this method. All boxes are
     * half-open crop-local coordinates. A crop is never assumed to be a full device screenshot.
     * The 4 MiPixel resource ceiling does not imply full-screen layout validation; ratio thresholds
     * remain calibrated only for the four provided section crops and uniform scales. */
    public static Geometry observeGray8(Key key,byte[] pixels,int width,int height,BooleanSupplier canceled) {
        return observeGray8(key,pixels,width,height,canceled,false);
    }
    /** Visible More viewport: card height is bounded relative to width, not viewport height.
     * This bounded visual heuristic does not establish that the section is complete. */
    public static Geometry observeVisibleMoreGray8(Key key,byte[] pixels,int width,int height,BooleanSupplier canceled) {
        return observeGray8(key,pixels,width,height,canceled,true);
    }
    private static Geometry observeGray8(Key key,byte[] pixels,int width,int height,BooleanSupplier canceled,boolean visibleMore) {
        Objects.requireNonNull(key);
        BooleanSupplier cancel=canceled==null?()->false:canceled;
        if(width<32||height<32||width>4096||height>8192||(long)width*height>4_194_304
                ||pixels==null||pixels.length!=(long)width*height)
            return new Geometry(key,width,height,Status.INVALID_INPUT,List.of());
        if(cancel.getAsBoolean())return new Geometry(key,width,height,Status.CANCELED,List.of());
        byte[] gray=Arrays.copyOf(pixels,pixels.length);
        try {
        List<Component> raw=components(gray,width,height,new Box(0,0,width,height),visibleMore?4:0,cancel);
        if(raw==null)return new Geometry(key,width,height,Status.CANCELED,List.of());
        if(raw.size()>16)return new Geometry(key,width,height,Status.TOO_MANY_COMPONENTS,List.of());
        List<Card> cards=new ArrayList<>();
        raw.sort(Comparator.comparingInt((Component c)->c.box.top).thenComparingInt(c->c.box.left));
        for(Component c:raw) {
            Box b=c.box;
            boolean complete=b.left>0 && b.top>0 && b.right<width && b.bottom<height;
            Box roi=new Box(b.left+(int)(b.width()*.65),b.top,b.right,b.bottom);
            List<Badge> badges=new ArrayList<>();
            for(int mode=1;mode<=2;mode++) {
                List<Component> parts=components(gray,width,height,roi,mode,cancel);
                if(parts==null)return new Geometry(key,width,height,Status.CANCELED,List.of());
                if(parts.size()>16)return new Geometry(key,width,height,Status.TOO_MANY_COMPONENTS,List.of());
                for(Component part:parts)badges.add(new Badge(part.box,mode==1?BadgeTone.DARK:BadgeTone.PALE));
            }
            List<Component> disks=components(gray,width,height,roi,3,cancel);
            if(disks==null)return new Geometry(key,width,height,Status.CANCELED,List.of());
            if(disks.size()>16)return new Geometry(key,width,height,Status.TOO_MANY_COMPONENTS,List.of());
            List<TickMark> ticks=new ArrayList<>();
            for(Component disk:disks){
                TickMark tick=tickShape(gray,width,disk.box,cancel);
                if(tick!=null)ticks.add(tick);
            }
            cards.add(new Card(b,complete,badges,ticks));
        }
        if(cancel.getAsBoolean())return new Geometry(key,width,height,Status.CANCELED,List.of());
        return new Geometry(key,width,height,cards.isEmpty()?Status.NO_CARDS:Status.OBSERVED,cards);
        } finally { Arrays.fill(gray,(byte)0); }
    }
    public record Row(String text,Box box,double confidence,Origin origin) {}
    /** Section names are auxiliary OCR cues, never a selected navigation-tab assumption. */
    public record Section(Key key,String title,Box titleBox,double confidence,Origin origin) {}
    public enum HeaderLevel { PAGE_AGGREGATE }
    /** Explicit parent context only; it never supplies a child task counter or account credit. */
    public record ProgressHeader(Key key,HeaderLevel level,String title,Box titleBox,Box counterBox,
                                 double confidence,Origin origin) {}
    private static boolean sameFrame(Key a,Key b){
        return a!=null&&b!=null&&a.source()==b.source()&&a.materialId().equals(b.materialId())
            &&a.observation()==b.observation();
    }
    public record Candidate(String visualCardId,Box cardBox,Box badgeBox,int displayedPoints,
                            BadgeTone tone,Origin ocrOrigin,double cropX,double cropY) {
        public boolean verifiedClickable(){return false;}
        public String triggerHypothesis(){return "USER_PROVIDED_TASK_RULE_POINT_BADGE_OPENS_TASK_UNVERIFIED";}
        public String coordinateSpace(){return "CROP_LOCAL_ONLY";}
    }
    public static final class Result {
        private final Geometry geometry;private final Assessment progress;private final Status status;
        private final List<Candidate> candidates;
        private final int ignoredSmallTextBoxes;
        private final List<SkippedItem> skippedItems;
        private Result(Geometry geometry,Assessment progress,Status status,List<Candidate> candidates,int ignoredSmallTextBoxes,List<SkippedItem> skippedItems){
            this.ignoredSmallTextBoxes=ignoredSmallTextBoxes;this.skippedItems=List.copyOf(skippedItems);
            this.geometry=geometry;this.progress=progress;this.status=status;this.candidates=List.copyOf(candidates);
        }
        public Geometry geometry(){return geometry;}public Assessment progress(){return progress;}
        public Status status(){return status;}public List<Candidate> candidates(){return candidates;}
        public int ignoredSmallTextBoxes(){return ignoredSmallTextBoxes;}
        public List<SkippedItem> skippedItems(){return skippedItems;}
    }
    private static Result result(Geometry g,Assessment a,Status s,List<Candidate> c){return new Result(g,a,s,c,0,List.of());}
    private static final Pattern POINTS=Pattern.compile("^\\+[ \\u00a0\\u2009\\u202f]*([0-9]{1,4})$");
    static Integer points(String text) {
        String n=RewardRegionGate.normalizeNumberText(text);
        Matcher m=n==null?null:POINTS.matcher(n);
        if(m==null||!m.matches())return null;
        int value=Integer.parseInt(m.group(1));return value>0?value:null;
    }
    private static boolean confidence(double c){return Double.isFinite(c)&&c>=.8&&c<=1;}
    private static boolean completion(String s) {
        String t=s.toLowerCase(java.util.Locale.ROOT);
        return t.contains("已完成")||t.contains("已领取")||t.contains("completed")||t.contains("claimed");
    }
    private static SkippedItem completedItem(Card card,List<Row> rows,int index){
        if(!card.fullyVisible||card.tickMarks.size()!=1||!card.badges.isEmpty())return null;
        TickMark tick=card.tickMarks.get(0);Box disk=tick.diskBox;
        if(!card.box.contains(disk)||disk.left<card.box.left+.70*card.box.width())return null;
        Row selected=null;int number=0,count=0;
        for(Row row:rows)if(card.box.contains(row.box())){
            if(points(row.text())!=null)return null;
            String numeric=RewardRegionGate.normalizeNumberText(row.text());
            if(numeric==null||!numeric.matches("[0-9]{1,4}"))continue;
            int value=Integer.parseInt(numeric);Box n=row.box();
            // Permit bounded OCR padding into the disk's left background, never its tick glyph.
            int intrusion=Math.max(0,n.right-disk.left);
            if(intrusion>.30*disk.width() || n.centerX()>=disk.left
                    || n.right>tick.glyphBox.left || disk.left-n.right>1.6*disk.width()
                    ||Math.abs(n.centerY()-disk.centerY())>.40*Math.max(n.height(),disk.height())
                    ||n.left<card.box.left+.65*card.box.width())continue;
            count++;selected=row;number=value;
        }
        return count==1&&number>0?new SkippedItem("visual-card-"+index,card.box,number,selected.box(),tick,
            selected.origin(),"USER_PROVIDED_TASK_RULE_NUMBER_THEN_LOCAL_TICK"):null;
    }
    /** Passive static observations only. Does not bypass the progress gate or authorize a target.
     * A shape-matched check requires a separate, high-confidence, plain-number OCR box to its left. */
    public static List<SkippedItem> observeCompletedItems(Geometry g,List<Row> rows){
        if(g==null||g.status!=Status.OBSERVED||rows==null||rows.size()>128)return List.of();
        for(Row r:rows)if(r==null||r.text()==null||r.text().length()>512||r.origin()==null
                ||!confidence(r.confidence())||!valid(r.box(),g.width,g.height))return List.of();
        for(int i=0;i<rows.size();i++)for(int j=i+1;j<rows.size();j++)
            if(rows.get(i).box().overlaps(rows.get(j).box()))return List.of();
        List<SkippedItem> out=new ArrayList<>();
        for(int i=0;i<g.cards.size();i++){
            SkippedItem item=completedItem(g.cards.get(i),rows,i);if(item!=null)out.add(item);
        }
        return List.copyOf(out);
    }
    private static boolean protectedSmallLabel(String text){
        return text.toLowerCase(java.util.Locale.ROOT).trim().matches(
            "(?:小时|分钟|分|时|天|明天|锁定|已锁定|登录|登入|hour|hours|min|minutes|day|days|tomorrow|locked|sign in)");
    }
    private static boolean ignorableUnit(Row row,Geometry g,List<Row> rows){
        String text=row.text().toLowerCase(java.util.Locale.ROOT).trim();
        if(!(text.equals("pts")||text.equals("points")||text.equals("点")||text.equals("积分")))return false;
        for(Card card:g.cards){
            if(!card.fullyVisible||card.badges.size()!=1)continue;
            Box b=card.badges.get(0).box;
            Box safe=new Box(b.left+2,b.top+2,b.right-2,b.bottom-2);
            if(!safe.contains(row.box())||row.box().width()>.40*b.width()||row.box().height()>.40*b.height()
                    ||(long)row.box().width()*row.box().height()>.12*b.width()*b.height())continue;
            Row token=null;int count=0;
            for(Row possible:rows)if(card.box.contains(possible.box())&&points(possible.text())!=null){
                token=possible;count++;
            }
            if(count!=1||!safe.contains(token.box())||row.box().overlaps(token.box()))continue;
            double dx=Math.max(0,Math.max(row.box().left-token.box().right,token.box().left-row.box().right));
            double dy=Math.max(0,Math.max(row.box().top-token.box().bottom,token.box().top-row.box().bottom));
            if(dx<=.18*b.width()&&dy<=.15*b.height())return true;
        }
        return false;
    }
    /** A complete progress gate returns before inspecting target OCR. Unknown progress also stops.
     * The section list and OCR rows must include all observed headers/counters in the crop, not a chosen subset.
     * Each separate visual card may yield one candidate; two badges/tokens IN ONE CARD are ambiguous.
     * Multiple legitimate cards are not collapsed or automatically selected. */
    public static Result associate(Geometry g,Assessment progress,List<Section> sections,List<Row> rows) {
        return associate(g,progress,null,sections,rows);
    }
    public static Result associate(Geometry g,Assessment progress,ProgressHeader parent,List<Section> sections,List<Row> rows) {
        if(g==null)return result(null,progress,Status.INVALID_INPUT,List.of());
        if(g.status==Status.INVALID_INPUT || g.status==Status.CANCELED || g.status==Status.TOO_MANY_COMPONENTS)
            return result(g,progress,g.status,List.of());
        if(progress==null || progress.gate()==Gate.UNKNOWN_STOP
                || (parent==null?!g.key.equals(progress.key()):
                    !sameFrame(g.key,progress.key())||!Objects.equals(parent.key(),progress.key())
                    ||parent.level()!=HeaderLevel.PAGE_AGGREGATE||progress.role()!=CounterRole.AGGREGATE_PROGRESS))
            return result(g,progress,Status.UNKNOWN_PROGRESS,List.of());
        if(progress.gate()==Gate.COMPLETE_SKIP)return result(g,progress,Status.SKIP_COMPLETE,List.of());
        if(g.status!=Status.OBSERVED)return result(g,progress,g.status,List.of());
        if(sections==null||sections.size()!=1||sections.get(0)==null)
            return result(g,progress,Status.BAD_SECTION,List.of());
        Section section=sections.get(0);
        if(!g.key.equals(section.key())||!confidence(section.confidence())
                ||section.origin()==null||!valid(section.titleBox(),g.width,g.height)
                ||!(Objects.equals(section.title(),"每日活动")||Objects.equals(section.title(),"更多活动"))
                ||section.titleBox().left()>.2*g.width||section.titleBox().bottom()>g.cards.get(0).box.top)
            return result(g,progress,Status.BAD_SECTION,List.of());
        if(parent!=null&&(!Objects.equals(parent.title(),"今日积分")||parent.origin()==null
                ||!confidence(parent.confidence())||!valid(parent.titleBox(),g.width,g.height)
                ||!valid(parent.counterBox(),g.width,g.height)||parent.titleBox().left()>.2*g.width
                ||parent.counterBox().left()<.65*g.width
                ||parent.titleBox().bottom()>=section.titleBox().top()
                ||parent.counterBox().bottom()>=section.titleBox().top()
                ||Math.abs(parent.titleBox().centerY()-parent.counterBox().centerY())
                    >.6*Math.max(parent.titleBox().height(),parent.counterBox().height())))
            return result(g,progress,Status.UNKNOWN_PROGRESS,List.of());
        if(rows==null||rows.size()>128)return result(g,progress,Status.BAD_OCR,List.of());
        for(Row row:rows)if(row==null||row.text()==null||row.text().length()>512||row.origin()==null
                ||!confidence(row.confidence())||!valid(row.box(),g.width,g.height))
            return result(g,progress,Status.BAD_OCR,List.of());
        // The supplied OCR batch must cover this whole crop, not only a chosen badge ROI.
        // Count every reported section heading and fraction before associating cards. Key equality
        // alone cannot supply scope: require observed title and a unique aligned progress row.
        int headingRows=0,fractionRows=0,parentHeadingRows=0;
        boolean matchingHeading=false,matchingCounter=false,matchingParent=false;
        for(Row row:rows) {
            String text=row.text();
            if(text.equals("今日积分")){
                parentHeadingRows++;
                matchingParent|=parent!=null&&row.box().equals(parent.titleBox());
            }
            if(text.equals("每日活动")||text.equals("更多活动")){
                headingRows++;
                matchingHeading|=text.equals(section.title())&&row.box().equals(section.titleBox());
            }
            String numeric=RewardRegionGate.normalizeNumberText(text);
            if(numeric!=null && numeric.matches("[0-9]{1,4}[ \\u00a0\\u2009\\u202f]*/[ \\u00a0\\u2009\\u202f]*[0-9]{1,4}")) {
                fractionRows++;
                String compact=numeric.replaceAll("[ \\u00a0\\u2009\\u202f]","");
                String expected=progress.fraction().numerator()+"/"+progress.fraction().denominator();
                Box counterAnchor=parent==null?section.titleBox():parent.titleBox();
                double delta=Math.abs(row.box().centerY()-counterAnchor.centerY());
                matchingCounter|=compact.equals(expected)&&row.box().left()>=.65*g.width
                    &&row.box().bottom()<=g.cards.get(0).box.top
                    &&delta<=Math.max(counterAnchor.height(),row.box().height())*.6
                    &&(parent==null||row.box().equals(parent.counterBox()));
            }
        }
        if(headingRows!=1||!matchingHeading)return result(g,progress,Status.BAD_SECTION,List.of());
        if(fractionRows!=1||!matchingCounter
                ||(parent==null?parentHeadingRows!=0:parentHeadingRows!=1||!matchingParent))
            return result(g,progress,Status.UNKNOWN_PROGRESS,List.of());
        int ignoredSmallTextBoxes=0;List<Row> retained=new ArrayList<>();
        for(Row row:rows){
            if(ignorableUnit(row,g,rows))ignoredSmallTextBoxes++;
            else retained.add(row);
        }
        rows=retained;
        for(int i=0;i<rows.size();i++)for(int j=i+1;j<rows.size();j++)
            if(rows.get(i).box().overlaps(rows.get(j).box()))
                return result(g,progress,Status.BAD_OCR,List.of());
        List<Candidate> candidates=new ArrayList<>();List<SkippedItem> skipped=new ArrayList<>();
        for(int i=0;i<g.cards.size();i++){
            Card card=g.cards.get(i);Box b=card.box;
            if(!card.fullyVisible)return result(g,progress,Status.INCOMPLETE_CARD,List.of());
            SkippedItem finished=completedItem(card,rows,i);
            if(finished!=null){skipped.add(finished);continue;}
            if(!card.tickMarks.isEmpty())return result(g,progress,Status.UNRESOLVED_STATE,List.of());
            if((double)b.width()/b.height()<2 || (double)b.width()/b.height()>4.5)
                return result(g,progress,Status.INCOMPLETE_CARD,List.of());
            for(int j=0;j<g.cards.size();j++)if(i!=j && b.overlaps(g.cards.get(j).box))
                return result(g,progress,Status.AMBIGUOUS_BADGE,List.of());
            if(card.badges.size()!=1)return result(g,progress,Status.AMBIGUOUS_BADGE,List.of());
            Badge badge=card.badges.get(0);Box p=badge.box;
            double relX=(p.left-b.left)/(double)b.width(),relW=p.width()/(double)b.width();
            double relH=p.height()/(double)b.height(),relY=(p.centerY()-b.top)/b.height();
            if(!b.contains(p)||relX<.70||relW<.16||relW>.26||relH<.24||relH>.51||relY<.38||relY>.62)
                return result(g,progress,Status.AMBIGUOUS_BADGE,List.of());
            List<Row> tokens=new ArrayList<>();boolean title=false;
            for(Row row:rows)if(b.contains(row.box())){
                if(completion(row.text()))return result(g,progress,Status.COMPLETION_MARKER,List.of());
                if(p.contains(row.box())&&protectedSmallLabel(row.text()))
                    return result(g,progress,Status.UNRESOLVED_STATE,List.of());
                if(points(row.text())!=null)tokens.add(row);
                else if(row.box().right()<p.left && row.box().top()<b.top+b.height()*.5
                        &&RewardRegionGate.visibleCodePoints(row.text())>=2 && !row.text().matches("[0-9０-９+＋/／ .]+"))
                    title=true;
            }
            if(!title)return result(g,progress,Status.MISSING_TITLE,List.of());
            if(tokens.size()!=1)return result(g,progress,Status.AMBIGUOUS_TOKEN,List.of());
            Row token=tokens.get(0);
            Box safe=new Box(p.left+2,p.top+2,p.right-2,p.bottom-2);
            if(!safe.contains(token.box()))return result(g,progress,Status.AMBIGUOUS_TOKEN,List.of());
            candidates.add(new Candidate("visual-card-"+i,b,p,points(token.text()),badge.tone,
                token.origin(),p.centerX(),p.centerY()));
        }
        return new Result(g,progress,candidates.isEmpty()?Status.SKIPPED_VISIBLE_ITEMS:Status.CANDIDATES,candidates,ignoredSmallTextBoxes,skipped);
    }
    /** Per-item observation of visible More, without claiming the section is fully visible.
     * Caller proves title/header uniqueness across the full frame and verifies the same-frame
     * header has no ambiguous counter before explicitly choosing NO_COUNTER. */
    public enum VisibleItemStatus { CANDIDATE, ITEM_COMPLETE_SKIP, REGION_COMPLETE_SKIP, HOLD }
    public enum CounterState { NO_COUNTER, HEADER_FRACTION }
    public record VisibleItemCounter(CounterState state,Row headerFraction) {}
    public record VisibleItemResult(VisibleItemStatus status,Candidate candidate,SkippedItem skipped,
                                    Row title,Row number,List<Row> body,VisibleItemCounter counter,Fraction fraction) {
        public VisibleItemResult { body=List.copyOf(body); }
    }
    private static VisibleItemResult itemResult(VisibleItemStatus status,Candidate candidate,SkippedItem skipped,
            Row title,Row number,List<Row> body,VisibleItemCounter counter,Fraction fraction) {
        return new VisibleItemResult(status,candidate,skipped,title,number,body,counter,fraction);
    }
    private static VisibleItemResult itemHold(VisibleItemCounter counter){
        return itemResult(VisibleItemStatus.HOLD,null,null,null,null,List.of(),counter,null);
    }
    private static boolean liveConfidence(double value){return Double.isFinite(value)&&value>=.85&&value<=1;}
    public static VisibleItemResult associateVisibleItem(Geometry g,Section section,List<Row> rows,
                                                           String exactTitle,VisibleItemCounter progress) {
        return associateVisibleItem(g,section,rows,exactTitle,progress,null);
    }
    private static VisibleItemResult associateVisibleItem(Geometry g,Section section,List<Row> rows,
                    String exactTitle,VisibleItemCounter progress,Row preferredTitle) {
        if(progress==null||progress.state()==null||g==null||g.status()!=Status.OBSERVED||section==null||rows==null||rows.size()>128
                ||exactTitle==null||RewardRegionGate.visibleCodePoints(exactTitle)<2||exactTitle.length()>160
                ||!g.key().equals(section.key())||!"更多活动".equals(section.title())
                ||section.origin()==null||!liveConfidence(section.confidence())
                ||!valid(section.titleBox(),g.width(),g.height())||section.titleBox().left()>.2*g.width())
            return itemHold(progress);
        int headings=0,titles=0,preferredMatches=0,counters=0;Row selectedTitle=null,counter=null;boolean matchingHeading=false;
        for(Row row:rows){
            if(row==null||row.text()==null||row.text().length()>512||row.origin()==null
                    ||!valid(row.box(),g.width(),g.height()))return itemHold(progress);
            if(row.text().equals("更多活动")){headings++;matchingHeading|=row.box().equals(section.titleBox())&&liveConfidence(row.confidence());}
            if(row.text().equals(exactTitle)){titles++;if(preferredTitle==null||row.equals(preferredTitle)){selectedTitle=row;preferredMatches++;}}
            String n=RewardRegionGate.normalizeNumberText(row.text());
            if(n!=null&&(n.contains("/")||n.matches("[0-9? ]+"))&&row.box().left()>=.65*g.width()
                    &&Math.abs(row.box().centerY()-section.titleBox().centerY())
                        <=.6*Math.max(row.box().height(),section.titleBox().height())){
                counters++;counter=row;
            }
        }
        if(headings!=1||!matchingHeading)return itemHold(progress);
        Fraction fraction=null;
        if(progress.state()==CounterState.NO_COUNTER){
            if(progress.headerFraction()!=null||counters!=0)return itemHold(progress);
        } else {
            if(counters!=1||!Objects.equals(counter,progress.headerFraction())
                    ||!liveConfidence(counter.confidence()))return itemHold(progress);
            String n=RewardRegionGate.normalizeNumberText(counter.text());
            if(n==null)return itemHold(progress);
            Matcher m=Pattern.compile("^([0-9]{1,4})/([0-9]{1,4})$").matcher(
                n.replaceAll("[ \\u00a0\\u2009\\u202f]",""));
            if(!m.matches())return itemHold(progress);
            int numerator=Integer.parseInt(m.group(1)),denominator=Integer.parseInt(m.group(2));
            if(denominator==0||numerator>denominator)return itemHold(progress);
            fraction=new Fraction(numerator,denominator);
            if(numerator==denominator)
                return itemResult(VisibleItemStatus.REGION_COMPLETE_SKIP,null,null,null,null,List.of(),progress,fraction);
        }
        if((preferredTitle==null?titles!=1:preferredMatches!=1)||selectedTitle==null||!liveConfidence(selectedTitle.confidence()))return itemHold(progress);
        Card selected=null;int selectedIndex=-1,count=0;
        for(int i=0;i<g.cards().size();i++)if(g.cards().get(i).box().contains(selectedTitle.box())){
            selected=g.cards().get(i);selectedIndex=i;count++;
        }
        if(count!=1||!selected.fullyVisible()||section.titleBox().bottom()>selected.box().top())return itemHold(progress);
        int titlesInSelected=0;
        for(Row row:rows)if(row.text().equals(exactTitle)&&selected.box().contains(row.box()))titlesInSelected++;
        if(titlesInSelected!=1)return itemHold(progress);
        for(Card other:g.cards())if(other!=selected&&other.box().overlaps(selected.box()))return itemHold(progress);
        List<Row> local=new ArrayList<>(),body=new ArrayList<>();
        for(Row row:rows)if(selected.box().overlaps(row.box())){
            if(!selected.box().contains(row.box())||!liveConfidence(row.confidence()))return itemHold(progress);
            local.add(row);
        }
        for(int i=0;i<local.size();i++)for(int j=i+1;j<local.size();j++)
            if(local.get(i).box().overlaps(local.get(j).box()))return itemHold(progress);
        SkippedItem skipped=completedItem(selected,local,selectedIndex);
        if(skipped!=null){
            Row number=null;for(Row row:local)if(row.box().equals(skipped.numberBox()))number=row;
            if(number==null||selectedTitle.box().right()>=skipped.tick().diskBox().left()
                    ||selectedTitle.box().top()>=selected.box().top()+selected.box().height()*.5)
                return itemHold(progress);
            for(Row row:local)if(row!=selectedTitle&&row!=number
                    &&row.box().right()<skipped.tick().diskBox().left()&&!row.text().trim().isEmpty())body.add(row);
            return itemResult(VisibleItemStatus.ITEM_COMPLETE_SKIP,null,skipped,selectedTitle,number,body,progress,fraction);
        }
        if((double)selected.box().width()/selected.box().height()<2
                ||(double)selected.box().width()/selected.box().height()>4.5
                ||!selected.tickMarks().isEmpty()||selected.badges().size()!=1)return itemHold(progress);
        Badge badge=selected.badges().get(0);Box b=selected.box(),p=badge.box();
        double relX=(p.left()-b.left())/(double)b.width(),relW=p.width()/(double)b.width();
        double relH=p.height()/(double)b.height(),relY=(p.centerY()-b.top())/b.height();
        if(!b.contains(p)||relX<.70||relW<.16||relW>.26||relH<.24||relH>.51||relY<.38||relY>.62
                ||selectedTitle.box().right()>=p.left()||selectedTitle.box().top()>=b.top()+b.height()*.5)
            return itemHold(progress);
        Row number=null;int tokens=0;
        for(Row row:local){
            if(completion(row.text())||p.contains(row.box())&&protectedSmallLabel(row.text()))return itemHold(progress);
            if(points(row.text())!=null){number=row;tokens++;}
            else if(row!=selectedTitle&&row.box().right()<p.left()&&!row.text().trim().isEmpty())body.add(row);
        }
        if(tokens!=1)return itemHold(progress);
        Box safe=new Box(p.left()+2,p.top()+2,p.right()-2,p.bottom()-2);
        if(!safe.contains(number.box()))return itemHold(progress);
        Candidate candidate=new Candidate("visual-card-"+selectedIndex,b,p,points(number.text()),badge.tone(),
            number.origin(),p.centerX(),p.centerY());
        return itemResult(VisibleItemStatus.CANDIDATE,candidate,null,selectedTitle,number,body,progress,fraction);
    }
    /** A family is complete only with respect to the caller-supplied same-frame OCR batch.
     * The caller must establish batch completeness, section ownership, viewport coverage,
     * native provenance and any absence of offscreen members. Never use card order as identity. */
    public enum FamilyStatus { OBSERVED, REGION_COMPLETE_SKIP, HOLD }
    public enum FamilyReason { READY, REGION_COMPLETE, INVALID_INPUT, NO_MATCHING_TITLE,
        AMBIGUOUS_CARD_OR_STATE, MISSING_BODY, DUPLICATE_BODY }
    public record VisibleItemFamily(FamilyStatus status,FamilyReason reason,
                                    List<VisibleItemResult> items,Fraction fraction) {
        public VisibleItemFamily { items=List.copyOf(items); }
    }
    private static VisibleItemFamily familyHold(FamilyReason reason){
        return new VisibleItemFamily(FamilyStatus.HOLD,reason,List.of(),null);
    }
    private static String bodyIdentity(List<Row> body){
        List<String> parts=new ArrayList<>();boolean hasLetter=false;
        for(Row row:body){
            String text=row.text().trim().replaceAll("[ \\t\\r\\n\\u00a0\\u2009\\u202f]+"," ")
                .toLowerCase(java.util.Locale.ROOT);
            if(text.isEmpty())continue;
            for(int i=0;i<text.length();){int cp=text.codePointAt(i);i+=Character.charCount(cp);hasLetter|=Character.isLetter(cp);}
            parts.add(text);
        }
        if(parts.isEmpty()||!hasLetter)return null;
        parts.sort(Comparator.naturalOrder());return String.join("\n",parts);
    }
    public static VisibleItemFamily observeVisibleItems(Geometry g,Section section,List<Row> rows,
                                                        String exactTitle,VisibleItemCounter counter){
        if(g==null||section==null||rows==null||counter==null||exactTitle==null||rows.size()>128)
            return familyHold(FamilyReason.INVALID_INPUT);
        // Validate header/counter even when there are no target rows; a valid complete header
        // skips the region without pretending a target was seen.
        VisibleItemResult first=associateVisibleItem(g,section,rows,exactTitle,counter,null);
        if(first.status()==VisibleItemStatus.REGION_COMPLETE_SKIP)
            return new VisibleItemFamily(FamilyStatus.REGION_COMPLETE_SKIP,FamilyReason.REGION_COMPLETE,List.of(),first.fraction());
        List<Row> titles=new ArrayList<>();
        for(Row row:rows)if(row!=null&&exactTitle.equals(row.text()))titles.add(row);
        if(titles.isEmpty())return familyHold(FamilyReason.NO_MATCHING_TITLE);
        List<VisibleItemResult> items=new ArrayList<>();List<String> bodies=new ArrayList<>();
        for(Row title:titles){
            VisibleItemResult item=associateVisibleItem(g,section,rows,exactTitle,counter,title);
            if(item.status()!=VisibleItemStatus.CANDIDATE&&item.status()!=VisibleItemStatus.ITEM_COMPLETE_SKIP)
                return familyHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE);
            String identity=bodyIdentity(item.body());
            // Multiple unfinished same-title cards must carry distinct reliable semantic text.
            // A completed short card may have collapsed body; it is evidence, not attribution.
            if(titles.size()>1&&item.status()==VisibleItemStatus.CANDIDATE&&identity==null)
                return familyHold(FamilyReason.MISSING_BODY);
            if(identity!=null&&bodies.contains(identity))return familyHold(FamilyReason.DUPLICATE_BODY);
            if(identity!=null)bodies.add(identity);
            items.add(item);
        }
        items.sort(Comparator.comparingInt((VisibleItemResult r)->r.status()==VisibleItemStatus.CANDIDATE
            ?r.candidate().cardBox().top():r.skipped().cardBox().top())
            .thenComparingInt(r->r.status()==VisibleItemStatus.CANDIDATE
                ?r.candidate().cardBox().left():r.skipped().cardBox().left()));
        return new VisibleItemFamily(FamilyStatus.OBSERVED,FamilyReason.READY,items,items.get(0).fraction());
    }
    public enum TitleMatchKind { EXACT_SINGLE, NORMALIZED_SINGLE, QUESTION_SINGLE, TWO_LINES }
    /** Original parts are OCR evidence. The envelope is geometry only, never an OCR row. */
    public record TitleEvidence(TitleMatchKind kind,List<Row> originalParts,Box envelope,double minConfidence){
        public TitleEvidence { originalParts=List.copyOf(originalParts); }
    }
    /** Safe diagnostic: boxes and scores, with no OCR text. */
    public record TitleDiagnostic(TitleMatchKind kind,int partCount,double minConfidence,List<Box> rawBoxes){
        public TitleDiagnostic { rawBoxes=List.copyOf(rawBoxes); }
    }
    /** Original OCR Row objects only. U is an unmodeled field, not proof of a decorative icon. */
    public record RowPartition(List<Row> leftUnmodeled,List<Row> centralIdentity,List<Row> rightState){
        public RowPartition { leftUnmodeled=List.copyOf(leftUnmodeled);centralIdentity=List.copyOf(centralIdentity);rightState=List.copyOf(rightState); }
        @Override public String toString(){return "RowPartition[left="+leftUnmodeled.size()+",central="+centralIdentity.size()+",right="+rightState.size()+"]";}
    }
    public record TitleCard(VisibleItemStatus status,Candidate candidate,SkippedItem skipped,
                            TitleEvidence title,Row number,List<Row> body,RowPartition partition){
        public TitleCard { body=List.copyOf(body); }
        public TitleCard(VisibleItemStatus status,Candidate candidate,SkippedItem skipped,
                         TitleEvidence title,Row number,List<Row> body){this(status,candidate,skipped,title,number,body,null);}
    }
    public enum FailureGate { INPUT, ROW_FORMAT, HEADER_CONTEXT, COUNTER_FORMAT, COUNTER_RANGE,
        TITLE_AMBIGUOUS, CARD_PARTIAL, CARD_BEFORE_HEADER, TITLE_LOW_CONFIDENCE, CARD_OVERLAP,
        ROW_CROSSES_CARD, ROW_LOW_CONFIDENCE, ROW_OVERLAP, CARD_ASPECT, TICK_CONFLICT,
        BADGE_COUNT, BADGE_CONTAINMENT, BADGE_LEFT, BADGE_WIDTH, BADGE_HEIGHT, BADGE_ALIGNMENT,
        COMPLETION_TEXT, PROTECTED_LABEL, POINTS_COUNT, POINTS_OUTSIDE_SAFE, MISSING_NUMBER,
        TITLE_AT_INDICATOR, BODY_ABOVE_TITLE, BODY_MISSING, BODY_DUPLICATE, NO_TITLE_MATCH,
        PARTITION_CROSSING, TEMPLATE_CONFLICT, PARTITION_LOW_CONFIDENCE, IDENTITY_BODY, RIGHT_STATE, POINTS_VALUE, RIGHT_EXTRA_ROW, UNEXPLAINED_ROW, CONSUMED_CATEGORY, EXTRA_STEP_RISK, PENDING_COUNT, KNOWN_COMPLETED_GUARD }
    /** Only geometry, scores, numeric candidate, counts and enums; never OCR text. The unique
     * numeric value/score is diagnostic, not proof of acceptance. cardIndex is frame-local, never an ID.
     * Negative counts, null boxes and NOT_EVALUATED checks mean not applicable or not evaluated. */
    public enum DiagnosticCheck { NOT_EVALUATED, YES, NO }
    public enum CrossingCoordinateSpace { NOT_AVAILABLE, LOCATOR_INPUT }
    public enum RowRegion { U_REGION, C_REGION, R_REGION, UNCLASSIFIED, CROSSES_CARD }
    /** Geometry/score only; contains no OCR text or Row object. */
    public record LocalRowDiagnostic(Box box,double confidence,RowRegion region) {}
    public enum PartitionBoundary { NOT_AVAILABLE, U_TO_GUTTER, GUTTER_ONLY, GUTTER_TO_C,
        U_TO_C, C_TO_R, MULTIPLE_BOUNDARIES, OTHER }
    public record FailureDiagnostic(FailureGate gate,int cardIndex,Box cardBox,Box indicatorBox,
            int localRows,int lowConfidenceRows,int crossingRows,int overlappingPairs,
            int badgeCount,int tickCount,int pointsCount,DiagnosticCheck badgeGeometryValid,
            DiagnosticCheck pointsInsideSafe,DiagnosticCheck titleBeforeIndicator,
            Box numberBox,Box derivedSafeBox,int unmodeledRows,int centralRows,int rightRows,int unclassifiedRows,
            int reportedUnclassified,boolean unclassifiedTruncated,CrossingCoordinateSpace crossingCoordinateSpace,
            Box unclassifiedBox1,double unclassifiedScore1,PartitionBoundary unclassifiedBoundary1,
            Box unclassifiedBox2,double unclassifiedScore2,PartitionBoundary unclassifiedBoundary2,
            List<LocalRowDiagnostic> localRowDiagnostics,boolean localRowsTruncated,CrossingCoordinateSpace localCoordinateSpace,
            double uBoundary,double cBoundary,double rBoundary,int uniquePointsValue,double uniquePointsScore) {
        public FailureDiagnostic { localRowDiagnostics=List.copyOf(localRowDiagnostics); }
        public FailureDiagnostic(FailureGate gate,int cardIndex,Box cardBox,Box indicatorBox,
                int localRows,int lowConfidenceRows,int crossingRows,int overlappingPairs,
                int badgeCount,int tickCount,int pointsCount,DiagnosticCheck badgeGeometryValid,
                DiagnosticCheck pointsInsideSafe,DiagnosticCheck titleBeforeIndicator){
            this(gate,cardIndex,cardBox,indicatorBox,localRows,lowConfidenceRows,crossingRows,overlappingPairs,
                badgeCount,tickCount,pointsCount,badgeGeometryValid,pointsInsideSafe,titleBeforeIndicator,null,null,-1,-1,-1,-1,-1,false,CrossingCoordinateSpace.NOT_AVAILABLE,null,-1,PartitionBoundary.NOT_AVAILABLE,null,-1,PartitionBoundary.NOT_AVAILABLE,List.of(),false,CrossingCoordinateSpace.NOT_AVAILABLE,-1,-1,-1,-1,-1);
        }
    }
    public record TitleFamily(FamilyStatus status,FamilyReason reason,List<TitleCard> items,
                              Fraction fraction,List<TitleDiagnostic> diagnostics,FailureDiagnostic failure){
        public TitleFamily { items=List.copyOf(items);diagnostics=List.copyOf(diagnostics); }
        public TitleFamily(FamilyStatus status,FamilyReason reason,List<TitleCard> items,
                           Fraction fraction,List<TitleDiagnostic> diagnostics){
            this(status,reason,items,fraction,diagnostics,null);
        }
    }
    private static FailureDiagnostic failure(FailureGate gate,int index,Card card,List<Row> rows,TitleEvidence title,Box explicitIndicator){
        if(card==null)return new FailureDiagnostic(gate,-1,null,null,-1,-1,-1,-1,-1,-1,-1,DiagnosticCheck.NOT_EVALUATED,DiagnosticCheck.NOT_EVALUATED,DiagnosticCheck.NOT_EVALUATED);
        List<Row> local=new ArrayList<>();int low=0,crossing=0,overlaps=0,pointsCount=0;Row token=null;
        for(Row row:rows)if(card.box().overlaps(row.box())){
            local.add(row);if(!liveConfidence(row.confidence()))low++;
            if(!card.box().contains(row.box()))crossing++;
            if(points(row.text())!=null){pointsCount++;token=row;}
        }
        for(int i=0;i<local.size();i++)for(int j=i+1;j<local.size();j++)
            if(local.get(i).box().overlaps(local.get(j).box()))overlaps++;
        int badges=card.badges().size(),ticks=card.tickMarks().size();
        Box badge=badges==1?card.badges().get(0).box():null;
        Box indicator=explicitIndicator!=null?explicitIndicator:badge!=null?badge:ticks==1?card.tickMarks().get(0).diskBox():null;
        DiagnosticCheck geometry=DiagnosticCheck.NOT_EVALUATED,safe=DiagnosticCheck.NOT_EVALUATED,before=DiagnosticCheck.NOT_EVALUATED;
        Box numberBox=pointsCount==1?token.box():null,derivedSafeBox=null;
        int uniqueValue=pointsCount==1?points(token.text()):-1;double uniqueScore=pointsCount==1?token.confidence():-1;
        if(numberBox==null&&pointsCount==0&&badges==0&&ticks==1){
            SkippedItem done=completedItem(card,local,index);
            if(done!=null){numberBox=done.numberBox();uniqueValue=done.displayedNumber();
                for(Row row:local)if(row.box().equals(numberBox)){uniqueScore=row.confidence();break;}}
        }
        if(badge!=null){
            Box b=card.box(),p=badge;double relX=(p.left()-b.left())/(double)b.width(),relW=p.width()/(double)b.width();
            double relH=p.height()/(double)b.height(),relY=(p.centerY()-b.top())/b.height();
            geometry=b.contains(p)&&relX>=.70&&relW>=.16&&relW<=.26&&relH>=.24&&relH<=.51&&relY>=.38&&relY<=.62?DiagnosticCheck.YES:DiagnosticCheck.NO;
            if(p.width()>4&&p.height()>4){derivedSafeBox=new Box(p.left()+2,p.top()+2,p.right()-2,p.bottom()-2);
                if(numberBox!=null)safe=derivedSafeBox.contains(numberBox)?DiagnosticCheck.YES:DiagnosticCheck.NO;}
        }
        if(title!=null&&indicator!=null)before=title.envelope().right()<indicator.left()?DiagnosticCheck.YES:DiagnosticCheck.NO;
        List<LocalRowDiagnostic> evidence=new ArrayList<>();
        for(int i=0;i<Math.min(8,local.size());i++){
            Row row=local.get(i);evidence.add(new LocalRowDiagnostic(row.box(),row.confidence(),localRowRegion(card,row.box())));
        }
        double u=card.box().left()+SCOPED_U_RIGHT_FRACTION*card.box().width(),c=card.box().left()+.18*card.box().width(),r=card.box().left()+.75*card.box().width();
        return new FailureDiagnostic(gate,index,card.box(),indicator,local.size(),low,crossing,overlaps,badges,ticks,pointsCount,geometry,safe,before,numberBox,derivedSafeBox,-1,-1,-1,-1,-1,false,CrossingCoordinateSpace.NOT_AVAILABLE,null,-1,PartitionBoundary.NOT_AVAILABLE,null,-1,PartitionBoundary.NOT_AVAILABLE,
            evidence,local.size()>evidence.size(),CrossingCoordinateSpace.LOCATOR_INPUT,u,c,r,uniqueValue,uniqueScore);
    }
    private static TitleFamily titleHold(FamilyReason reason,List<TitleDiagnostic> diagnostics,
            FailureGate gate,int index,Card card,List<Row> rows,TitleEvidence title,Box indicator){
        return new TitleFamily(FamilyStatus.HOLD,reason,List.of(),null,diagnostics,failure(gate,index,card,rows,title,indicator));
    }
    // Only Unicode spaces, letter case and a single terminal ASCII/full-width question mark
    // are normalized. No fuzzy matching, OCR substitutions, word completion, or NFKC.
    private static String titleText(String text,boolean dropQuestion){
        if(text==null||text.length()>512)return null;
        StringBuilder out=new StringBuilder();boolean space=false;
        for(int i=0;i<text.length();){
            int cp=text.codePointAt(i);i+=Character.charCount(cp);
            if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)){space=out.length()>0;continue;}
            if(space){out.append(' ');space=false;}
            out.appendCodePoint(cp==0xff1f?'?':cp);
        }
        String v=out.toString().toLowerCase(java.util.Locale.ROOT);
        if(dropQuestion&&v.endsWith("?")){
            int end=v.length()-1;
            while(end>0&&v.charAt(end-1)==' ')end--;
            v=v.substring(0,end);
        }
        return v;
    }
    /** Single native OCR row only: no fuzzy substitutions, missing words, or synthetic joining. */
    public static boolean matchesKnownTitleText(String actual,String expected){
        if(actual==null||expected==null||expected.length()>160||RewardRegionGate.visibleCodePoints(expected)<2)return false;
        String a=titleText(actual,true),e=titleText(expected,true);
        return a!=null&&e!=null&&a.equals(e);
    }
    private static int terminalQuestion(String text){
        for(int i=text.length();i>0;){int cp=text.codePointBefore(i);i-=Character.charCount(cp);
            if(Character.isWhitespace(cp)||Character.isSpaceChar(cp))continue;
            return cp=='?'?1:cp==0xff1f?2:0;
        }
        return 0;
    }
    private static Box enclosing(Box a,Box b){return new Box(Math.min(a.left(),b.left()),Math.min(a.top(),b.top()),
        Math.max(a.right(),b.right()),Math.max(a.bottom(),b.bottom()));}
    private static boolean headingPart(Card card,Row row,boolean completedPair){
        Box b=card.box(),r=row.box();double limit=completedPair?.75:.5;
        return b.contains(r)&&r.top()<b.top()+b.height()*limit&&r.bottom()<=b.top()+b.height()*limit
            &&r.left()<b.left()+b.width()*.65&&r.right()<=b.left()+b.width()*.75;
    }
    // Expanded two-line geometry is permitted only after an independent, same-card plain
    // number plus shape-matched tick is established. OCR scores and original boxes are preserved.
    private static boolean independentlyCompleted(Card card,List<Row> rows,int index){
        if(!card.fullyVisible())return false;
        List<Row> local=new ArrayList<>();
        for(Row row:rows)if(card.box().overlaps(row.box())){
            if(!card.box().contains(row.box()))return false;
            local.add(row);
        }
        for(int i=0;i<local.size();i++)for(int j=i+1;j<local.size();j++)
            if(local.get(i).box().overlaps(local.get(j).box()))return false;
        SkippedItem completed=completedItem(card,local,index);
        if(completed==null)return false;
        for(Row row:local)if(row.box().equals(completed.numberBox()))return liveConfidence(row.confidence());
        return false;
    }
    // Only the fixed More-family completed two-line path may use this larger envelope.
    // Single-line, unfinished and unverified-number matching keep their original limits.
    private static boolean completedPairPart(Card card,Row row){
        Box b=card.box(),r=row.box();
        return b.contains(r)&&r.top()<b.top()+b.height()*.80&&r.bottom()<=b.top()+b.height()*.80
            &&r.left()<b.left()+b.width()*.65&&r.right()<=b.left()+b.width()*.75;
    }
    private static boolean independentlyCompletedFive(Card card,List<Row> rows,int index){
        if(!independentlyCompleted(card,rows,index))return false;
        List<Row> local=new ArrayList<>();for(Row row:rows)if(card.box().overlaps(row.box()))local.add(row);
        SkippedItem item=completedItem(card,local,index);
        return item!=null&&item.displayedNumber()==5;
    }
    private static boolean adjacentHeading(Row a,Row b){
        Box x=a.box(),y=b.box();double maxHeight=Math.max(x.height(),y.height());
        return x.bottom()<=y.top()&&y.top()-x.bottom()<=maxHeight
            &&Math.abs(x.left()-y.left())<=.75*maxHeight
            &&Math.min(x.height(),y.height())>=.65*maxHeight;
    }
    private static TitleEvidence evidence(TitleMatchKind kind,List<Row> parts){
        Box e=parts.get(0).box();double confidence=parts.get(0).confidence();
        for(int i=1;i<parts.size();i++){e=enclosing(e,parts.get(i).box());confidence=Math.min(confidence,parts.get(i).confidence());}
        return new TitleEvidence(kind,parts,e,confidence);
    }
    private static List<TitleEvidence> titleMatches(Card card,List<Row> rows,String expected,boolean completed){
        return titleMatches(card,rows,expected,completed,false);
    }
    private static List<TitleEvidence> titleMatches(Card card,List<Row> rows,String expected,boolean completed,boolean scopedFivePair){
        List<Row> possible=new ArrayList<>();for(Row row:rows)if(headingPart(card,row,completed)||(scopedFivePair&&completedPairPart(card,row)))possible.add(row);
        possible.sort(Comparator.comparingInt((Row r)->r.box().top()).thenComparingInt(r->r.box().left()));
        List<TitleEvidence> out=new ArrayList<>();String target=titleText(expected,true),withQuestion=titleText(expected,false);
        for(Row row:possible){
            if(!headingPart(card,row,completed))continue; // Preserve previous single-line bounds.
            if(!headingPart(card,row,false)&&!(completed&&row.box().top()<card.box().top()+card.box().height()*.5))continue;
            String bare=titleText(row.text(),true),full=titleText(row.text(),false);
            if(target.equals(bare)){
                TitleMatchKind kind=row.text().equals(expected)?TitleMatchKind.EXACT_SINGLE:
                    Objects.equals(full,withQuestion)&&terminalQuestion(row.text())==terminalQuestion(expected)
                        ?TitleMatchKind.NORMALIZED_SINGLE:TitleMatchKind.QUESTION_SINGLE;
                out.add(evidence(kind,List.of(row)));
            }
        }
        for(int i=0;i<possible.size();i++)for(int j=i+1;j<possible.size();j++){
            Row a=possible.get(i),b=possible.get(j);
            if(a.box().top()<card.box().top()+card.box().height()*.5
                    &&(headingPart(card,b,completed)||(scopedFivePair&&a.box().bottom()<=card.box().top()+card.box().height()*.5))
                    &&adjacentHeading(a,b)&&target.equals(titleText(a.text()+" "+b.text(),true)))
                out.add(evidence(TitleMatchKind.TWO_LINES,List.of(a,b)));
        }
        return out;
    }
    /** A strict literal proper fragment is a veto signal, never title evidence. */
    public static boolean matchesKnownTitleFragment(String actual,String expected){
        if(actual==null||expected==null||expected.length()>160
                ||RewardRegionGate.visibleCodePoints(expected)<2)return false;
        String a=titleText(actual,true),e=titleText(expected,true);
        if(a==null||e==null||a.isEmpty()||a.equals(e))return false;
        String[] words=e.split(" ");
        for(int start=0;start<words.length;start++){
            StringBuilder part=new StringBuilder();
            for(int end=start;end<words.length;end++){
                if(part.length()>0)part.append(' ');
                part.append(words[end]);
                if(!a.contentEquals(part))continue;
                if(end>start)return true;
                String word=words[start];int count=0;boolean letters=true;
                for(int i=0;i<word.length();){int cp=word.codePointAt(i);i+=Character.charCount(cp);count++;letters&=Character.isLetter(cp);}
                if(letters&&count>=6)return true;
            }
        }
        return false;
    }
    /** Full-frame literal-title coverage only, not card selection or completion proof.
     * No confidence filtering; evidence retains the original row objects and scores.
     * Invalid input must be treated as a hold by the caller. */
    public static List<TitleEvidence> enumerateKnownTitleEvidence(List<Row> fullRows,String exactTitle){
        if(fullRows==null||fullRows.size()>128||exactTitle==null||exactTitle.length()>160
                ||RewardRegionGate.visibleCodePoints(exactTitle)<2)
            throw new IllegalArgumentException("invalid title coverage input");
        List<Row> ordered=new ArrayList<>();
        for(Row row:fullRows){
            if(row==null||row.box()==null||row.text()==null||row.text().length()>512
                    ||!Double.isFinite(row.confidence())||row.confidence()<0||row.confidence()>1)
                throw new IllegalArgumentException("invalid title coverage row");
            ordered.add(row);
        }
        ordered.sort(Comparator.comparingInt((Row r)->r.box().top()).thenComparingInt(r->r.box().left()));
        List<TitleEvidence> out=new ArrayList<>();
        String target=titleText(exactTitle,true),withQuestion=titleText(exactTitle,false);
        for(Row row:ordered){
            if(matchesKnownTitleText(row.text(),exactTitle)){
                String full=titleText(row.text(),false);
                TitleMatchKind kind=row.text().equals(exactTitle)?TitleMatchKind.EXACT_SINGLE:
                    Objects.equals(full,withQuestion)&&terminalQuestion(row.text())==terminalQuestion(exactTitle)
                        ?TitleMatchKind.NORMALIZED_SINGLE:TitleMatchKind.QUESTION_SINGLE;
                out.add(evidence(kind,List.of(row)));
            }
        }
        for(int i=0;i<ordered.size();i++)for(int j=i+1;j<ordered.size();j++){
            Row a=ordered.get(i),b=ordered.get(j);
            if(adjacentHeading(a,b)&&target.equals(titleText(a.text()+" "+b.text(),true)))
                out.add(evidence(TitleMatchKind.TWO_LINES,List.of(a,b)));
        }
        return List.copyOf(out);
    }
    private static TitleDiagnostic safeTitleDiagnostic(TitleEvidence e){
        List<Box> boxes=new ArrayList<>();for(Row row:e.originalParts())boxes.add(row.box());
        return new TitleDiagnostic(e.kind(),e.originalParts().size(),e.minConfidence(),boxes);
    }
    /** Strict title format matching is limited to 1–2 original rows inside one CV card.
     * The caller proves frame provenance, OCR batch completeness and viewport coverage.
     * Missing or unrecognized sibling headings cannot be inferred from this observer. */
    public static TitleFamily observeTitleFamily(Geometry g,Section section,List<Row> rows,
                                                 String exactTitle,VisibleItemCounter counter){
        List<TitleDiagnostic> diagnostics=new ArrayList<>();
        if(g==null||g.status()!=Status.OBSERVED||section==null||rows==null||rows.size()>128
                ||exactTitle==null||RewardRegionGate.visibleCodePoints(exactTitle)<2||exactTitle.length()>160
                ||counter==null||counter.state()!=CounterState.HEADER_FRACTION
                ||!g.key().equals(section.key())||!"更多活动".equals(section.title())
                ||section.origin()==null||!liveConfidence(section.confidence())
                ||!valid(section.titleBox(),g.width(),g.height())||section.titleBox().left()>.2*g.width())
            return titleHold(FamilyReason.INVALID_INPUT,diagnostics,FailureGate.INPUT,-1,null,null,null,null);
        int headings=0,counters=0;boolean headerMatch=false;Row headerCounter=null;
        for(Row row:rows){
            if(row==null||row.text()==null||row.text().length()>512||row.origin()==null
                    ||!valid(row.box(),g.width(),g.height()))return titleHold(FamilyReason.INVALID_INPUT,diagnostics,FailureGate.ROW_FORMAT,-1,null,null,null,null);
            if(row.text().equals("更多活动")){headings++;headerMatch|=row.box().equals(section.titleBox())&&liveConfidence(row.confidence());}
            String n=RewardRegionGate.normalizeNumberText(row.text());
            if(n!=null&&(n.contains("/")||n.matches("[0-9? ]+"))&&row.box().left()>=.65*g.width()
                    &&Math.abs(row.box().centerY()-section.titleBox().centerY())
                       <=.6*Math.max(row.box().height(),section.titleBox().height())){counters++;headerCounter=row;}
        }
        if(headings!=1||!headerMatch||counters!=1||!Objects.equals(headerCounter,counter.headerFraction())
                ||!liveConfidence(headerCounter.confidence()))return titleHold(FamilyReason.INVALID_INPUT,diagnostics,FailureGate.HEADER_CONTEXT,-1,null,null,null,null);
        String n=RewardRegionGate.normalizeNumberText(headerCounter.text());
        Matcher m=n==null?null:Pattern.compile("^([0-9]{1,4})/([0-9]{1,4})$").matcher(n.replaceAll("[ \\u00a0\\u2009\\u202f]",""));
        if(m==null||!m.matches())return titleHold(FamilyReason.INVALID_INPUT,diagnostics,FailureGate.COUNTER_FORMAT,-1,null,null,null,null);
        int numerator=Integer.parseInt(m.group(1)),denominator=Integer.parseInt(m.group(2));
        if(denominator==0||numerator>denominator)return titleHold(FamilyReason.INVALID_INPUT,diagnostics,FailureGate.COUNTER_RANGE,-1,null,null,null,null);
        Fraction fraction=new Fraction(numerator,denominator);
        if(numerator==denominator)return new TitleFamily(FamilyStatus.REGION_COMPLETE_SKIP,FamilyReason.REGION_COMPLETE,List.of(),fraction,diagnostics);
        List<List<TitleEvidence>> byCard=new ArrayList<>();
        for(int i=0;i<g.cards().size();i++){
            Card card=g.cards().get(i);
            List<TitleEvidence> matches=titleMatches(card,rows,exactTitle,independentlyCompleted(card,rows,i));byCard.add(matches);
            for(TitleEvidence e:matches)diagnostics.add(safeTitleDiagnostic(e));
        }
        List<TitleCard> items=new ArrayList<>();List<String> bodies=new ArrayList<>();
        for(int index=0;index<g.cards().size();index++){
            Card card=g.cards().get(index);List<TitleEvidence> matches=byCard.get(index);
            if(matches.isEmpty())continue;
            if(matches.size()!=1)return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.TITLE_AMBIGUOUS,index,card,rows,null,null);
            if(!card.fullyVisible())return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.CARD_PARTIAL,index,card,rows,matches.get(0),null);
            if(section.titleBox().bottom()>card.box().top())return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.CARD_BEFORE_HEADER,index,card,rows,matches.get(0),null);
            TitleEvidence title=matches.get(0);
            if(!liveConfidence(title.minConfidence()))return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.TITLE_LOW_CONFIDENCE,index,card,rows,title,null);
            for(Card other:g.cards())if(other!=card&&other.box().overlaps(card.box()))
                return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.CARD_OVERLAP,index,card,rows,title,null);
            List<Row> local=new ArrayList<>(),body=new ArrayList<>();
            for(Row row:rows)if(card.box().overlaps(row.box())){
                if(!card.box().contains(row.box()))return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.ROW_CROSSES_CARD,index,card,rows,title,null);
                if(!liveConfidence(row.confidence()))return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.ROW_LOW_CONFIDENCE,index,card,rows,title,null);
                local.add(row);
            }
            for(int i=0;i<local.size();i++)for(int j=i+1;j<local.size();j++)
                if(local.get(i).box().overlaps(local.get(j).box()))return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.ROW_OVERLAP,index,card,rows,title,null);
            SkippedItem skipped=completedItem(card,local,index);Box indicator;Row number=null;Candidate candidate=null;
            if(skipped!=null){
                indicator=skipped.tick().diskBox();
                for(Row row:local)if(row.box().equals(skipped.numberBox()))number=row;
            } else {
                if((double)card.box().width()/card.box().height()<2||(double)card.box().width()/card.box().height()>4.5)
                    return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.CARD_ASPECT,index,card,rows,title,null);
                if(!card.tickMarks().isEmpty())return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.TICK_CONFLICT,index,card,rows,title,null);
                if(card.badges().size()!=1)return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.BADGE_COUNT,index,card,rows,title,null);
                Badge badge=card.badges().get(0);Box b=card.box(),p=badge.box();indicator=p;
                double relX=(p.left()-b.left())/(double)b.width(),relW=p.width()/(double)b.width();
                double relH=p.height()/(double)b.height(),relY=(p.centerY()-b.top())/b.height();
                if(!b.contains(p))return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.BADGE_CONTAINMENT,index,card,rows,title,p);
                if(relX<.70)return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.BADGE_LEFT,index,card,rows,title,p);
                if(relW<.16||relW>.26)return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.BADGE_WIDTH,index,card,rows,title,p);
                if(relH<.24||relH>.51)return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.BADGE_HEIGHT,index,card,rows,title,p);
                if(relY<.38||relY>.62)return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.BADGE_ALIGNMENT,index,card,rows,title,p);
                int tokens=0;
                for(Row row:local){
                    if(completion(row.text()))return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.COMPLETION_TEXT,index,card,rows,title,p);
                    if(p.contains(row.box())&&protectedSmallLabel(row.text()))return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.PROTECTED_LABEL,index,card,rows,title,p);
                    if(points(row.text())!=null){number=row;tokens++;}
                }
                Box safe=new Box(p.left()+2,p.top()+2,p.right()-2,p.bottom()-2);
                if(tokens!=1)return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.POINTS_COUNT,index,card,rows,title,p);
                if(!safe.contains(number.box()))return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.POINTS_OUTSIDE_SAFE,index,card,rows,title,p);
                candidate=new Candidate("visual-card-"+index,b,p,points(number.text()),badge.tone(),number.origin(),p.centerX(),p.centerY());
            }
            if(number==null)return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.MISSING_NUMBER,index,card,rows,title,indicator);
            if(title.envelope().right()>=indicator.left())return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.TITLE_AT_INDICATOR,index,card,rows,title,indicator);
            for(Row row:local)if(!title.originalParts().contains(row)&&row!=number
                    &&row.box().right()<indicator.left()&&!row.text().trim().isEmpty()){
                if(row.box().top()<title.envelope().bottom())return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.BODY_ABOVE_TITLE,index,card,rows,title,indicator);
                body.add(row);
            }
            String identity=bodyIdentity(body);
            if(candidate!=null&&identity==null)return titleHold(FamilyReason.MISSING_BODY,diagnostics,FailureGate.BODY_MISSING,index,card,rows,title,indicator);
            if(identity!=null&&bodies.contains(identity))return titleHold(FamilyReason.DUPLICATE_BODY,diagnostics,FailureGate.BODY_DUPLICATE,index,card,rows,title,indicator);
            if(identity!=null)bodies.add(identity);
            items.add(new TitleCard(skipped==null?VisibleItemStatus.CANDIDATE:VisibleItemStatus.ITEM_COMPLETE_SKIP,
                candidate,skipped,title,number,body));
        }
        if(items.isEmpty())return titleHold(FamilyReason.NO_MATCHING_TITLE,diagnostics,FailureGate.NO_TITLE_MATCH,-1,null,null,null,null);
        return new TitleFamily(FamilyStatus.OBSERVED,FamilyReason.READY,items,fraction,diagnostics);
    }
    // Bounded OCR-box tolerance: C/R remain independent and unchanged.
    private static final double SCOPED_U_RIGHT_FRACTION=.161;
    private static final String SCOPED_TITLE="Do you know the answer?";
    private static final String SCOPED_BODY_A="Challenge yourself with these trivia questions";
    private static final String SCOPED_BODY_B="Test your knowledge on these topics";
    private record LocalPartition(List<Row> u,List<Row> c,List<Row> r,List<Row> unclassified) {}
    private static LocalPartition partitionRows(Card card,List<Row> local){
        List<Row> u=new ArrayList<>(),c=new ArrayList<>(),r=new ArrayList<>(),unknown=new ArrayList<>();
        double left=card.box().left(),width=card.box().width();
        for(Row row:local){Box b=row.box();
            if(b.right()<=left+SCOPED_U_RIGHT_FRACTION*width)u.add(row);
            else if(b.left()>=left+.18*width&&b.right()<=left+.75*width)c.add(row);
            else if(b.left()>=left+.75*width&&b.right()<=card.box().right())r.add(row);
            else unknown.add(row);
        }
        return new LocalPartition(u,c,r,unknown);
    }
    private static RowRegion localRowRegion(Card card,Box box){
        if(!card.box().contains(box))return RowRegion.CROSSES_CARD;
        double left=card.box().left(),width=card.box().width();
        if(box.right()<=left+SCOPED_U_RIGHT_FRACTION*width)return RowRegion.U_REGION;
        if(box.left()>=left+.18*width&&box.right()<=left+.75*width)return RowRegion.C_REGION;
        if(box.left()>=left+.75*width&&box.right()<=card.box().right())return RowRegion.R_REGION;
        return RowRegion.UNCLASSIFIED;
    }
    private static PartitionBoundary partitionBoundary(Card card,Box box){
        double left=card.box().left(),width=card.box().width(),u=left+SCOPED_U_RIGHT_FRACTION*width,c=left+.18*width,r=left+.75*width;
        if(box.left()<u&&box.right()>r||box.left()<c&&box.right()>r)return PartitionBoundary.MULTIPLE_BOUNDARIES;
        if(box.left()<u&&box.right()>c)return PartitionBoundary.U_TO_C;
        if(box.left()<u&&box.right()>u)return PartitionBoundary.U_TO_GUTTER;
        if(box.left()>=u&&box.right()<=c)return PartitionBoundary.GUTTER_ONLY;
        if(box.left()<c&&box.right()>c)return PartitionBoundary.GUTTER_TO_C;
        if(box.left()<r&&box.right()>r)return PartitionBoundary.C_TO_R;
        return PartitionBoundary.OTHER;
    }
    private static TitleFamily partitionHold(FamilyReason reason,List<TitleDiagnostic> diagnostics,FailureGate gate,
                int index,Card card,List<Row> rows,TitleEvidence title,Box indicator,LocalPartition part){
        FailureDiagnostic f=failure(gate,index,card,rows,title,indicator);
        List<Row> unmatched=part.unclassified();int reported=Math.min(2,unmatched.size());
        Row first=reported>0?unmatched.get(0):null,second=reported>1?unmatched.get(1):null;
        FailureDiagnostic result=new FailureDiagnostic(f.gate(),f.cardIndex(),f.cardBox(),f.indicatorBox(),
            f.localRows(),f.lowConfidenceRows(),f.crossingRows(),f.overlappingPairs(),f.badgeCount(),f.tickCount(),
            f.pointsCount(),f.badgeGeometryValid(),f.pointsInsideSafe(),f.titleBeforeIndicator(),
            f.numberBox(),f.derivedSafeBox(),part.u().size(),part.c().size(),part.r().size(),unmatched.size(),
            reported,unmatched.size()>reported,reported==0?CrossingCoordinateSpace.NOT_AVAILABLE:CrossingCoordinateSpace.LOCATOR_INPUT,
            first==null?null:first.box(),first==null?-1:first.confidence(),first==null?PartitionBoundary.NOT_AVAILABLE:partitionBoundary(card,first.box()),
            second==null?null:second.box(),second==null?-1:second.confidence(),second==null?PartitionBoundary.NOT_AVAILABLE:partitionBoundary(card,second.box()),
            f.localRowDiagnostics(),f.localRowsTruncated(),f.localCoordinateSpace(),f.uBoundary(),f.cBoundary(),f.rBoundary(),f.uniquePointsValue(),f.uniquePointsScore());
        return new TitleFamily(FamilyStatus.HOLD,reason,List.of(),null,diagnostics,result);
    }
    private static String exactBodyRows(List<Row> body){
        List<Row> ordered=new ArrayList<>(body);
        ordered.sort(Comparator.comparingInt((Row r)->r.box().top()).thenComparingInt(r->r.box().left()));
        StringBuilder out=new StringBuilder();
        for(Row row:ordered){String text=row.text();if(text==null)return null;
            StringBuilder part=new StringBuilder();boolean space=false;
            for(int i=0;i<text.length();){int cp=text.codePointAt(i);i+=Character.charCount(cp);
                if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)){space=part.length()>0;continue;}
                if(space){part.append(' ');space=false;}part.appendCodePoint(cp);
            }
            if(part.length()==0)return null;if(out.length()>0)out.append(' ');out.append(part);
        }
        return out.toString();
    }
    private static boolean scopedConflict(String text){
        String s=text.toLowerCase(java.util.Locale.ROOT);
        if(completion(s)||s.contains("已赚取")||s.contains("already earned")||s.contains("锁")||s.contains("🔒")||s.contains("🔐")||s.contains("登录")
                ||s.contains("登入")||s.contains("未开放")||s.contains("购买")||s.contains("订阅")||s.contains("付款")
                ||s.contains("支付")||s.contains("授权")||s.contains("隐私")||s.contains("下载")||s.contains("安装")
                ||s.contains("明天")||s.contains("兑换")||s.contains("充值")||s.contains("下单")
                ||s.contains("locked")||s.contains("sign in")||s.contains("subscribe")||s.contains("subscription")
                ||s.contains("payment")||s.contains("consent")||s.contains("privacy")||s.contains("tomorrow")
                ||s.contains("top up")||s.contains("place order")||s.matches(".*\\b(?:lock|buy|purchase|redeem|checkout|install|download)\\b.*"))return true;
        return s.contains("√")||s.contains("✓")||s.contains("✔")||s.contains("☑")||s.contains("/")||s.contains("／")
            ||s.matches(".*[0-9０-９]+[ \\t\\u00a0\\u2009\\u202f]*(?:小时|分钟|hours?|minutes?).*");
    }
    private static boolean containsPlusSign(String text){return text.indexOf('+')>=0||text.indexOf('＋')>=0||text.indexOf('﹢')>=0;}
    private static boolean containsPlusAmount(String text){return Pattern.compile("[+＋﹢][ \\u00a0\\u2009\\u202f]*[0-9０-９]").matcher(text).find();}
    private static boolean unmodeledConflictSequence(List<Row> rows){
        List<Row> ordered=new ArrayList<>(rows);
        ordered.sort(Comparator.comparingInt((Row r)->r.box().top()).thenComparingInt(r->r.box().left()));
        for(int i=0;i<ordered.size();i++){
            StringBuilder text=new StringBuilder(),joined=new StringBuilder();
            for(int j=i;j<ordered.size();j++){
                if(text.length()>0)text.append(' ');text.append(ordered.get(j).text());joined.append(ordered.get(j).text());
                if(text.length()>512)break;
                if(knownForeignIdentity(text.toString())||scopedConflict(text.toString())||containsPlusAmount(text.toString())
                        ||scopedConflict(joined.toString())||containsPlusAmount(joined.toString()))return true;
            }
        }
        return false;
    }
    private static boolean knownForeignIdentity(String text){
        String s=titleText(text,true);
        return Objects.equals(s,titleText(SCOPED_TITLE,true))||Objects.equals(s,titleText("Quote of the day",true))
            ||Objects.equals(s,titleText("Complete this puzzle",true))||Objects.equals(s,titleText(SCOPED_BODY_A,true))
            ||Objects.equals(s,titleText(SCOPED_BODY_B,true));
    }
    /** Fixed More trivia template only; U is not an icon claim, input field or completeness proof.
     * The caller must independently establish full-frame batch/viewport and family coverage. */
    public static TitleFamily observeTitleFamilyPartitioned(Geometry g,Section section,List<Row> rows,
                                                           String exactTitle,VisibleItemCounter counter){
        // U may be below the quality threshold, never malformed or non-finite.
        if(rows!=null)for(Row row:rows)if(row!=null&&(!Double.isFinite(row.confidence())
                ||row.confidence()<0||row.confidence()>1||RewardRegionGate.visibleCodePoints(row.text())==0))
            return titleHold(FamilyReason.INVALID_INPUT,List.of(),FailureGate.ROW_FORMAT,-1,null,null,null,null);
        TitleFamily original=observeTitleFamily(g,section,rows,exactTitle,counter);
        if(!SCOPED_TITLE.equals(exactTitle))return titleHold(FamilyReason.INVALID_INPUT,List.of(),FailureGate.INPUT,-1,null,null,null,null);
        if(original.status()==FamilyStatus.REGION_COMPLETE_SKIP||original.reason()==FamilyReason.INVALID_INPUT)return original;
        String header=RewardRegionGate.normalizeNumberText(counter.headerFraction().text());
        Matcher fractionMatcher=Pattern.compile("^([0-9]{1,4})/([0-9]{1,4})$").matcher(header.replaceAll("[ \\u00a0\\u2009\\u202f]",""));
        if(!fractionMatcher.matches())return original; // Header was already validated by the original observer.
        Fraction scopedFraction=new Fraction(Integer.parseInt(fractionMatcher.group(1)),Integer.parseInt(fractionMatcher.group(2)));
        List<TitleDiagnostic> diagnostics=new ArrayList<>();List<List<TitleEvidence>> scopedMatches=new ArrayList<>();
        for(int i=0;i<g.cards().size();i++){
            Card card=g.cards().get(i);boolean completed=independentlyCompleted(card,rows,i);
            List<TitleEvidence> matches=titleMatches(card,rows,exactTitle,completed,independentlyCompletedFive(card,rows,i));
            scopedMatches.add(matches);for(TitleEvidence match:matches)diagnostics.add(safeTitleDiagnostic(match));
        }
        List<TitleCard> items=new ArrayList<>();Set<String> bodies=new HashSet<>();
        for(int index=0;index<g.cards().size();index++){
            Card card=g.cards().get(index);List<TitleEvidence> matches=scopedMatches.get(index);
            if(matches.isEmpty())continue;
            if(matches.size()!=1||!card.fullyVisible()||section.titleBox().bottom()>card.box().top())
                return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.TITLE_AMBIGUOUS,index,card,rows,null,null);
            TitleEvidence title=matches.get(0);
            if(!liveConfidence(title.minConfidence()))return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.TITLE_LOW_CONFIDENCE,index,card,rows,title,null);
            for(Card other:g.cards())if(other!=card&&other.box().overlaps(card.box()))
                return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.CARD_OVERLAP,index,card,rows,title,null);
            List<Row> local=new ArrayList<>();
            for(Row row:rows)if(card.box().overlaps(row.box())){
                if(!card.box().contains(row.box()))return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.ROW_CROSSES_CARD,index,card,rows,title,null);
                local.add(row);
            }
            for(int i=0;i<local.size();i++)for(int j=i+1;j<local.size();j++)if(local.get(i).box().overlaps(local.get(j).box()))
                return titleHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.ROW_OVERLAP,index,card,rows,title,null);
            LocalPartition part=partitionRows(card,local);
            if(!part.unclassified().isEmpty())return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.PARTITION_CROSSING,index,card,rows,title,null,part);
            for(Row row:title.originalParts())if(!part.c().contains(row))
                return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.PARTITION_CROSSING,index,card,rows,title,null,part);
            for(Row row:local){
                if(scopedConflict(row.text())||(containsPlusSign(row.text())&&!part.r().contains(row))
                        ||(part.u().contains(row)&&knownForeignIdentity(row.text())))
                    return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.TEMPLATE_CONFLICT,index,card,rows,title,null,part);
            }
            if(unmodeledConflictSequence(part.u()))
                return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.TEMPLATE_CONFLICT,index,card,rows,title,null,part);
            for(Row row:part.c())if(!liveConfidence(row.confidence()))
                return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.PARTITION_LOW_CONFIDENCE,index,card,rows,title,null,part);
            for(Row row:part.r())if(!liveConfidence(row.confidence()))
                return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.PARTITION_LOW_CONFIDENCE,index,card,rows,title,null,part);
            SkippedItem skipped=completedItem(card,local,index);Box indicator;Row number=null;Candidate candidate=null;
            if(skipped!=null){
                indicator=skipped.tick().diskBox();for(Row row:part.r())if(row.box().equals(skipped.numberBox()))number=row;
                if(number==null)return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.MISSING_NUMBER,index,card,rows,title,indicator,part);
                if(skipped.displayedNumber()!=5)return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.POINTS_VALUE,index,card,rows,title,indicator,part);
                for(Row row:part.r())if(row!=number)return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.RIGHT_EXTRA_ROW,index,card,rows,title,indicator,part);
            }else{
                if((double)card.box().width()/card.box().height()<2||(double)card.box().width()/card.box().height()>4.5)
                    return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.CARD_ASPECT,index,card,rows,title,null,part);
                if(!card.tickMarks().isEmpty())return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.TICK_CONFLICT,index,card,rows,title,null,part);
                if(card.badges().size()!=1)return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.BADGE_COUNT,index,card,rows,title,null,part);
                Badge badge=card.badges().get(0);Box b=card.box(),p=badge.box();indicator=p;
                double relX=(p.left()-b.left())/(double)b.width(),relW=p.width()/(double)b.width(),relH=p.height()/(double)b.height(),relY=(p.centerY()-b.top())/b.height();
                if(!b.contains(p))return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.BADGE_CONTAINMENT,index,card,rows,title,p,part);
                if(relX<.70)return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.BADGE_LEFT,index,card,rows,title,p,part);
                if(relW<.16||relW>.26)return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.BADGE_WIDTH,index,card,rows,title,p,part);
                if(relH<.24||relH>.51)return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.BADGE_HEIGHT,index,card,rows,title,p,part);
                if(relY<.38||relY>.62)return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.BADGE_ALIGNMENT,index,card,rows,title,p,part);
                int tokens=0;for(Row row:part.r())if(points(row.text())!=null){number=row;tokens++;}
                Box safe=new Box(p.left()+2,p.top()+2,p.right()-2,p.bottom()-2);
                if(tokens!=1)return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.POINTS_COUNT,index,card,rows,title,p,part);
                if(points(number.text())!=5)return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.POINTS_VALUE,index,card,rows,title,p,part);
                if(!safe.contains(number.box()))return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.POINTS_OUTSIDE_SAFE,index,card,rows,title,p,part);
                for(Row row:part.r())if(row!=number&&!ignorableUnit(row,g,rows))
                    return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.RIGHT_EXTRA_ROW,index,card,rows,title,p,part);
                candidate=new Candidate("visual-card-"+index,b,p,5,badge.tone(),number.origin(),p.centerX(),p.centerY());
            }
            if(title.envelope().right()>=indicator.left())return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.TITLE_AT_INDICATOR,index,card,rows,title,indicator,part);
            List<Row> body=new ArrayList<>();for(Row row:part.c())if(!title.originalParts().contains(row)){
                if(row.box().top()<title.envelope().bottom())return partitionHold(FamilyReason.AMBIGUOUS_CARD_OR_STATE,diagnostics,FailureGate.BODY_ABOVE_TITLE,index,card,rows,title,indicator,part);
                body.add(row);
            }
            String bodyText=exactBodyRows(body),a=SCOPED_BODY_A,b=SCOPED_BODY_B;
            if((candidate!=null&&(bodyText==null||bodyText.isEmpty()))||(!body.isEmpty()&&!Objects.equals(bodyText,a)&&!Objects.equals(bodyText,b)))
                return partitionHold(FamilyReason.MISSING_BODY,diagnostics,FailureGate.IDENTITY_BODY,index,card,rows,title,indicator,part);
            if(bodyText!=null&&!bodyText.isEmpty()&&!bodies.add(bodyText))return partitionHold(FamilyReason.DUPLICATE_BODY,diagnostics,FailureGate.BODY_DUPLICATE,index,card,rows,title,indicator,part);
            RowPartition partition=new RowPartition(part.u(),part.c(),part.r());
            items.add(new TitleCard(skipped==null?VisibleItemStatus.CANDIDATE:VisibleItemStatus.ITEM_COMPLETE_SKIP,
                candidate,skipped,title,number,body,partition));
        }
        if(items.isEmpty())return titleHold(FamilyReason.NO_MATCHING_TITLE,diagnostics,FailureGate.NO_TITLE_MATCH,-1,null,null,null,null);
        return new TitleFamily(FamilyStatus.OBSERVED,FamilyReason.READY,items,scopedFraction,diagnostics);
    }
    /** Dedicated passive facts only: none of these types contains Row, TitleEvidence,
     * Candidate, business Card/Frame, task identity, or a method to create authority. */
    public enum CurrentReadStatus { PENDING, COMPLETE, HOLD, UNMODELED }
    public enum CurrentReadIdentity { EXACT_A, EXACT_B, UNNAMED, UNKNOWN }
    public enum CurrentBatchStatus { PROCESSED, HOLD }
    public record CurrentReadCard(int frameCardIndex,Box cardBox,CurrentReadStatus status,CurrentReadIdentity identity,
            int points,FailureGate firstGate,Box titleBox,Box numberBox,Box indicatorBox,double minIdentityStateScore,int bodyRows) {
        String compact(){return "{index="+frameCardIndex+",box="+compactBox(cardBox)+",state="+status+",identity="+identity
            +",points="+points+",gate="+firstGate+",title="+compactBox(titleBox)+",number="+compactBox(numberBox)
            +",indicator="+compactBox(indicatorBox)+",minIdentityStateScore="+minIdentityStateScore+",qualityExcludesUnmodeledU="+(status==CurrentReadStatus.PENDING)+",bodyRows="+bodyRows+"}";}
    }
    public record CurrentReadBatch(CurrentBatchStatus status,FailureGate firstGate,Status geometryStatus,int headerCount,
            Fraction headerFraction,int nativeCards,List<CurrentReadCard> cards,boolean truncated,int unassociatedKnownTitles,int unassociatedKnownFragments) {
        public CurrentReadBatch {cards=List.copyOf(cards);}
        public String summary(){StringBuilder out=new StringBuilder("profile=READ_ONLY_PER_CARD batchStatus=").append(status)
            .append(" firstGate=").append(firstGate).append(" geometry=").append(geometryStatus).append(" moreHeaders=").append(headerCount)
            .append(" headerFraction=").append(headerFraction==null?"UNOBSERVED":headerFraction.numerator()+"/"+headerFraction.denominator())
            .append(" nativeCards=").append(nativeCards).append(" reported=").append(cards.size()).append(" truncated=").append(truncated)
            .append(" unassociatedKnownTitles=").append(unassociatedKnownTitles).append(" unassociatedKnownFragments=").append(unassociatedKnownFragments)
            .append(" familyCoverage=UNESTABLISHED completeFamily=false businessEligible=false taskTaps=0 causalAttribution=NOT_APPLICABLE items=[");
            for(int i=0;i<cards.size();i++){if(i>0)out.append(';');out.append(cards.get(i).compact());}return out.append(']').toString();}
    }
    private static String compactBox(Box b){return b==null?"NONE":b.left()+","+b.top()+","+b.right()+","+b.bottom();}
    private static CurrentReadBatch currentHold(FailureGate gate,Status geometry,int headings,int cards){
        return new CurrentReadBatch(CurrentBatchStatus.HOLD,gate,geometry,headings,null,cards,List.of(),false,-1,-1);
    }
    private static CurrentReadCard currentFailure(int index,Card card,FailureGate gate,TitleEvidence title){
        return new CurrentReadCard(index,card.box(),CurrentReadStatus.HOLD,CurrentReadIdentity.UNKNOWN,-1,gate,
            title==null?null:title.envelope(),null,null,title==null?-1:title.minConfidence(),-1);
    }
    private static boolean originalMember(List<Row> rows,Row target){for(Row r:rows)if(r==target)return true;return false;}
    /** Same full-frame rows and pixel buffer only. No section crop, confidence prefilter, OCR or input is performed here. */
    public static CurrentReadBatch observeCurrentCardsGray8(Key key,byte[] gray,int width,int height,List<Row> rows,BooleanSupplier canceled){
        if(canceled==null)return currentHold(FailureGate.INPUT,Status.INVALID_INPUT,0,-1);BooleanSupplier cancel=canceled;
        if(key==null||rows==null||rows.isEmpty()||rows.size()>128)return currentHold(FailureGate.INPUT,Status.INVALID_INPUT,0,-1);
        for(Row row:rows)if(row==null||row.text()==null||row.text().length()>512||row.origin()==null||!valid(row.box(),width,height)
                ||!Double.isFinite(row.confidence())||row.confidence()<0||row.confidence()>1)
            return currentHold(FailureGate.ROW_FORMAT,Status.INVALID_INPUT,0,-1);
        if(cancel.getAsBoolean())return currentHold(FailureGate.INPUT,Status.CANCELED,0,-1);
        Geometry geometry=observeVisibleMoreGray8(key,gray,width,height,cancel);if(cancel.getAsBoolean())return currentHold(FailureGate.INPUT,Status.CANCELED,0,geometry.cards().size());Row heading=null;int headings=0;
        for(Row row:rows)if("更多活动".equals(row.text())){headings++;heading=row;}
        if(headings!=1||!liveConfidence(heading.confidence())||heading.box().left()>.2*width)
            return currentHold(FailureGate.HEADER_CONTEXT,geometry.status(),headings,geometry.cards().size());
        if(geometry.status()!=Status.OBSERVED)return currentHold(FailureGate.INPUT,geometry.status(),headings,geometry.cards().size());
        int first=height;for(Card card:geometry.cards())if(card.box().top()>=heading.box().bottom())first=Math.min(first,card.box().top());
        Row fraction=null;int counters=0;
        for(Row row:rows){if(row==heading||row.box().top()<heading.box().top()||row.box().bottom()>first)continue;
            String text=row.text().trim();boolean right=row.box().left()>=.65*width;
            if(!right&&!text.matches(".*[0-9０-９/／?].*"))continue;counters++;fraction=row;
            if(!right||!liveConfidence(row.confidence())||Math.abs(row.box().centerY()-heading.box().centerY())>.6*Math.max(row.box().height(),heading.box().height()))
                return currentHold(FailureGate.HEADER_CONTEXT,geometry.status(),headings,geometry.cards().size());
        }
        if(counters!=1)return currentHold(FailureGate.HEADER_CONTEXT,geometry.status(),headings,geometry.cards().size());
        var result=currentPerCard(geometry,new Section(key,"更多活动",heading.box(),heading.confidence(),heading.origin()),rows,
            SCOPED_TITLE,new VisibleItemCounter(CounterState.HEADER_FRACTION,fraction));
        return cancel.getAsBoolean()?currentHold(FailureGate.INPUT,Status.CANCELED,headings,geometry.cards().size()):result;
    }
    /** A local current fact is not a complete family/section or cross-frame identity claim. */
    private static CurrentReadBatch currentPerCard(Geometry g,Section section,List<Row> rows,String title,VisibleItemCounter counter){
        if(g==null||g.status()!=Status.OBSERVED||section==null||!g.key().equals(section.key())||!"更多活动".equals(section.title())
                ||!liveConfidence(section.confidence())||rows==null||rows.size()>128||!SCOPED_TITLE.equals(title)
                ||counter==null||counter.state()!=CounterState.HEADER_FRACTION||counter.headerFraction()==null)
            return currentHold(FailureGate.INPUT,g==null?Status.INVALID_INPUT:g.status(),0,g==null?-1:g.cards().size());
        int headings=0,counters=0;boolean exactHeading=false;Row fraction=null;
        for(Row row:rows){if(row==null||row.text()==null||row.text().length()>512||row.origin()==null||!valid(row.box(),g.width(),g.height())
                ||!Double.isFinite(row.confidence())||row.confidence()<0||row.confidence()>1)
                return currentHold(FailureGate.ROW_FORMAT,g.status(),0,g.cards().size());
            if("更多活动".equals(row.text())){headings++;exactHeading|=row.box().equals(section.titleBox())&&liveConfidence(row.confidence());}
            String n=RewardRegionGate.normalizeNumberText(row.text());
            if(row.box().left()>=.65*g.width()&&Math.abs(row.box().centerY()-section.titleBox().centerY())<=.6*Math.max(row.box().height(),section.titleBox().height())
                &&n!=null&&(n.contains("/")||n.matches("[0-9? ]+"))){counters++;fraction=row;}
        }
        if(headings!=1||!exactHeading||counters!=1||fraction!=counter.headerFraction()||!liveConfidence(fraction.confidence()))
            return currentHold(FailureGate.HEADER_CONTEXT,g.status(),headings,g.cards().size());
        String n=RewardRegionGate.normalizeNumberText(fraction.text());Matcher match=n==null?null:Pattern.compile("^([0-9]{1,4})/([0-9]{1,4})$").matcher(n.replaceAll("[ \\u00a0\\u2009\\u202f]",""));
        if(match==null||!match.matches())return currentHold(FailureGate.COUNTER_FORMAT,g.status(),headings,g.cards().size());
        int numerator=Integer.parseInt(match.group(1)),denominator=Integer.parseInt(match.group(2));
        if(denominator==0||numerator>denominator)return currentHold(FailureGate.COUNTER_RANGE,g.status(),headings,g.cards().size());
        List<CurrentReadCard> items=new ArrayList<>();for(int i=0;i<g.cards().size()&&i<8;i++)items.add(currentReadOne(g,section,rows,i));
        int unassociated=0,fragments=0;List<Row> adopted=new ArrayList<>();List<List<Row>> titles=new ArrayList<>();
        for(int i=0;i<g.cards().size();i++){Card c=g.cards().get(i);boolean completed=independentlyCompleted(c,rows,i);
            for(TitleEvidence e:titleMatches(c,rows,SCOPED_TITLE,completed,independentlyCompletedFive(c,rows,i))){titles.add(e.originalParts());adopted.addAll(e.originalParts());}}
        for(TitleEvidence evidence:enumerateKnownTitleEvidence(rows,SCOPED_TITLE)){int owners=0;
            for(List<Row> parts:titles)if(sameOriginalParts(evidence.originalParts(),parts))owners++;
            if(owners!=1)unassociated++;
        }
        for(Row row:rows)if(!originalMember(adopted,row)&&matchesKnownTitleFragment(row.text(),SCOPED_TITLE))fragments++;
        return new CurrentReadBatch(CurrentBatchStatus.PROCESSED,null,g.status(),headings,new Fraction(numerator,denominator),g.cards().size(),items,g.cards().size()>items.size(),unassociated,fragments);
    }
    private static boolean sameOriginalParts(List<Row> a,List<Row> b){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(a.get(i)!=b.get(i))return false;return true;}
    private static CurrentReadCard currentReadOne(Geometry g,Section section,List<Row> rows,int index){
        Card card=g.cards().get(index);List<Row> local=new ArrayList<>();for(Row row:rows)if(card.box().overlaps(row.box()))local.add(row);
        boolean completed=independentlyCompleted(card,rows,index);
        List<TitleEvidence> titles=titleMatches(card,rows,SCOPED_TITLE,completed,independentlyCompletedFive(card,rows,index));
        if(titles.isEmpty()){
            for(Row row:local)if(matchesKnownTitleText(row.text(),SCOPED_TITLE)||matchesKnownTitleFragment(row.text(),SCOPED_TITLE))
                return currentFailure(index,card,FailureGate.TITLE_AMBIGUOUS,null);
            return new CurrentReadCard(index,card.box(),CurrentReadStatus.UNMODELED,CurrentReadIdentity.UNKNOWN,-1,FailureGate.NO_TITLE_MATCH,null,null,null,-1,-1);
        }
        if(titles.size()!=1)return currentFailure(index,card,FailureGate.TITLE_AMBIGUOUS,null);TitleEvidence title=titles.get(0);
        if(!card.fullyVisible())return currentFailure(index,card,FailureGate.CARD_PARTIAL,title);
        if(card.box().top()<=section.titleBox().bottom())return currentFailure(index,card,FailureGate.CARD_BEFORE_HEADER,title);
        if(!liveConfidence(title.minConfidence()))return currentFailure(index,card,FailureGate.TITLE_LOW_CONFIDENCE,title);
        for(Card other:g.cards())if(other!=card&&other.box().overlaps(card.box()))return currentFailure(index,card,FailureGate.CARD_OVERLAP,title);
        for(Row row:local)if(!card.box().contains(row.box()))return currentFailure(index,card,FailureGate.ROW_CROSSES_CARD,title);
        for(int i=0;i<local.size();i++)for(int j=i+1;j<local.size();j++)if(local.get(i).box().overlaps(local.get(j).box()))return currentFailure(index,card,FailureGate.ROW_OVERLAP,title);
        for(Row row:local)if(!originalMember(title.originalParts(),row)
            &&(matchesKnownTitleText(row.text(),SCOPED_TITLE)||matchesKnownTitleFragment(row.text(),SCOPED_TITLE)))
            return currentFailure(index,card,FailureGate.TITLE_AMBIGUOUS,title);
        SkippedItem skip=completedItem(card,local,index);
        if(skip!=null)return currentComplete(card,local,index,title,skip);
        if(!card.tickMarks().isEmpty())return currentFailure(index,card,FailureGate.TICK_CONFLICT,title);
        return currentPending(g,card,rows,local,index,title);
    }
    private static CurrentReadCard currentComplete(Card card,List<Row> local,int index,TitleEvidence title,SkippedItem skip){
        double compact=(double)card.box().height()/card.box().width();if(compact<.10||compact>.22)return currentFailure(index,card,FailureGate.CARD_ASPECT,title);
        if(skip.displayedNumber()!=5)return currentFailure(index,card,FailureGate.POINTS_VALUE,title);
        Row number=null;for(Row row:local)if(row.box().equals(skip.numberBox())){if(number!=null)return currentFailure(index,card,FailureGate.POINTS_COUNT,title);number=row;}
        List<Row> body=new ArrayList<>();double score=title.minConfidence();
        for(Row row:local){if(!liveConfidence(row.confidence()))return currentFailure(index,card,FailureGate.PARTITION_LOW_CONFIDENCE,title);score=Math.min(score,row.confidence());
            if(scopedConflict(row.text())||containsPlusSign(row.text()))return currentFailure(index,card,FailureGate.TEMPLATE_CONFLICT,title);
            if(originalMember(title.originalParts(),row)||row==number)continue;
            if(row.box().top()<title.envelope().bottom()||row.box().right()>=skip.numberBox().left())return currentFailure(index,card,FailureGate.RIGHT_EXTRA_ROW,title);body.add(row);
        }
        if(number==null||title.envelope().right()>=number.box().left())return currentFailure(index,card,FailureGate.TITLE_AT_INDICATOR,title);
        String text=exactBodyRows(body);CurrentReadIdentity identity=text!=null&&text.equals(SCOPED_BODY_A)?CurrentReadIdentity.EXACT_A:
            text!=null&&text.equals(SCOPED_BODY_B)?CurrentReadIdentity.EXACT_B:body.isEmpty()?CurrentReadIdentity.UNNAMED:null;
        if(identity==null)return currentFailure(index,card,FailureGate.IDENTITY_BODY,title);
        return new CurrentReadCard(index,card.box(),CurrentReadStatus.COMPLETE,identity,5,null,title.envelope(),number.box(),skip.tick().diskBox(),score,body.size());
    }
    private static CurrentReadCard currentPending(Geometry g,Card card,List<Row> rows,List<Row> local,int index,TitleEvidence title){
        LocalPartition p=partitionRows(card,local);if(!p.unclassified().isEmpty())return currentFailure(index,card,FailureGate.PARTITION_CROSSING,title);
        for(Row row:title.originalParts())if(!originalMember(p.c(),row))return currentFailure(index,card,FailureGate.PARTITION_CROSSING,title);
        for(Row row:local)if(scopedConflict(row.text())||(containsPlusSign(row.text())&&!originalMember(p.r(),row))
                ||originalMember(p.u(),row)&&knownForeignIdentity(row.text()))return currentFailure(index,card,FailureGate.TEMPLATE_CONFLICT,title);
        if(unmodeledConflictSequence(p.u()))return currentFailure(index,card,FailureGate.TEMPLATE_CONFLICT,title);
        double score=title.minConfidence();for(Row row:p.c())if(!liveConfidence(row.confidence()))return currentFailure(index,card,FailureGate.PARTITION_LOW_CONFIDENCE,title);else score=Math.min(score,row.confidence());
        for(Row row:p.r())if(!liveConfidence(row.confidence()))return currentFailure(index,card,FailureGate.PARTITION_LOW_CONFIDENCE,title);else score=Math.min(score,row.confidence());
        double aspect=(double)card.box().width()/card.box().height();if(aspect<2||aspect>4.5)return currentFailure(index,card,FailureGate.CARD_ASPECT,title);
        if(card.badges().size()!=1)return currentFailure(index,card,FailureGate.BADGE_COUNT,title);Box badge=card.badges().get(0).box(),b=card.box();
        double x=(badge.left()-b.left())/(double)b.width(),w=badge.width()/(double)b.width(),h=badge.height()/(double)b.height(),y=(badge.centerY()-b.top())/b.height();
        if(!b.contains(badge))return currentFailure(index,card,FailureGate.BADGE_CONTAINMENT,title);
        if(x<.70)return currentFailure(index,card,FailureGate.BADGE_LEFT,title);if(w<.16||w>.26)return currentFailure(index,card,FailureGate.BADGE_WIDTH,title);
        if(h<.24||h>.51)return currentFailure(index,card,FailureGate.BADGE_HEIGHT,title);if(y<.38||y>.62)return currentFailure(index,card,FailureGate.BADGE_ALIGNMENT,title);
        Row number=null;int numbers=0;for(Row row:p.r())if(points(row.text())!=null){number=row;numbers++;}
        if(numbers!=1)return currentFailure(index,card,FailureGate.POINTS_COUNT,title);if(points(number.text())!=5)return currentFailure(index,card,FailureGate.POINTS_VALUE,title);
        if(!new Box(badge.left()+2,badge.top()+2,badge.right()-2,badge.bottom()-2).contains(number.box()))return currentFailure(index,card,FailureGate.POINTS_OUTSIDE_SAFE,title);
        for(Row row:p.r())if(row!=number&&!ignorableUnit(row,g,rows))return currentFailure(index,card,FailureGate.RIGHT_EXTRA_ROW,title);
        if(title.envelope().right()>=badge.left())return currentFailure(index,card,FailureGate.TITLE_AT_INDICATOR,title);
        List<Row> body=new ArrayList<>();for(Row row:p.c())if(!originalMember(title.originalParts(),row)){
            if(row.box().top()<title.envelope().bottom())return currentFailure(index,card,FailureGate.BODY_ABOVE_TITLE,title);body.add(row);}
        String text=exactBodyRows(body);CurrentReadIdentity identity=SCOPED_BODY_A.equals(text)?CurrentReadIdentity.EXACT_A:SCOPED_BODY_B.equals(text)?CurrentReadIdentity.EXACT_B:null;
        if(identity==null)return currentFailure(index,card,FailureGate.IDENTITY_BODY,title);
        return new CurrentReadCard(index,card.box(),CurrentReadStatus.PENDING,identity,5,null,title.envelope(),number.box(),badge,score,body.size());
    }
    /** Separate native business verifier. This is not constructed from any readonly result. */
    public static final class SelectedPendingFamily {
        private final List<TitleCard> items;private final Fraction fraction;private final FailureGate failure;
        private final int failureCard;private final List<Box> unmodeled;
        private SelectedPendingFamily(List<TitleCard> items,Fraction fraction,FailureGate failure,int failureCard,List<Box> unmodeled){
            this.items=List.copyOf(items);this.fraction=fraction;this.failure=failure;this.failureCard=failureCard;this.unmodeled=List.copyOf(unmodeled);
        }
        public boolean accepted(){return failure==null&&items.size()==2;}public List<TitleCard> items(){return items;}
        public Fraction fraction(){return fraction;}public FailureGate failure(){return failure;}public int failureCard(){return failureCard;}
        public List<Box> unmodeled(){return unmodeled;}
        @Override public String toString(){return "SelectedPendingFamily[accepted="+accepted()+",failure="+failure+",knownItems="+items.size()+",unmodeled="+unmodeled.size()+"]";}
    }
    private static SelectedPendingFamily selectedHold(FailureGate gate,int index,List<Box> unknown){return new SelectedPendingFamily(List.of(),null,gate,index,unknown);}
    private record SelectedLocal(TitleCard item,FailureGate failure) {}
    /** Full original batch only. Exactly two known title evidences, not a whole Rewards family claim. */
    public static SelectedPendingFamily observeSelectedPendingFamily(Geometry g,Section section,List<Row> rows,String title,VisibleItemCounter counter){
        if(g==null||g.status()!=Status.OBSERVED||section==null||!g.key().equals(section.key())||!"更多活动".equals(section.title())
                ||section.origin()==null||!valid(section.titleBox(),g.width(),g.height())||!liveConfidence(section.confidence())||section.titleBox().left()>.2*g.width()||rows==null||rows.isEmpty()||rows.size()>128
                ||!SCOPED_TITLE.equals(title)||counter==null||counter.state()!=CounterState.HEADER_FRACTION||counter.headerFraction()==null)
            return selectedHold(FailureGate.INPUT,-1,List.of());
        for(Row row:rows)if(row==null||row.text()==null||row.text().length()>512||row.origin()==null||!valid(row.box(),g.width(),g.height())
                ||!Double.isFinite(row.confidence())||row.confidence()<0||row.confidence()>1)return selectedHold(FailureGate.ROW_FORMAT,-1,List.of());
        // Header-only pure checks are repeated here; no readonly batch/cast supplies business evidence.
        int headings=0,counters=0;boolean headingMatch=false;Row fraction=null,headingRow=null;
        int firstCard=g.height();for(Card c:g.cards())if(c.box().top()>=section.titleBox().bottom())firstCard=Math.min(firstCard,c.box().top());
        for(Row row:rows)if("更多活动".equals(row.text())){headings++;headingRow=row;headingMatch|=row.box().equals(section.titleBox())&&liveConfidence(row.confidence());}
        for(Row row:rows){if(row==headingRow)continue;if(row.box().equals(section.titleBox()))return selectedHold(FailureGate.HEADER_CONTEXT,-1,List.of());
            if(row.box().top()<section.titleBox().top()||row.box().bottom()>firstCard)continue;
            boolean right=row.box().left()>=.65*g.width(),possible=row.text().matches(".*[0-9０-９/／?].*");
            if(!right&&!possible)continue;counters++;fraction=row;
            if(!right||!liveConfidence(row.confidence())||Math.abs(row.box().centerY()-section.titleBox().centerY())>.6*Math.max(row.box().height(),section.titleBox().height()))return selectedHold(FailureGate.HEADER_CONTEXT,-1,List.of());
        }
        if(headings!=1||!headingMatch||counters!=1||fraction!=counter.headerFraction()||!liveConfidence(fraction.confidence()))return selectedHold(FailureGate.HEADER_CONTEXT,-1,List.of());
        String n=RewardRegionGate.normalizeNumberText(fraction.text());Matcher m=n==null?null:Pattern.compile("^([0-9]{1,4})/([0-9]{1,4})$").matcher(n.replaceAll("[ \\u00a0\\u2009\\u202f]",""));
        if(m==null||!m.matches())return selectedHold(FailureGate.COUNTER_FORMAT,-1,List.of());
        int numerator=Integer.parseInt(m.group(1)),denominator=Integer.parseInt(m.group(2));if(denominator==0||numerator>denominator)return selectedHold(FailureGate.COUNTER_RANGE,-1,List.of());
        List<TitleCard> items=new ArrayList<>();List<List<Row>> titles=new ArrayList<>();List<Row> adopted=new ArrayList<>();List<Box> unknown=new ArrayList<>();
        for(int index=0;index<g.cards().size();index++){
            Card card=g.cards().get(index);List<Row> local=new ArrayList<>();for(Row row:rows)if(card.box().overlaps(row.box())){
                if(!card.box().contains(row.box()))return selectedHold(FailureGate.ROW_CROSSES_CARD,index,unknown);local.add(row);}
            for(Card other:g.cards())if(other!=card&&other.box().overlaps(card.box()))return selectedHold(FailureGate.CARD_OVERLAP,index,unknown);
            List<TitleEvidence> matches=titleMatches(card,rows,title,independentlyCompleted(card,rows,index),independentlyCompletedFive(card,rows,index));
            if(matches.isEmpty()){
                for(Row row:local)if(matchesKnownTitleText(row.text(),title)||matchesKnownTitleFragment(row.text(),title))return selectedHold(FailureGate.TITLE_AMBIGUOUS,index,unknown);
                if(card.box().bottom()>section.titleBox().bottom()&&!card.fullyVisible())return selectedHold(FailureGate.CARD_PARTIAL,index,unknown);
                unknown.add(card.box());continue;
            }
            if(matches.size()!=1||!card.fullyVisible()||card.box().top()<=section.titleBox().bottom())return selectedHold(FailureGate.TITLE_AMBIGUOUS,index,unknown);
            TitleEvidence evidence=matches.get(0);if(!liveConfidence(evidence.minConfidence()))return selectedHold(FailureGate.TITLE_LOW_CONFIDENCE,index,unknown);
            for(int i=0;i<local.size();i++)for(int j=i+1;j<local.size();j++)if(local.get(i).box().overlaps(local.get(j).box()))return selectedHold(FailureGate.ROW_OVERLAP,index,unknown);
            for(Row row:local)if(!originalMember(evidence.originalParts(),row)&&(matchesKnownTitleText(row.text(),title)||matchesKnownTitleFragment(row.text(),title)))return selectedHold(FailureGate.TITLE_AMBIGUOUS,index,unknown);
            SkippedItem skip=completedItem(card,local,index);TitleCard item;
            if(skip!=null){SelectedLocal verified=selectedComplete(card,local,index,evidence,skip);if(verified.item()==null)return selectedHold(verified.failure(),index,unknown);item=verified.item();}
            else{
                // Invoke unchanged original pending rules with all original rows. Original-g
                // cross-card/partial/title ownership was checked above, never erased by this view.
                Geometry one=new Geometry(g.key(),g.width(),g.height(),Status.OBSERVED,List.of(card));
                TitleFamily pending=observeTitleFamilyPartitioned(one,section,rows,title,counter);
                if(pending.status()!=FamilyStatus.OBSERVED||pending.items().size()!=1||pending.items().get(0).status()!=VisibleItemStatus.CANDIDATE)
                    return selectedHold(pending.failure()==null?FailureGate.IDENTITY_BODY:pending.failure().gate(),index,unknown);
                item=pending.items().get(0);
            }
            items.add(item);titles.add(evidence.originalParts());adopted.addAll(evidence.originalParts());
        }
        if(items.size()!=2)return selectedHold(FailureGate.NO_TITLE_MATCH,-1,unknown);
        List<TitleEvidence> full=enumerateKnownTitleEvidence(rows,title);boolean[] used=new boolean[2];
        for(TitleEvidence evidence:full){int match=-1,count=0;for(int i=0;i<2;i++)if(sameOriginalParts(evidence.originalParts(),titles.get(i))){match=i;count++;}
            if(count!=1||used[match])return selectedHold(FailureGate.TITLE_AMBIGUOUS,-1,unknown);used[match]=true;}
        if(full.size()!=2||!used[0]||!used[1])return selectedHold(FailureGate.TITLE_AMBIGUOUS,-1,unknown);
        for(Row row:rows)if(!originalMember(adopted,row)&&matchesKnownTitleFragment(row.text(),title))return selectedHold(FailureGate.TITLE_AMBIGUOUS,-1,unknown);
        return new SelectedPendingFamily(items,new Fraction(numerator,denominator),null,-1,unknown);
    }
    private static SelectedLocal selectedComplete(Card card,List<Row> local,int index,TitleEvidence title,SkippedItem skip){
        double aspect=(double)card.box().height()/card.box().width();if(aspect<.10||aspect>.22)return new SelectedLocal(null,FailureGate.CARD_ASPECT);
        if(skip.displayedNumber()!=5)return new SelectedLocal(null,FailureGate.POINTS_VALUE);
        Row number=null;for(Row row:local)if(row.box().equals(skip.numberBox())){if(number!=null)return new SelectedLocal(null,FailureGate.POINTS_COUNT);number=row;}
        List<Row> body=new ArrayList<>();for(Row row:local){if(!liveConfidence(row.confidence()))return new SelectedLocal(null,FailureGate.PARTITION_LOW_CONFIDENCE);
            if(scopedConflict(row.text())||containsPlusSign(row.text()))return new SelectedLocal(null,FailureGate.TEMPLATE_CONFLICT);
            if(originalMember(title.originalParts(),row)||row==number)continue;
            if(row.box().top()<title.envelope().bottom()||row.box().right()>=skip.numberBox().left())return new SelectedLocal(null,FailureGate.RIGHT_EXTRA_ROW);body.add(row);}
        if(number==null||title.envelope().right()>=number.box().left())return new SelectedLocal(null,FailureGate.TITLE_AT_INDICATOR);
        String text=exactBodyRows(body);if(!body.isEmpty()&&!SCOPED_BODY_A.equals(text)&&!SCOPED_BODY_B.equals(text))return new SelectedLocal(null,FailureGate.IDENTITY_BODY);
        return new SelectedLocal(new TitleCard(VisibleItemStatus.ITEM_COMPLETE_SKIP,null,skip,title,number,body),null);
    }

    /** Independent raw business evidence. It is never created from CurrentReadBatch. */
    public static final class UniqueVisibleItem {
        private final int index,points;private final boolean complete;private final Box card,indicator;private final Row number;
        private final List<Row> identity;private final List<Row> local;
        private UniqueVisibleItem(int index,int points,boolean complete,Box card,Box indicator,Row number,List<Row> identity,List<Row> local){
            this.index=index;this.points=points;this.complete=complete;this.card=card;this.indicator=indicator;this.number=number;
            this.identity=List.copyOf(identity);this.local=List.copyOf(local);
        }
        public int frameIndex(){return index;}public int points(){return points;}public boolean complete(){return complete;}
        public Box cardBox(){return card;}public Box indicatorBox(){return indicator;}public Row number(){return number;}
        public List<Row> identityRows(){return identity;}public List<Row> originalLocalRows(){return local;}
        @Override public String toString(){return "UniqueVisibleItem[index="+index+",points="+points+",complete="+complete+",card="+card+"]";}
    }
    public enum VetoRule { NOT_APPLICABLE, FULL_LITERAL, STRICT_FRAGMENT, CATEGORY_LITERAL, RISK_PATTERN }
    public enum VetoCategory { NONE, TRIVIA_TITLE, TRIVIA_BODY_A, TRIVIA_BODY_B, QUOTE, PUZZLE, CHECKIN, RISK }
    public enum VetoAssembly { NOT_APPLICABLE, SINGLE_ROW, ADJACENT_ROWS, NONADJACENT_ROWS }
    public record VetoPart(Box box,double confidence) {}
    public record VetoDiagnostic(VetoRule rule,VetoCategory category,VetoAssembly assembly,List<VetoPart> parts) {
        public VetoDiagnostic {parts=List.copyOf(parts);if(parts.size()>6)throw new IllegalArgumentException("bounded veto parts");}
        public static VetoDiagnostic none(){return new VetoDiagnostic(VetoRule.NOT_APPLICABLE,VetoCategory.NONE,VetoAssembly.NOT_APPLICABLE,List.of());}
        public String summary(){return "vetoRule="+rule+" vetoCategory="+category+" vetoAssembly="+assembly+" vetoParts="+parts;}
        @Override public String toString(){return summary();}
    }
    private record VetoMatch(VetoRule rule,VetoCategory category) {}
    private static VetoDiagnostic veto(VetoMatch match,List<Row> rows){
        List<VetoPart> parts=new ArrayList<>();for(Row row:rows)parts.add(new VetoPart(row.box(),row.confidence()));
        boolean adjacent=true;for(int i=1;i<rows.size();i++)adjacent&=adjacentHeading(rows.get(i-1),rows.get(i));
        return new VetoDiagnostic(match.rule(),match.category(),rows.size()==1?VetoAssembly.SINGLE_ROW:adjacent?VetoAssembly.ADJACENT_ROWS:VetoAssembly.NONADJACENT_ROWS,parts);
    }
    public static final class UniquePendingProof {
        private final List<UniqueVisibleItem> items;private final Fraction fraction;private final FailureGate failure;private final int failureCard,pending,complete,known,unmodeledText;private final VetoDiagnostic veto;
        private UniquePendingProof(List<UniqueVisibleItem> items,Fraction fraction,FailureGate failure,int failureCard,int pending,int complete,int known){this(items,fraction,failure,failureCard,pending,complete,known,0);}
        private UniquePendingProof(List<UniqueVisibleItem> items,Fraction fraction,FailureGate failure,int failureCard,int pending,int complete,int known,int unmodeledText){this(items,fraction,failure,failureCard,pending,complete,known,unmodeledText,VetoDiagnostic.none());}
        private UniquePendingProof(List<UniqueVisibleItem> items,Fraction fraction,FailureGate failure,int failureCard,int pending,int complete,int known,int unmodeledText,VetoDiagnostic veto){
            this.veto=veto;
            this.unmodeledText=unmodeledText;
            this.items=List.copyOf(items);this.fraction=fraction;this.failure=failure;this.failureCard=failureCard;this.pending=pending;this.complete=complete;this.known=known;
        }
        public boolean accepted(){return failure==null&&items.size()==5;}
        public List<UniqueVisibleItem> items(){return items;}public Fraction fraction(){return fraction;}public FailureGate failure(){return failure;}
        public int failureCard(){return failureCard;}public int pending(){return pending;}public int complete(){return complete;}public int knownCompleted(){return known;}public int unmodeledText(){return unmodeledText;}public VetoDiagnostic veto(){return veto;}
        public String summary(){return "uniqueRawProof="+(accepted()?"STRICT_NATIVE":"HOLD")+" uniqueFirstGate="+failure+" failureCard="+failureCard
            +" visibleItems="+(accepted()?items.size():-1)+" pending="+pending+" ownNumberTicks="+complete+" consumedCandidateVeto="+(accepted()?"APPLIED":"NOT_QUALIFIED")
            +" "+veto.summary()+" unmodeledText="+unmodeledText+" candidateUniqueness=RECOGNIZED_VISIBLE_CARDS_ONLY completeRewardsFamily=false causalAttribution=UNPROVEN";}
        @Override public String toString(){return summary();}
    }
    private static UniquePendingProof uniqueHold(FailureGate gate,int index){return new UniquePendingProof(List.of(),null,gate,index,-1,-1,-1);}
    private static final List<String> CONSUMED_NAMES=List.of(SCOPED_TITLE,SCOPED_BODY_A,SCOPED_BODY_B,"Quote of the day","Complete this puzzle","签到","每日签到","Check in","Daily check-in");
    private static VetoCategory consumedCategory(int i){return switch(i){case 0->VetoCategory.TRIVIA_TITLE;case 1->VetoCategory.TRIVIA_BODY_A;case 2->VetoCategory.TRIVIA_BODY_B;case 3->VetoCategory.QUOTE;case 4->VetoCategory.PUZZLE;default->VetoCategory.CHECKIN;};}
    private static VetoMatch consumedMatch(String text){
        String actual=titleText(java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKC),true);if(actual==null)return null;
        actual=actual.replaceAll("^[\\p{P}\\p{S}]+|[\\p{P}\\p{S}]+$","");if(actual.isEmpty())return null;
        for(int i=0;i<CONSUMED_NAMES.size();i++){
            String expected=titleText(CONSUMED_NAMES.get(i),true),compared=actual;
            if(consumedCategory(i)==VetoCategory.CHECKIN){
                expected=expected.replaceAll("[\\u2010-\\u2015\\u2212-]"," ").replaceAll("[\\s\\p{Z}]+"," ");
                compared=compared.replaceAll("[\\u2010-\\u2015\\u2212-]"," ").replaceAll("[\\s\\p{Z}]+"," ");
            }
            boolean full=expected.matches(".*[\\u3400-\\u9fff].*")?compared.contains(expected)
                :Pattern.compile("(?<![\\p{L}\\p{N}])"+Pattern.quote(expected)+"(?![\\p{L}\\p{N}])").matcher(compared).find();
            if(full)return new VetoMatch(VetoRule.FULL_LITERAL,consumedCategory(i));
        }
        // Exact consumed-category labels only. Ordinary verbs/short words are not identities.
        String category=actual.replaceAll("[\\u2010-\\u2015\\u2212-]"," ").replaceAll("[\\s\\p{Z}]+"," ").trim();
        if(category.equals("quote"))return new VetoMatch(VetoRule.CATEGORY_LITERAL,VetoCategory.QUOTE);
        if(category.equals("puzzle"))return new VetoMatch(VetoRule.CATEGORY_LITERAL,VetoCategory.PUZZLE);
        if(category.equals("签到")||category.equals("check in"))return new VetoMatch(VetoRule.CATEGORY_LITERAL,VetoCategory.CHECKIN);
        for(int i=0;i<CONSUMED_NAMES.size();i++){
            // A known BODY fragment needs at least two consecutive words. A common standalone
            // body word, regardless of length, is not a consumed task identity.
            if((i==1||i==2)&&actual.indexOf(' ')<0)continue;
            if(matchesKnownTitleFragment(actual,CONSUMED_NAMES.get(i)))return new VetoMatch(VetoRule.STRICT_FRAGMENT,consumedCategory(i));
        }
        return null;
    }
    private static boolean consumedText(String text){return consumedMatch(text)!=null;}
    private static UniquePendingProof uniqueHold(FailureGate gate,int index,VetoDiagnostic diagnostic){return new UniquePendingProof(List.of(),null,gate,index,-1,-1,-1,0,diagnostic);}
    private static boolean uniqueRisk(String text){
        String s=java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT).replaceAll("[\\u2010-\\u2015\\u2212-]"," ").replaceAll("[\\s\\p{Z}]+"," ");
        if(scopedConflict(text))return true;
        for(String t:List.of("登录","登入","注册","订阅","付款","支付","购买","充值","兑换","安装","下载","授权","隐私","分享","发送","转发","邀请","绑定账户","银行卡","信用卡"))if(s.contains(t))return true;
        return s.matches(".*\\b(?:buy|pay|purchase|checkout|subscribe|subscription|install|download|share|send|invite|login|signup|email)\\b.*")
            ||s.contains("log in")||s.contains("sign up")||s.contains("authorize")||s.contains("authorise")||s.contains("permission")||s.contains("credit card")||s.contains("sign in")||s.contains("register")||s.contains("registration")||s.matches(".*\\b(?:verify|link|connect)\\s+(?:(?:your|the)\\s+)?account\\b.*")||s.contains("account verification")
            ||s.matches(".*(?:[$€£¥￥][ \\u00a0]*[0-9０-９]|[0-9０-９]+(?:[.,][0-9０-９]+)?[ \\u00a0]*(?:usd|eur|gbp|cny|rmb|dollars?|euros?|pounds?|美元|欧元|英镑|元)).*");
    }
    private static VetoDiagnostic textVeto(String text,List<Row> parts,boolean consumed){
        VetoMatch match=consumed?consumedMatch(text):null;if(match!=null)return veto(match,parts);
        return uniqueRisk(text)?veto(new VetoMatch(VetoRule.RISK_PATTERN,VetoCategory.RISK),parts):null;
    }
    private static VetoDiagnostic compoundVeto(List<Row> rows,boolean consumed){
        StringBuilder spaced=new StringBuilder(),joined=new StringBuilder();
        for(Row row:rows){if(spaced.length()>0)spaced.append(' ');spaced.append(row.text());joined.append(row.text());}
        VetoDiagnostic hit=textVeto(spaced.toString(),rows,consumed);return hit!=null?hit:textVeto(joined.toString(),rows,consumed);
    }
    private static VetoDiagnostic sequenceVeto(List<Row> rows,boolean consumed){
        List<Row> ordered=new ArrayList<>(rows);ordered.sort(Comparator.comparingInt((Row row)->row.box().top()).thenComparingInt(row->row.box().left()));
        for(int i=0;i<ordered.size();i++)for(int j=i+1;j<ordered.size();j++){
            VetoDiagnostic hit=compoundVeto(List.of(ordered.get(i),ordered.get(j)),consumed);if(hit!=null)return hit;
        }
        for(int i=0;i<ordered.size();i++)for(int end=i;end<ordered.size()&&end<i+6;end++){
            VetoDiagnostic hit=compoundVeto(ordered.subList(i,end+1),consumed);if(hit!=null)return hit;
        }return null;
    }
    private static Fraction uniqueHeader(Geometry g,Section section,List<Row> rows,VisibleItemCounter counter){
        if(g==null||g.status()!=Status.OBSERVED||section==null||!g.key().equals(section.key())||!"更多活动".equals(section.title())
            ||section.origin()==null||!valid(section.titleBox(),g.width(),g.height())||section.titleBox().left()>.2*g.width()||!liveConfidence(section.confidence())
            ||rows==null||rows.isEmpty()||rows.size()>128||counter==null||counter.state()!=CounterState.HEADER_FRACTION||counter.headerFraction()==null)return null;
        for(Row row:rows)if(row==null||row.text()==null||row.text().length()>512||row.origin()==null||!valid(row.box(),g.width(),g.height())||!Double.isFinite(row.confidence())||row.confidence()<0||row.confidence()>1)return null;
        int headings=0,count=0,first=g.height();Row heading=null,fraction=null;
        for(Card c:g.cards())if(c.box().top()>=section.titleBox().bottom())first=Math.min(first,c.box().top());
        for(Row row:rows)if("更多活动".equals(row.text())){heading=row;headings++;}
        if(headings!=1||!heading.box().equals(section.titleBox())||!liveConfidence(heading.confidence()))return null;
        for(Row row:rows){if(row==heading)continue;if(row.box().equals(section.titleBox()))return null;
            if(row.box().top()<section.titleBox().top()||row.box().bottom()>first)continue;
            boolean right=row.box().left()>=.65*g.width(),possible=row.text().matches(".*[0-9０-９/／?].*");if(!right&&!possible)continue;
            count++;fraction=row;if(!right||!liveConfidence(row.confidence())||Math.abs(row.box().centerY()-heading.box().centerY())>.6*Math.max(row.box().height(),heading.box().height()))return null;
        }
        if(count!=1||fraction!=counter.headerFraction())return null;String n=RewardRegionGate.normalizeNumberText(fraction.text());
        Matcher m=n==null?null:Pattern.compile("^([0-9]{1,4})/([0-9]{1,4})$").matcher(n.replaceAll("[ \\u00a0\\u2009\\u202f]",""));if(m==null||!m.matches())return null;
        int numerator=Integer.parseInt(m.group(1)),denominator=Integer.parseInt(m.group(2));return denominator>0&&numerator<=denominator?new Fraction(numerator,denominator):null;
    }
    /** Fresh raw More viewport only. Completed items need their own number/tick, never a name. */
    public static UniquePendingProof observeUniquePending(Geometry g,Section section,List<Row> rows,VisibleItemCounter counter){
        Fraction fraction=uniqueHeader(g,section,rows,counter);if(fraction==null)return uniqueHold(FailureGate.HEADER_CONTEXT,-1);
        if(g.cards().size()!=5)return uniqueHold(FailureGate.CARD_PARTIAL,-1);
        List<UniqueVisibleItem> items=new ArrayList<>();int pending=0,complete=0,last=section.titleBox().bottom();
        for(int index=0;index<g.cards().size();index++){
            Card c=g.cards().get(index);if(!c.fullyVisible()||c.box().top()<=section.titleBox().bottom())return uniqueHold(FailureGate.CARD_PARTIAL,index);
            for(Card other:g.cards())if(other!=c&&other.box().overlaps(c.box()))return uniqueHold(FailureGate.CARD_OVERLAP,index);
            List<Row> local=new ArrayList<>();for(Row row:rows)if(c.box().overlaps(row.box())){if(!c.box().contains(row.box()))return uniqueHold(FailureGate.ROW_CROSSES_CARD,index);local.add(row);}
            if(local.isEmpty()||local.size()>16)return uniqueHold(FailureGate.ROW_FORMAT,index);
            for(int i=0;i<local.size();i++)for(int j=i+1;j<local.size();j++)if(local.get(i).box().overlaps(local.get(j).box()))return uniqueHold(FailureGate.ROW_OVERLAP,index);
            SkippedItem skip=completedItem(c,local,index);UniqueVisibleItem item;
            if(skip!=null){
                Row number=null;List<Row> identity=new ArrayList<>();
                // Independent number/tick completion, not a before/after compact identity relation.
                for(Row row:local){if(!liveConfidence(row.confidence()))return uniqueHold(FailureGate.ROW_LOW_CONFIDENCE,index);
                    if(uniqueRisk(row.text()))return uniqueHold(FailureGate.EXTRA_STEP_RISK,index,veto(new VetoMatch(VetoRule.RISK_PATTERN,VetoCategory.RISK),List.of(row)));
                    if(containsPlusSign(row.text()))return uniqueHold(FailureGate.EXTRA_STEP_RISK,index);
                    String n=RewardRegionGate.normalizeNumberText(row.text());boolean numeric=n!=null&&n.matches("[0-9]{1,4}");
                    if(row.box().equals(skip.numberBox())){if(number!=null)return uniqueHold(FailureGate.POINTS_COUNT,index);number=row;}
                    else{if(numeric||row.box().right()>=skip.numberBox().left())return uniqueHold(FailureGate.RIGHT_EXTRA_ROW,index);identity.add(row);}
                }
                if(number==null)return uniqueHold(FailureGate.MISSING_NUMBER,index);
                VetoDiagnostic completedRisk=sequenceVeto(identity,false);if(completedRisk!=null)return uniqueHold(FailureGate.EXTRA_STEP_RISK,index,completedRisk);
                item=new UniqueVisibleItem(index,skip.displayedNumber(),true,c.box(),skip.tick().diskBox(),number,identity,local);complete++;
            }else{
                if(!c.tickMarks().isEmpty())return uniqueHold(FailureGate.TICK_CONFLICT,index);
                LocalPartition part=partitionRows(c,local);if(!part.unclassified().isEmpty())return uniqueHold(FailureGate.PARTITION_CROSSING,index);
                for(Row row:local){VetoMatch match=consumedMatch(row.text());if(match!=null)return uniqueHold(FailureGate.CONSUMED_CATEGORY,index,veto(match,List.of(row)));
                    if(!liveConfidence(row.confidence()))return uniqueHold(FailureGate.ROW_LOW_CONFIDENCE,index);
                    if(uniqueRisk(row.text()))return uniqueHold(FailureGate.EXTRA_STEP_RISK,index,veto(new VetoMatch(VetoRule.RISK_PATTERN,VetoCategory.RISK),List.of(row)));
                    if(containsPlusSign(row.text())&&!originalMember(part.r(),row))return uniqueHold(FailureGate.EXTRA_STEP_RISK,index);}
                VetoDiagnostic combination=sequenceVeto(local,true);if(combination!=null)return uniqueHold(combination.category()==VetoCategory.RISK?FailureGate.EXTRA_STEP_RISK:FailureGate.CONSUMED_CATEGORY,index,combination);
                double aspect=(double)c.box().width()/c.box().height();if(aspect<2||aspect>4.5)return uniqueHold(FailureGate.CARD_ASPECT,index);
                if(c.badges().size()!=1)return uniqueHold(FailureGate.BADGE_COUNT,index);
                Box b=c.box(),badge=c.badges().get(0).box();double x=(badge.left()-b.left())/(double)b.width(),w=badge.width()/(double)b.width(),h=badge.height()/(double)b.height(),y=(badge.centerY()-b.top())/b.height();
                if(!b.contains(badge))return uniqueHold(FailureGate.BADGE_CONTAINMENT,index);if(x<.70)return uniqueHold(FailureGate.BADGE_LEFT,index);
                if(w<.16||w>.26)return uniqueHold(FailureGate.BADGE_WIDTH,index);if(h<.24||h>.51)return uniqueHold(FailureGate.BADGE_HEIGHT,index);if(y<.38||y>.62)return uniqueHold(FailureGate.BADGE_ALIGNMENT,index);
                if(part.r().size()!=1)return uniqueHold(FailureGate.RIGHT_EXTRA_ROW,index);Row number=part.r().get(0);
                if(!liveConfidence(number.confidence()))return uniqueHold(FailureGate.ROW_LOW_CONFIDENCE,index);
                if(points(number.text())==null||points(number.text())!=5)return uniqueHold(FailureGate.POINTS_VALUE,index);
                if(!new Box(badge.left()+2,badge.top()+2,badge.right()-2,badge.bottom()-2).contains(number.box()))return uniqueHold(FailureGate.POINTS_OUTSIDE_SAFE,index);
                if(part.c().isEmpty())return uniqueHold(FailureGate.NO_TITLE_MATCH,index);
                boolean heading=false;for(Row row:part.c())if(liveConfidence(row.confidence())&&row.box().top()<b.top()+.5*b.height()){
                    int letters=0;for(int at=0;at<row.text().length();){int cp=row.text().codePointAt(at);at+=Character.charCount(cp);if(Character.isLetter(cp))letters++;}
                    if(letters>=2)heading=true;
                }if(!heading)return uniqueHold(FailureGate.NO_TITLE_MATCH,index);
                for(Row row:part.c())if(!liveConfidence(row.confidence())||row.box().right()>=badge.left()||RewardRegionGate.visibleCodePoints(row.text())<1)
                    return uniqueHold(FailureGate.PARTITION_LOW_CONFIDENCE,index);
                item=new UniqueVisibleItem(index,5,false,c.box(),badge,number,part.c(),local);pending++;
            }
            items.add(item);last=Math.max(last,c.box().bottom());
        }
        // High-quality, non-candidate card-external text remains explicitly unmodeled.
        // It is not declared footer/More content or evidence of exhaustive page detection.
        List<Row> unmodeled=new ArrayList<>();Box band=new Box(0,section.titleBox().bottom(),g.width(),g.height());
        for(Row row:rows)if(band.overlaps(row.box())){
            if(row==counter.headerFraction())continue;
            int owners=0;for(UniqueVisibleItem item:items)if(item.cardBox().contains(row.box()))owners++;
            if(owners==1)continue;if(owners>1||row.box().top()<band.top())return uniqueHold(FailureGate.UNEXPLAINED_ROW,-1);
            if(!liveConfidence(row.confidence())||RewardRegionGate.visibleCodePoints(row.text())<2)return uniqueHold(FailureGate.ROW_LOW_CONFIDENCE,-1);
            int letters=0;boolean digit=false;for(int at=0;at<row.text().length();){int cp=row.text().codePointAt(at);at+=Character.charCount(cp);if(Character.isLetter(cp))letters++;if(Character.isDigit(cp))digit=true;}
            if(digit||letters<2||containsPlusSign(row.text()))return uniqueHold(FailureGate.UNEXPLAINED_ROW,-1);
            VetoDiagnostic external=textVeto(row.text(),List.of(row),true);if(external!=null)return uniqueHold(FailureGate.UNEXPLAINED_ROW,-1,external);
            for(Row other:rows)if(other!=row&&other!=counter.headerFraction()){
                if(row.box().overlaps(other.box()))return uniqueHold(FailureGate.ROW_OVERLAP,-1);
                int gap=Math.max(0,Math.max(row.box().top(),other.box().top())-Math.min(row.box().bottom(),other.box().bottom()));
                boolean nearby=gap<=1.5*Math.max(row.box().height(),other.box().height())&&row.box().left()<other.box().right()&&row.box().right()>other.box().left();
                if(nearby){VetoDiagnostic nearbyHit=compoundVeto(List.of(row,other),true);if(nearbyHit==null)nearbyHit=compoundVeto(List.of(other,row),true);
                    if(nearbyHit!=null)return uniqueHold(FailureGate.UNEXPLAINED_ROW,-1,nearbyHit);
                }
            }
            unmodeled.add(row);
        }
        VetoDiagnostic outsideCombination=sequenceVeto(unmodeled,true);if(outsideCombination!=null)return uniqueHold(FailureGate.UNEXPLAINED_ROW,-1,outsideCombination);
        if(pending>1)return uniqueHold(FailureGate.PENDING_COUNT,-1);
        if(pending==1&&fraction.numerator()==fraction.denominator()||pending==0&&fraction.numerator()!=fraction.denominator())return uniqueHold(FailureGate.COUNTER_RANGE,-1);
        return new UniquePendingProof(items,fraction,null,-1,pending,complete,0,unmodeled.size());
    }

    public record Preview(String status,Candidate hypotheticalTarget,String actor,int executedActions,
                          boolean claimed,boolean credited,boolean requiresIndependentNewCounter) {}
    /** Explicit card selection is required even with one candidate. No coordinates are ever sent. */
    public static final class Session {
        private Result original;private boolean canceled,consumed;
        public Session(Result result){original=result;}
        public void cancel(){canceled=true;original=null;}
        public Preview previewExplicitly(Result current,String selectedCardId){
            String stop=canceled?"CANCELED":consumed?"ALREADY_PREVIEWED":
                original==null||original.status!=Status.CANDIDATES?"BLOCKED":
                original!=current?"STALE":null;
            if(stop!=null){if("STALE".equals(stop))original=null;return new Preview(stop,null,"UNBOUND",0,false,false,true);}
            Candidate target=null;
            for(Candidate c:original.candidates)if(c.visualCardId().equals(selectedCardId))target=c;
            if(target==null)return new Preview("EXPLICIT_UNIQUE_SELECTION_REQUIRED",null,"UNBOUND",0,false,false,true);
            consumed=true;
            return new Preview("HYPOTHETICAL_CROP_TARGET_ONLY",target,"UNBOUND",0,false,false,true);
        }
    }
}


