package com.aliothmoon.maafw.rewards;

import com.aliothmoon.maafw.rewards.RewardRegionGate.Key;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Origin;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Row;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Offline check-in material observer, calibrated on the supplied section crops, not a trained
 * model. GRAY8 surface boundaries and current-style thin rings are visual evidence only.
 * No Android, capture, input, network, account, clock or permission interface exists here.
 * Header/day labels and numbers remain explicitly attributed OCR or manual evidence.
 */
public final class DailyCheckInRegion {
    private DailyCheckInRegion() {}
    public enum Status { OBSERVED, INVALID_INPUT, CANCELED, TOO_MANY_COMPONENTS, NO_BOUNDARY,
        NO_UNIQUE_SECTION, INCOMPLETE_REGION, BAD_OCR, BAD_DAY_SLOTS, AMBIGUOUS_CIRCLE,
        NO_CURRENT_TARGET, BAD_NUMBER, CANDIDATE }
    public enum SlotState { CURRENT_STYLE_RING, LOCAL_TICK, NO_CURRENT_MARK }
    public enum Outcome { PREVIEW_ONLY, CANCELED, ALREADY_CONSUMED, BLOCKED,
        REPEATED_OR_STALE_IMAGE_STOP, SCOPE_OR_DAY_CHANGE_STOP, NO_LOCAL_COMPLETION_STOP,
        OBSERVED_SAME_SLOT_MARKED_COMPLETE_UNATTRIBUTED }
    /** Similarity is bounded one-cell shape coverage, not OCR or semantic confidence. */
    public record Tick(Box disk, Box glyph, double similarity) {}
    public record Surface(Box box, boolean complete) {}
    private record Component(Box box, int area) {
        double fill() { return (double)area / ((long)box.width()*box.height()); }
    }
    public static final class Geometry {
        private final Key key;
        private final int width, height;
        private final Status status;
        private final String pixelHash;
        private final List<Surface> surfaces;
        private final List<Box> rings;
        private final List<Tick> ticks;
        private Geometry(Key key,int w,int h,Status status,String hash,List<Surface> surfaces,
                         List<Box> rings,List<Tick> ticks) {
            this.key=key; width=w; height=h; this.status=status; pixelHash=hash;
            this.surfaces=List.copyOf(surfaces); this.rings=List.copyOf(rings); this.ticks=List.copyOf(ticks);
        }
        public Key key(){return key;} public int width(){return width;} public int height(){return height;}
        public Status status(){return status;} public String pixelHash(){return pixelHash;}
        public List<Surface> surfaces(){return surfaces;} public List<Box> rings(){return rings;}
        public List<Tick> ticks(){return ticks;}
    }
    private static boolean valid(Box b,int w,int h) {
        return b!=null&&b.left()>=0&&b.top()>=0&&b.right()<=w&&b.bottom()<=h
            &&b.right()>b.left()&&b.bottom()>b.top();
    }
    private static boolean confidence(double c){return Double.isFinite(c)&&c>=.85&&c<=1;}
    private static boolean mask(int value, int threshold){return threshold==245?value>=245:value<threshold;}
    /** Scratch is sized to the ROI, wiped on every exit, and never retained with observations. */
    private static List<Component> components(byte[] gray,int w,Box roi,int threshold,BooleanSupplier cancel) {
        int rw=roi.width(),rh=roi.height(),area=rw*rh;
        boolean[] seen=new boolean[area]; int[] queue=new int[area];
        List<Component> out=new ArrayList<>();
        try {
            for(int seed=0;seed<area;seed++) {
                if((seed&511)==0&&cancel.getAsBoolean())return null;
                int x=seed%rw,y=seed/rw;
                if(seen[seed]||!mask(gray[(roi.top()+y)*w+roi.left()+x]&255,threshold))continue;
                int head=0,tail=0,minX=x,maxX=x,minY=y,maxY=y;
                seen[seed]=true;queue[tail++]=seed;
                while(head<tail) {
                    if((head&1023)==0&&cancel.getAsBoolean())return null;
                    int p=queue[head++],px=p%rw,py=p/rw;
                    minX=Math.min(minX,px);maxX=Math.max(maxX,px);minY=Math.min(minY,py);maxY=Math.max(maxY,py);
                    for(int d=0;d<4;d++) {
                        int nx=px+(d==0?-1:d==1?1:0),ny=py+(d==2?-1:d==3?1:0);
                        if(nx<0||ny<0||nx>=rw||ny>=rh)continue;
                        int n=ny*rw+nx;
                        if(!seen[n]&&mask(gray[(roi.top()+ny)*w+roi.left()+nx]&255,threshold)){
                            seen[n]=true;queue[tail++]=n;
                        }
                    }
                }
                Box b=new Box(roi.left()+minX,roi.top()+minY,roi.left()+maxX+1,roi.top()+maxY+1);
                boolean eligible=threshold==245?b.width()>.70*w&&b.height()>.22*w&&b.height()<.45*w
                    :b.width()>.035*w&&b.width()<.14*w&&b.height()>.035*w&&b.height()<.14*w;
                if(eligible){out.add(new Component(b,tail));if(out.size()>64)return out;}
            }
            return out;
        } finally {Arrays.fill(seen,false);Arrays.fill(queue,0);}
    }
    // Literal local check shape reference, not learned parameters or a claim of semantic credit.
    private static final String[] CHECK={"00000000000111","00000000001110","00000000011100",
        "10000000111000","11100001110000","01110011100000","00111111000000","00011110000000","00001100000000"};
    private static Tick tick(byte[] gray,int w,Box disk,BooleanSupplier cancel) {
        int rw=disk.width(),rh=disk.height();
        boolean[] seen=new boolean[rw*rh];int[] queue=new int[rw*rh];Box glyph=null;
        try {
            for(int seed=0;seed<seen.length;seed++) {
                int x=seed%rw,y=seed/rw;
                if(seen[seed]||(gray[(disk.top()+y)*w+disk.left()+x]&255)<230)continue;
                int head=0,tail=0,minX=x,maxX=x,minY=y,maxY=y;boolean edge=false;
                seen[seed]=true;queue[tail++]=seed;
                while(head<tail) {
                    if((head&255)==0&&cancel.getAsBoolean())return null;
                    int p=queue[head++],px=p%rw,py=p/rw;
                    minX=Math.min(minX,px);maxX=Math.max(maxX,px);minY=Math.min(minY,py);maxY=Math.max(maxY,py);
                    edge|=px==0||py==0||px==rw-1||py==rh-1;
                    for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++) {
                        int nx=px+dx,ny=py+dy;
                        if(nx<0||ny<0||nx>=rw||ny>=rh||(dx==0&&dy==0))continue;
                        int n=ny*rw+nx;
                        if(!seen[n]&&(gray[(disk.top()+ny)*w+disk.left()+nx]&255)>=230){seen[n]=true;queue[tail++]=n;}
                    }
                }
                if(!edge&&tail>=4) {
                    if(glyph!=null)return null;
                    glyph=new Box(disk.left()+minX,disk.top()+minY,disk.left()+maxX+1,disk.top()+maxY+1);
                }
            }
            if(glyph==null||glyph.width()<.25*rw||glyph.width()>.75*rw
                ||glyph.height()<.20*rh||glyph.height()>.65*rh)return null;
            double score=checkSimilarity(gray,w,glyph);
            return score>=.94?new Tick(disk,glyph,score):null;
        } finally {Arrays.fill(seen,false);Arrays.fill(queue,0);}
    }
    /** Minimum bidirectional coverage within ONE normalized cell, not OCR confidence.
     * Small antialiased glyphs shift by one cell. Directional arm/valley constraints, bounded
     * ink count and a direct-overlap floor reject X, V, slash, box and round-hole substitutes. */
    private static double checkSimilarity(byte[] gray,int width,Box glyph) {
        boolean[] actual=new boolean[126];
        try {
            int intersection=0,union=0,actualCount=0,expectedCount=0;
            boolean rightTip=false,leftArm=false,valley=false,upperLeft=false;
            for(int y=0;y<9;y++)for(int x=0;x<14;x++) {
                int px=glyph.left()+Math.min(glyph.width()-1,(int)((x+.5)*glyph.width()/14));
                int py=glyph.top()+Math.min(glyph.height()-1,(int)((y+.5)*glyph.height()/9));
                boolean a=(gray[py*width+px]&255)>=230,b=CHECK[y].charAt(x)=='1';
                actual[y*14+x]=a;
                if(a){actualCount++;rightTip|=x>=10&&y<3;leftArm|=x<=3&&y>=3&&y<=5;
                    valley|=x>=3&&x<=9&&y>=6;upperLeft|=x<=4&&y<=1;}
                if(b)expectedCount++;if(a&&b)intersection++;if(a||b)union++;
            }
            if(!rightTip||!leftArm||!valley||upperLeft||actualCount<.60*expectedCount
                ||actualCount>1.35*expectedCount||union==0||(double)intersection/union<.45)return 0;
            int supportedActual=0,supportedExpected=0;
            for(int y=0;y<9;y++)for(int x=0;x<14;x++) {
                boolean nearActual=false,nearExpected=false;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++) {
                    int nx=x+dx,ny=y+dy;if(nx<0||ny<0||nx>=14||ny>=9)continue;
                    nearActual|=actual[ny*14+nx];nearExpected|=CHECK[ny].charAt(nx)=='1';
                }
                if(actual[y*14+x]&&nearExpected)supportedActual++;
                if(CHECK[y].charAt(x)=='1'&&nearActual)supportedExpected++;
            }
            return Math.min((double)supportedActual/actualCount,(double)supportedExpected/expectedCount);
        } finally {Arrays.fill(actual,false);}
    }
    /** A square or arbitrary thin connected mark is not a circular indicator. */
    private static boolean ellipticalOutline(byte[] gray,int width,Box box) {
        int covered=0;
        for(int angle=0;angle<32;angle++) {
            double theta=angle*(2*Math.PI/32);boolean hit=false;
            for(int step=0;step<=9&&!hit;step++) {
                double radius=.84+step*.025;
                int x=(int)Math.floor(box.centerX()+Math.cos(theta)*box.width()*.5*radius);
                int y=(int)Math.floor(box.centerY()+Math.sin(theta)*box.height()*.5*radius);
                if(x>=box.left()&&x<box.right()&&y>=box.top()&&y<box.bottom()
                    &&(gray[y*width+x]&255)<195)hit=true;
            }
            if(hit)covered++;
        }
        return covered>=26;
    }
    private static Geometry failure(Key k,int w,int h,Status s){return new Geometry(k,w,h,s,"",List.of(),List.of(),List.of());}
    private static String hash(byte[] bytes) {
        byte[] digest;
        try {digest=MessageDigest.getInstance("SHA-256").digest(bytes);}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        try {StringBuilder b=new StringBuilder(64);for(byte v:digest){b.append(Character.forDigit((v>>>4)&15,16));b.append(Character.forDigit(v&15,16));}return b.toString();}
        finally {Arrays.fill(digest,(byte)0);}
    }
    public static Geometry observeGray8(Key key,byte[] pixels,int width,int height,BooleanSupplier canceled) {
        if(key==null||width<32||height<32||width>4096||height>8192||(long)width*height>4_194_304
            ||pixels==null||pixels.length!=(long)width*height)return failure(key,width,height,Status.INVALID_INPUT);
        BooleanSupplier cancel=canceled==null?()->false:canceled;
        if(cancel.getAsBoolean())return failure(key,width,height,Status.CANCELED);
        byte[] gray=Arrays.copyOf(pixels,pixels.length);
        try {
            List<Component> white=components(gray,width,new Box(0,0,width,height),245,cancel);
            if(white==null)return failure(key,width,height,Status.CANCELED);
            if(white.size()>64)return failure(key,width,height,Status.TOO_MANY_COMPONENTS);
            List<Surface> surfaces=new ArrayList<>();List<Box> rings=new ArrayList<>();List<Tick> ticks=new ArrayList<>();
            for(Component c:white)if(c.fill()>.55){Box b=c.box();surfaces.add(new Surface(b,b.left()>0&&b.top()>0&&b.right()<width&&b.bottom()<height));}
            if(surfaces.isEmpty())return failure(key,width,height,Status.NO_BOUNDARY);
            for(Surface surface:surfaces) {
                List<Component> dark=components(gray,width,surface.box(),195,cancel);
                if(dark==null)return failure(key,width,height,Status.CANCELED);
                if(dark.size()>64)return failure(key,width,height,Status.TOO_MANY_COMPONENTS);
                for(Component c:dark) {
                    Box b=c.box();double aspect=(double)b.width()/b.height();
                    if(aspect<.85||aspect>1.15)continue;
                    if(b.width()>.075*width&&b.width()<.13*width&&b.height()>.075*width&&b.height()<.13*width
                        &&c.fill()>=.04&&c.fill()<=.25&&ellipticalOutline(gray,width,b))rings.add(b);
                    
                }
                List<Component> disks=components(gray,width,surface.box(),205,cancel);
                if(disks==null)return failure(key,width,height,Status.CANCELED);
                if(disks.size()>64)return failure(key,width,height,Status.TOO_MANY_COMPONENTS);
                for(Component c:disks) {
                    Box b=c.box();double aspect=(double)b.width()/b.height();
                    if(b.width()>.04*width&&b.width()<.08*width&&b.height()>.04*width&&b.height()<.08*width
                        &&aspect>=.85&&aspect<=1.15&&c.fill()>=.60&&c.fill()<=.90) {
                        Tick t=tick(gray,width,b,cancel);if(t!=null)ticks.add(t);
                    }
                }
            }
            if(cancel.getAsBoolean())return failure(key,width,height,Status.CANCELED);
            return new Geometry(key,width,height,Status.OBSERVED,hash(gray),surfaces,rings,ticks);
        } finally {Arrays.fill(gray,(byte)0);}
    }
    public record TextEvidence(Key key,List<Row> rows) {
        public TextEvidence {rows=rows==null?null:java.util.Collections.unmodifiableList(new ArrayList<>(rows));}
    }
    public record Slot(int day,Box bounds,Box label,Origin labelOrigin,SlotState state,Box ring,Tick tick) {}
    public record Candidate(int day,Box region,Box slot,Box circle,Box numeral,int displayedPoints,
                            Origin numberOrigin,Origin titleOrigin,Origin dayLabelOrigin,double cropX,double cropY) {
        public boolean calendarTodayProven(){return false;} public boolean verifiedClickable(){return false;}
        public String coordinateSpace(){return "CROP_LOCAL_ONLY";}
    }
    public static final class Result {
        private final Geometry geometry;private final Status status;private final Box region;
        private final List<Slot> slots;private final Candidate candidate;
        private Result(Geometry g,Status s,Box region,List<Slot> slots,Candidate c){geometry=g;status=s;this.region=region;this.slots=List.copyOf(slots);candidate=c;}
        public Geometry geometry(){return geometry;}public Status status(){return status;}public Box region(){return region;}
        public List<Slot> slots(){return slots;}public Candidate candidate(){return candidate;}
        public boolean credited(){return false;}
    }
    private static Result result(Geometry g,Status s){return new Result(g,s,null,List.of(),null);}
    private static int day(String s) {
        if(s==null)return 0;
        for(int i=1;i<=7;i++)if(s.equals("第"+i+"天")||s.equals("Day "+i)||s.equals("Day"+i))return i;
        return 0;
    }
    private static Integer points(String s) {
        String n=RewardRegionGate.normalizeNumberText(s);
        if(n==null||!n.matches("[0-9]{1,4}"))return null;
        int v=Integer.parseInt(n);return v>0?v:null;
    }
    /** Scalar crop-local visual preview only. This is deliberately not a Candidate or a Slot,
     * and cannot be passed to Session/compare. Origins describe evidence, not authentication.
     * Auxiliary rows are counted, not exposed as text, and caller rows/boxes remain unchanged. */
    public static final class VisualTargetResult {
        private final Geometry geometry;private final Status status;
        private final Box panel,ring,numeral;
        private final int displayedPoints,auxiliaryRows;
        private final Origin titleOrigin,numberOrigin;
        private VisualTargetResult(Geometry g,Status s,Box panel,Box ring,Row title,Row number,
                                   int points,int auxiliaryRows) {
            geometry=g;status=s;this.panel=panel;this.ring=ring;
            numeral=number==null?null:number.box();displayedPoints=points;
            titleOrigin=title==null?null:title.origin();numberOrigin=number==null?null:number.origin();
            this.auxiliaryRows=auxiliaryRows;
        }
        public Geometry geometry(){return geometry;}public Status status(){return status;}
        public Box panel(){return panel;}public Box ring(){return ring;}public Box numeral(){return numeral;}
        public int displayedPoints(){return displayedPoints;}public int auxiliaryRows(){return auxiliaryRows;}
        public Origin titleOrigin(){return titleOrigin;}public Origin numberOrigin(){return numberOrigin;}
        public double cropX(){return numeral==null?Double.NaN:numeral.centerX();}
        public double cropY(){return numeral==null?Double.NaN:numeral.centerY();}
        public String coordinateSpace(){return "CROP_LOCAL_ONLY";}
        public boolean previewOnly(){return true;}public boolean calendarTodayProven(){return false;}
        public boolean verifiedClickable(){return false;}public boolean credited(){return false;}
        public int hypotheticalClicks(){return 0;}public int realExecutions(){return 0;}
    }
    private static VisualTargetResult visualResult(Geometry g,Status s) {
        return new VisualTargetResult(g,s,null,null,null,null,0,0);
    }
    private static Box visualTitleZone(Box panel) {
        return new Box(panel.left(),panel.top(),panel.left()+(int)(.25*panel.width()),
            panel.top()+(int)(.40*panel.height()));
    }
    /** Forbidden state/section evidence is not made harmless by a low OCR score. */
    private static boolean visualConflict(String text) {
        String s=text.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\u00a0\\u2009\\u202f_-]+","");
        for(String marker:List.of("锁","鎖","明天","明日","登录","登入","登錄","不可用",
            "无法领取","無法領取","不可领取","不可領取","已领取","已領取","领取完成","領取完成",
            "已签入","已簽入","已签到","已簽到","已完成","每日活动","lock","tomorrow","login",
            "signin","notavailable","unavailable","claimcomplete","claimed","completed"))
            if(s.contains(marker))return true;
        return false;
    }
    /** Same-crop material/synthetic preview. Seven Day labels are optional auxiliary evidence,
     * never a calendar date. An exact high-confidence title and one plain numeral (using the
     * existing bounded fullwidth/outer-space normalization) are required
     * in independently observed complete-panel/ring geometry. The protected title zone is the
     * upper-left quarter/40% of the panel; the entire ring is protected, with the numeral wholly
     * inside its 20% inset. No OCR box is clipped, replaced, merged, or silently discarded. */
    public static VisualTargetResult previewVisualTarget(Geometry g,TextEvidence text) {
        if(g==null)return visualResult(null,Status.INVALID_INPUT);
        if(g.status()!=Status.OBSERVED)return visualResult(g,g.status());
        if(text==null||!Objects.equals(g.key(),text.key())||text.rows()==null||text.rows().size()>128)
            return visualResult(g,Status.BAD_OCR);
        // Validate every raw row, including rows wholly outside the selected panel.
        for(Row r:text.rows())if(r==null||r.origin()==null||r.text()==null||r.text().length()>128
            ||!valid(r.box(),g.width(),g.height())||!Double.isFinite(r.confidence())
            ||r.confidence()<0||r.confidence()>1)return visualResult(g,Status.BAD_OCR);
        Surface surface=null;Row title=null;
        for(Surface observed:g.surfaces())for(Row row:text.rows())
            if(row.text().equals("签入")&&observed.box().overlaps(row.box())) {
                if(!observed.box().contains(row.box())||!visualTitleZone(observed.box()).contains(row.box())
                    ||!confidence(row.confidence())||row.origin()!=Origin.STATIC_OCR)
                    return visualResult(g,Status.BAD_OCR);
                if(title!=null)return visualResult(g,Status.NO_UNIQUE_SECTION);
                surface=observed;title=row;
            }
        if(surface==null)return visualResult(g,Status.NO_UNIQUE_SECTION);
        Box panel=surface.box();
        if(!surface.complete())return visualResult(g,Status.INCOMPLETE_REGION);
        Box titleZone=visualTitleZone(panel);
        for(Row row:text.rows())if(panel.overlaps(row.box())) {
            if(!panel.contains(row.box()))return visualResult(g,Status.BAD_OCR);
            if(visualConflict(row.text()))return visualResult(g,Status.NO_UNIQUE_SECTION);
        }
        for(Row row:text.rows())if(row!=title&&titleZone.overlaps(row.box()))
            return visualResult(g,Status.BAD_OCR);
        Box ring=null;
        for(Box observed:g.rings())if(panel.overlaps(observed)) {
            if(!panel.contains(observed)||ring!=null)return visualResult(g,Status.AMBIGUOUS_CIRCLE);
            ring=observed;
        }
        if(ring==null)return visualResult(g,Status.NO_CURRENT_TARGET);
        if(titleZone.overlaps(ring))return visualResult(g,Status.AMBIGUOUS_CIRCLE);
        for(Tick tick:g.ticks())if(ring.overlaps(tick.disk()))
            return visualResult(g,Status.AMBIGUOUS_CIRCLE);
        int insetX=Math.max(2,(int)Math.ceil(.20*ring.width()));
        int insetY=Math.max(2,(int)Math.ceil(.20*ring.height()));
        Box safe=new Box(ring.left()+insetX,ring.top()+insetY,ring.right()-insetX,ring.bottom()-insetY);
        Row number=null;int value=0;
        for(Row row:text.rows())if(ring.overlaps(row.box())) {
            if(!confidence(row.confidence())||row.origin()!=Origin.STATIC_OCR)
                return visualResult(g,Status.BAD_OCR);
            Integer n=points(row.text());
            if(number!=null||n==null||!safe.contains(row.box()))
                return visualResult(g,Status.BAD_NUMBER);
            value=n;
            number=row;
        }
        if(number==null)return visualResult(g,Status.BAD_NUMBER);
        return new VisualTargetResult(g,Status.CANDIDATE,panel,ring,title,number,value,text.rows().size()-2);
    }
    /** No fraction/countdown gate. Exact title and seven ordered day labels are auxiliary
     * evidence within a machine-observed complete boundary. Manual labels remain manual. */
    public static Result associate(Geometry g,TextEvidence text) {
        if(g==null)return result(null,Status.INVALID_INPUT);
        if(g.status()!=Status.OBSERVED)return result(g,g.status());
        if(text==null||!Objects.equals(g.key(),text.key())||text.rows()==null||text.rows().size()>128)return result(g,Status.BAD_OCR);
        for(Row r:text.rows())if(r==null||r.origin()==null||r.text()==null||r.text().length()>128||!valid(r.box(),g.width(),g.height()))return result(g,Status.BAD_OCR);
        List<Surface> matched=new ArrayList<>();Row title=null;
        for(Surface s:g.surfaces())for(Row r:text.rows())if(r.text().equals("签入")&&s.box().contains(r.box())
            &&r.box().left()<s.box().left()+.25*s.box().width()&&r.box().bottom()<s.box().top()+.40*s.box().height()){
            if(!confidence(r.confidence()))return result(g,Status.BAD_OCR);matched.add(s);title=r;
        }
        if(matched.size()!=1)return result(g,Status.NO_UNIQUE_SECTION);
        Surface surface=matched.get(0);Box region=surface.box();
        if(!surface.complete())return result(g,Status.INCOMPLETE_REGION);
        List<Row> rows=new ArrayList<>();
        for(Row r:text.rows())if(region.overlaps(r.box())) {
            if(!region.contains(r.box())||!confidence(r.confidence()))return result(g,Status.BAD_OCR);
            rows.add(r);
            if(r.text().equals("每日活动")||r.text().equals("登录")||r.text().equals("登入"))return result(g,Status.NO_UNIQUE_SECTION);
        }
        for(int i=0;i<rows.size();i++)for(int j=i+1;j<rows.size();j++)if(rows.get(i).box().overlaps(rows.get(j).box()))return result(g,Status.BAD_OCR);
        Row[] labels=new Row[7];
        for(Row r:rows) {
            int d=day(r.text());if(d==0)continue;
            if(labels[d-1]!=null||r.box().top()<region.top()+.55*region.height())return result(g,Status.BAD_DAY_SLOTS);
            labels[d-1]=r;
        }
        for(Row label:labels)if(label==null)return result(g,Status.BAD_DAY_SLOTS);
        double spacing=(labels[6].box().centerX()-labels[0].box().centerX())/6;
        if(spacing<.11*region.width()||spacing>.16*region.width())return result(g,Status.BAD_DAY_SLOTS);
        for(int i=1;i<7;i++)if(Math.abs(labels[i].box().centerX()-labels[i-1].box().centerX()-spacing)>.16*spacing
            ||Math.abs(labels[i].box().centerY()-labels[0].box().centerY())>.10*region.height())return result(g,Status.BAD_DAY_SLOTS);
        List<Slot> slots=new ArrayList<>();int active=0;Slot target=null;
        for(int i=0;i<7;i++) {
            Box label=labels[i].box();int left=i==0?region.left():(int)Math.round((labels[i-1].box().centerX()+label.centerX())/2);
            int right=i==6?region.right():(int)Math.round((labels[i+1].box().centerX()+label.centerX())/2);
            Box bounds=new Box(left,region.top()+(int)(.38*region.height()),right,label.top());
            List<Box> rings=new ArrayList<>();List<Tick> ticks=new ArrayList<>();
            for(Box b:g.rings())if(bounds.overlaps(b)){if(!bounds.contains(b))return result(g,Status.AMBIGUOUS_CIRCLE);rings.add(b);}
            for(Tick t:g.ticks())if(bounds.overlaps(t.disk())){if(!bounds.contains(t.disk()))return result(g,Status.AMBIGUOUS_CIRCLE);ticks.add(t);}
            if(rings.size()>1||ticks.size()>1||(!rings.isEmpty()&&!ticks.isEmpty()))return result(g,Status.AMBIGUOUS_CIRCLE);
            SlotState state=!rings.isEmpty()?SlotState.CURRENT_STYLE_RING:!ticks.isEmpty()?SlotState.LOCAL_TICK:SlotState.NO_CURRENT_MARK;
            Slot slot=new Slot(i+1,bounds,label,labels[i].origin(),state,rings.isEmpty()?null:rings.get(0),ticks.isEmpty()?null:ticks.get(0));slots.add(slot);
            if(state==SlotState.CURRENT_STYLE_RING){active++;target=slot;}
        }
        int regionRingCount=0;for(Box b:g.rings())if(region.overlaps(b))regionRingCount++;
        if(regionRingCount!=active||active>1)return result(g,Status.AMBIGUOUS_CIRCLE);
        if(active==0)return new Result(g,Status.NO_CURRENT_TARGET,region,slots,null);
        Box ring=target.ring();int insetX=Math.max(2,(int)Math.ceil(.20*ring.width())),insetY=Math.max(2,(int)Math.ceil(.20*ring.height()));
        Box safe=new Box(ring.left()+insetX,ring.top()+insetY,ring.right()-insetX,ring.bottom()-insetY);
        Row number=null;int value=0,count=0;
        for(Row row:rows)if(ring.overlaps(row.box())) {
            Integer n=points(row.text());if(n==null||!safe.contains(row.box()))return result(g,Status.BAD_NUMBER);
            count++;number=row;value=n;
        }
        if(count!=1)return result(g,Status.BAD_NUMBER);
        Candidate c=new Candidate(target.day(),region,target.bounds(),ring,number.box(),value,number.origin(),title.origin(),target.labelOrigin(),number.box().centerX(),number.box().centerY());
        return new Result(g,Status.CANDIDATE,region,slots,c);
    }
    public record Receipt(Outcome outcome,Candidate candidate,int hypotheticalClicks) {
        public String actor(){return "UNBOUND";} public int realExecutions(){return 0;}
        public boolean creditKnown(){return false;}public boolean causalityProven(){return false;}
        public boolean retryAllowed(){return false;}
    }
    private static Receipt receipt(Outcome s,Candidate c,int clicks){return new Receipt(s,c,clicks);}
    /** One-use in-memory fake budget. Cancellation/verification consumes the flow; no next day. */
    public static final class Session {
        private final Result before;private boolean consumed,canceled,verified,previewed;
        public Session(Result before){this.before=before;}
        public synchronized Receipt previewOnce() {
            if(canceled)return receipt(Outcome.CANCELED,null,0);
            if(consumed)return receipt(Outcome.ALREADY_CONSUMED,null,0);
            consumed=true;
            if(before==null||before.status()!=Status.CANDIDATE)return receipt(Outcome.BLOCKED,null,0);
            previewed=true;
            return receipt(Outcome.PREVIEW_ONLY,before.candidate(),1);
        }
        public synchronized Receipt cancel(){canceled=true;consumed=true;return receipt(Outcome.CANCELED,null,0);}
        public synchronized Receipt verifyOnce(Result after) {
            if(canceled)return receipt(Outcome.CANCELED,null,0);
            if(verified)return receipt(Outcome.ALREADY_CONSUMED,null,0);
            verified=true;consumed=true;
            if(!previewed||before==null||before.status()!=Status.CANDIDATE)return receipt(Outcome.BLOCKED,null,0);
            return compare(before,after);
        }
    }
    /** Caller sequence/series labels never prove runtime freshness or calendar identity. The
     * computed raster hash additionally rejects replay. Only a same-slot visual mark is reported. */
    public static Receipt compare(Result before,Result after) {
        if(before==null||before.status()!=Status.CANDIDATE||after==null||after.region()==null||after.slots().size()!=7)
            return receipt(Outcome.NO_LOCAL_COMPLETION_STOP,null,0);
        Geometry a=before.geometry(),b=after.geometry();Key x=a.key(),y=b.key();
        if(x.source()!=y.source()||!x.materialId().equals(y.materialId())||!x.regionId().equals(y.regionId())
            ||a.width()!=b.width()||a.height()!=b.height()||!before.region().equals(after.region()))
            return receipt(Outcome.SCOPE_OR_DAY_CHANGE_STOP,null,0);
        if(y.observation()<=x.observation()||a.pixelHash().equals(b.pixelHash()))return receipt(Outcome.REPEATED_OR_STALE_IMAGE_STOP,null,0);
        int day=before.candidate().day();
        for(int i=0;i<7;i++) {
            Slot p=before.slots().get(i),q=after.slots().get(i);
            if(p.day()!=q.day()||!p.label().equals(q.label())||!p.bounds().equals(q.bounds())
                ||(i!=day-1&&p.state()!=q.state()))return receipt(Outcome.SCOPE_OR_DAY_CHANGE_STOP,null,0);
        }
        Slot target=after.slots().get(day-1);
        if(target.state()!=SlotState.LOCAL_TICK||target.tick()==null)return receipt(Outcome.NO_LOCAL_COMPLETION_STOP,null,0);
        Box ring=before.candidate().circle(),disk=target.tick().disk();
        if(!ring.contains(disk)||Math.abs(ring.centerX()-disk.centerX())>.15*ring.width()
            ||Math.abs(ring.centerY()-disk.centerY())>.15*ring.height())return receipt(Outcome.SCOPE_OR_DAY_CHANGE_STOP,null,0);
        return receipt(Outcome.OBSERVED_SAME_SLOT_MARKED_COMPLETE_UNATTRIBUTED,before.candidate(),0);
    }
}
