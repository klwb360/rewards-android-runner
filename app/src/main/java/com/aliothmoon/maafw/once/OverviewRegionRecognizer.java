package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.DailyCheckInRegion;
import com.aliothmoon.maafw.rewards.RewardRegionGate;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Row;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Origin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import static com.aliothmoon.maafw.once.OverviewFrameResult.Reason;
import static com.aliothmoon.maafw.once.OverviewFrameResult.Status;

/** Pure bounded ROI observer. Caller binds UI, original OCR, gray and optional ARGB to one
 * authenticated frame. This class cannot establish that binding or grant input authority.
 * Original rows are never corrected, combined, re-scored or changed; ROI translation only
 * changes their coordinate origin. Results retain no text, image, row or pixel digest. */
final class OverviewRegionRecognizer {
    private static final int MAX_PIXELS = 4_194_304, MAX_REGIONS = 12, MAX_ROWS = 512;
    private static final double MIN_CONFIDENCE = .85;
    private static final Pattern PLUS = Pattern.compile("\\+[ \\u00a0\\u2009\\u202f]*([0-9]{1,4})");
    private static final Pattern FRACTION = Pattern.compile("([0-9]{1,4})[ \\u00a0\\u2009\\u202f]*/[ \\u00a0\\u2009\\u202f]*([0-9]{1,4})");
    private record Progress(int current, int total) { boolean complete() { return total > 0 && current == total; } }
    private record Crop(Box box, byte[] gray, List<Row> rows) { }
    private static final class Halt extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Reason reason;
        Halt(Reason reason) { this.reason = reason; }
    }

    static OverviewFrameResult observe(long frameId, List<OverviewUiRegions.Region> regions,
            byte[] gray8, int[] argb, int width, int height, List<Row> originalRows,
            BooleanSupplier canceled) {
        if (frameId < 1) throw new IllegalArgumentException("positive frame id");
        if (regions == null || regions.size() > MAX_REGIONS || canceled == null)
            return failed(frameId, Reason.INVALID_INPUT);
        if (width < 32 || height < 32 || width > 4096 || height > 8192
                || (long) width*height > MAX_PIXELS || gray8 == null || gray8.length != (long)width*height
                || argb != null && argb.length != gray8.length) return failed(frameId, Reason.INVALID_IMAGE);
        try {
            check(canceled);
            validateRows(originalRows, width, height, canceled);
            for (var region : regions) {
                if (region == null || region.role() == null || !valid(region.coarse(),width,height)
                        || region.heading() != null && !valid(region.heading(),width,height)
                        || region.counter() != null && !valid(region.counter(),width,height))
                    return failed(frameId, Reason.REGION_OUTSIDE_FRAME);
            }
            List<OverviewFrameResult.Item> out = new ArrayList<>();
            for (int i=0;i<regions.size();i++) {
                check(canceled);
                var region = regions.get(i);
                try {
                    // Summary context can overlap child task regions. Two actionable region
                    // surfaces may not claim the same original pixels.
                    if (actionRole(region.role())) for (int j=0;j<regions.size();j++) {
                        if (j != i && actionRole(regions.get(j).role())
                                && region.coarse().overlaps(regions.get(j).coarse())) throw new Halt(Reason.REGION_OVERLAP);
                    }
                    observeRegion(frameId,i,region,gray8,argb,width,height,originalRows,canceled,out);
                } catch (Halt held) {
                    if (held.reason == Reason.CANCELED) throw held;
                    out.add(hold(i,0,region,held.reason,null,-1,-1));
                }
                if (out.size()>96) return failed(frameId,Reason.INVALID_INPUT);
            }
            check(canceled);
            return new OverviewFrameResult(frameId,out,null);
        } catch (Halt held) { return failed(frameId,held.reason); }
        catch (RuntimeException unavailable) { return failed(frameId,Reason.DETECTOR_UNAVAILABLE); }
    }

    private static void observeRegion(long frameId,int index,OverviewUiRegions.Region r,
            byte[] pixels,int[] argb,int w,int h,List<Row> rows,BooleanSupplier cancel,
            List<OverviewFrameResult.Item> out) {
        // An activity container can extend beyond the viewport. Its role must still be bound
        // by the current UI tree; a null heading means no heading OCR/old-frame context is used.
        Row heading = r.heading()==null && activityRole(r.role()) ? null : heading(r,rows);
        Progress progress = progress(r,rows);
        if (progress.complete()) {
            out.add(item(index,0,r,null,heading==null?null:heading.box(),null,Status.REGION_COMPLETE_SKIP,
                    Reason.EQUAL_REGION_COUNTER,-1,progress)); return;
        }
        if (r.role()==OverviewUiRegions.Role.POINTS_SUMMARY || r.role()==OverviewUiRegions.Role.STREAK_SUMMARY) {
            out.add(item(index,0,r,null,heading.box(),null,Status.OBSERVED,
                    Reason.READ_ONLY_SUMMARY,-1,progress)); return;
        }
        if (!r.completeVisible() && !activityRole(r.role())) throw new Halt(Reason.REGION_PARTIAL);
        Crop crop = crop(r,pixels,w,h,rows,cancel);
        try {
            var key = new RewardRegionGate.Key(RewardRegionGate.Source.REAL_DISPLAY_NATIVE,
                    "overview-frame",frameId,"region-"+index);
            if (r.role()==OverviewUiRegions.Role.CHECK_IN || r.role()==OverviewUiRegions.Role.DAILY_PROGRESS) {
                var g = DailyCheckInRegion.observeGray8(key,crop.gray(),crop.box().width(),crop.box().height(),cancel);
                check(cancel);
                if (g.status()!=DailyCheckInRegion.Status.OBSERVED) throw new Halt(Reason.NO_BOUNDARY);
                DailyCheckInRegion.Surface selected = null;
                Box localHeading = local(heading.box(),crop.box());
                for (var surface:g.surfaces()) if (surface.box().contains(localHeading)) {
                    if(selected!=null) throw new Halt(Reason.AMBIGUOUS_BOUNDARY);
                    selected=surface;
                }
                if(selected==null) throw new Halt(Reason.NO_BOUNDARY);
                Box panel=full(selected.box(),crop.box());
                if(!selected.complete() || !r.coarse().contains(panel)) throw new Halt(Reason.REGION_PARTIAL);
                if(r.role()==OverviewUiRegions.Role.DAILY_PROGRESS) {
                    out.add(item(index,0,r,panel,heading.box(),null,Status.OBSERVED,
                            Reason.READ_ONLY_WEEK_PROGRESS,-1,progress));return;
                }
                checkIn(index,r,heading,panel,g,crop,argb,w,rows,progress,cancel,out);
            } else {
                var g=RewardRegionLocator.observeVisibleMoreGray8(key,crop.gray(),crop.box().width(),crop.box().height(),cancel);
                check(cancel);
                if(g.status()!=RewardRegionLocator.Status.OBSERVED || g.cards().isEmpty()) throw new Halt(Reason.NO_BOUNDARY);
                if(g.cards().size()>8) throw new Halt(Reason.INVALID_INPUT);
                List<RewardRegionLocator.SkippedItem> skipped=RewardRegionLocator.observeCompletedItems(g,crop.rows());
                for(int cardIndex=0;cardIndex<g.cards().size();cardIndex++) {
                    check(cancel);
                    var card=g.cards().get(cardIndex);
                    Box boundary=full(card.box(),crop.box());
                    try { activity(index,cardIndex,r,heading,card,g,skipped,crop,progress,out); }
                    catch(Halt held) { out.add(hold(index,cardIndex,r,held.reason,
                            r.coarse().contains(boundary)?boundary:null,progress.current(),progress.total())); }
                }
            }
        } finally { Arrays.fill(crop.gray(),(byte)0); }
    }

    private static void checkIn(int index,OverviewUiRegions.Region r,Row heading,Box panel,
            DailyCheckInRegion.Geometry geometry,Crop crop,int[] argb,int width,List<Row> rows,
            Progress progress,BooleanSupplier cancel,List<OverviewFrameResult.Item> out) {
        if (heading.box().left()>=panel.left()+panel.width()*.25
                || heading.box().bottom()>panel.top()+panel.height()*.40) throw new Halt(Reason.HEADER_CONFLICT);
        Box ring=null;
        for(Box observed:geometry.rings()) {
            Box candidate=full(observed,crop.box());
            if(!panel.overlaps(candidate))continue;
            if(!panel.contains(candidate)||ring!=null)throw new Halt(Reason.AMBIGUOUS_RING);
            ring=candidate;
        }
        if(ring==null)throw new Halt(Reason.NO_CURRENT_RING);
        for(var tick:geometry.ticks())if(ring.overlaps(full(tick.disk(),crop.box())))throw new Halt(Reason.TICK_CONFLICT);
        int ix=Math.max(2,(int)Math.ceil(.20*ring.width())),iy=Math.max(2,(int)Math.ceil(.20*ring.height()));
        Box safe=new Box(ring.left()+ix,ring.top()+iy,ring.right()-ix,ring.bottom()-iy);
        Row numeral=null;int ringRows=0;
        for(Row row:rows)if(panel.overlaps(row.box())) {
            if(!panel.contains(row.box()))throw new Halt(Reason.ROW_CROSSES_REGION);
            if(conflict(row.text()))throw new Halt(Reason.STATE_CONFLICT);
            if(ring.overlaps(row.box())) {
                if(!trusted(row))throw new Halt(Reason.LOW_CONFIDENCE);
                ringRows++;
                if(numeral==null)numeral=row;
            }
        }
        if(numeral==null)throw new Halt(Reason.NUMBER_MISSING);
        if(ringRows!=1)throw new Halt(Reason.NUMBER_MULTIPLE);
        Integer points=plainNumber(numeral.text());
        if(points==null)throw new Halt(numeral.text().matches("[0-9]{1,4}")
                ?Reason.NUMBER_INVALID:Reason.NUMBER_NON_NUMERIC);
        if(!safe.contains(numeral.box()))throw new Halt(Reason.NUMBER_OUTSIDE_SAFE_RING);
        if(argb==null)throw new Halt(Reason.COLOR_UNAVAILABLE);
        if(!yellowRing(argb,width,ring,cancel))throw new Halt(Reason.COLOR_NOT_YELLOW);
        out.add(item(index,0,r,panel,heading.box(),numeral.box(),Status.CANDIDATE,
                Reason.CURRENT_RING_NUMBER,points,progress));
    }

    private static void activity(int index,int cardIndex,OverviewUiRegions.Region r,Row heading,
            RewardRegionLocator.Card card,RewardRegionLocator.Geometry geometry,
            List<RewardRegionLocator.SkippedItem> skipped,Crop crop,Progress progress,
            List<OverviewFrameResult.Item> out) {
        Box b=full(card.box(),crop.box());
        // Detector padding is scratch context, not additional visible UI authority. A card
        // touching the original coarse viewport can be clipped even if padding shows chrome.
        if(!card.fullyVisible()||!strictlyInside(r.coarse(),b))throw new Halt(Reason.CARD_PARTIAL);
        if(heading!=null && b.top()<=heading.box().bottom())throw new Halt(Reason.HEADER_CONFLICT);
        for(var other:geometry.cards())if(other!=card&&other.box().overlaps(card.box()))throw new Halt(Reason.CARD_OVERLAP);
        List<Row> localRows=new ArrayList<>();
        for(Row row:crop.rows())if(card.box().overlaps(row.box())) {
            if(!card.box().contains(row.box()))throw new Halt(Reason.ROW_CROSSES_CARD);
            if(!trusted(row))throw new Halt(Reason.LOW_CONFIDENCE);
            localRows.add(row);
        }
        for(int i=0;i<localRows.size();i++)for(int j=i+1;j<localRows.size();j++)
            if(localRows.get(i).box().overlaps(localRows.get(j).box()))throw new Halt(Reason.ROW_OVERLAP);
        for(var skip:skipped)if(skip.cardBox().equals(card.box())) {
            Row title=title(localRows,card.box(),skip.numberBox());
            if(title==null)throw new Halt(Reason.TITLE_MISSING);
            out.add(item(index,cardIndex,r,b,full(title.box(),crop.box()),null,Status.ITEM_COMPLETE_SKIP,
                    Reason.NUMBER_AND_TICK,skip.displayedNumber(),progress));return;
        }
        if(!card.tickMarks().isEmpty())throw new Halt(Reason.TICK_CONFLICT);
        double aspect=(double)b.width()/b.height();
        if(aspect<2||aspect>4.5)throw new Halt(Reason.CARD_PARTIAL);
        if(card.badges().size()!=1)throw new Halt(Reason.BADGE_AMBIGUOUS);
        Box badge=card.badges().get(0).box();Box cb=card.box();
        double relX=(badge.left()-cb.left())/(double)cb.width(), relW=badge.width()/(double)cb.width();
        double relH=badge.height()/(double)cb.height(), relY=(badge.centerY()-cb.top())/cb.height();
        if(!cb.contains(badge)||relX<.70||relW<.16||relW>.26||relH<.24||relH>.51||relY<.38||relY>.62)
            throw new Halt(Reason.BADGE_AMBIGUOUS);
        Row title=title(localRows,cb,badge), token=null;int points=0;
        if(title==null)throw new Halt(Reason.TITLE_MISSING);
        for(Row row:localRows) {
            if(conflict(row.text()))throw new Halt(Reason.STATE_CONFLICT);
            Integer value=plus(row.text());
            if(value!=null) { if(token!=null)throw new Halt(Reason.TOKEN_AMBIGUOUS);token=row;points=value; }
            else if(badge.overlaps(row.box()))throw new Halt(Reason.TOKEN_AMBIGUOUS);
        }
        Box safe=new Box(badge.left()+2,badge.top()+2,badge.right()-2,badge.bottom()-2);
        if(token==null||!safe.contains(token.box()))throw new Halt(Reason.TOKEN_AMBIGUOUS);
        out.add(item(index,cardIndex,r,b,full(title.box(),crop.box()),full(badge,crop.box()),
                Status.CANDIDATE,Reason.PLUS_NUMBER,points,progress));
    }

    private static Row title(List<Row> rows,Box card,Box reward) {
        Row first=null;
        for(Row row:rows)if(row.box().right()<reward.left()&&row.box().top()<card.top()+card.height()*.5
                &&row.box().left()<card.left()+card.width()*.65&&row.text().codePointCount(0,row.text().length())>=2
                &&row.text().codePoints().anyMatch(Character::isLetter)) {
            if(first==null || row.box().top()<first.box().top())first=row;
        }
        return first;
    }

    private static Row heading(OverviewUiRegions.Region region,List<Row> rows) {
        if(region.heading()==null)throw new Halt(Reason.HEADER_MISSING);
        Row selected=null;boolean extraText=false;
        for(Row row:rows)if(region.heading().overlaps(row.box())) {
            if(!headerText(region.role(),row.text())) {
                extraText|=containsHeaderText(region.role(),row.text());
                continue;
            }
            if(!trusted(row))throw new Halt(Reason.LOW_CONFIDENCE);
            if(selected!=null)throw new Halt(Reason.HEADER_CONFLICT);
            selected=row;
        }
        if(selected==null)throw new Halt(extraText?Reason.HEADER_WITH_EXTRA_TEXT:Reason.HEADER_MISSING);
        return selected;
    }
    /** Diagnostic only: a merged or decorated title remains rejected, without rewriting its row. */
    private static boolean containsHeaderText(OverviewUiRegions.Role role,String text) {
        return switch(role) {
            case CHECK_IN -> text.contains("签入")||text.contains("签到");
            case DAILY_PROGRESS,DAILY_ACTIVITIES -> text.contains("每日活动");
            case MORE_ACTIVITIES -> text.contains("更多活动");
            case POINTS_SUMMARY -> text.contains("今日积分");
            case STREAK_SUMMARY -> text.contains("连续");
        };
    }
    private static boolean headerText(OverviewUiRegions.Role role,String text) {
        return switch(role) {
            case CHECK_IN -> text.equals("签入")||text.equals("签到")||text.equals("每日签到");
            case DAILY_PROGRESS,DAILY_ACTIVITIES -> text.equals("每日活动");
            case MORE_ACTIVITIES -> text.equals("更多活动");
            case POINTS_SUMMARY -> text.equals("今日积分");
            case STREAK_SUMMARY -> text.equals("连续");
        };
    }
    private static Progress progress(OverviewUiRegions.Region r,List<Row> rows) {
        if(r.counter()==null) {
            if(r.current()!=-1||r.target()!=-1)throw new Halt(Reason.COUNTER_CONFLICT);
            return new Progress(-1,-1);
        }
        Row selected=null;
        for(Row row:rows)if(r.counter().overlaps(row.box())) {
            if(selected!=null)throw new Halt(Reason.COUNTER_AMBIGUOUS);
            if(!r.counter().contains(row.box()))throw new Halt(Reason.COUNTER_AMBIGUOUS);
            selected=row;
        }
        if(selected==null)throw new Halt(Reason.COUNTER_MISSING);
        if(!trusted(selected))throw new Halt(Reason.LOW_CONFIDENCE);
        var match=FRACTION.matcher(selected.text());
        if(!match.matches())throw new Halt(Reason.COUNTER_INVALID);
        int current=Integer.parseInt(match.group(1)),total=Integer.parseInt(match.group(2));
        if(total==0||current>total)throw new Halt(Reason.COUNTER_INVALID);
        if((r.current()!=-1||r.target()!=-1)&&(r.current()!=current||r.target()!=total))throw new Halt(Reason.COUNTER_CONFLICT);
        return new Progress(current,total);
    }

    private static Crop crop(OverviewUiRegions.Region r,byte[] source,int width,int height,List<Row> rows,BooleanSupplier cancel) {
        Box area=r.coarse();
        for(Box b:new Box[]{r.heading(),r.counter()})if(b!=null)area=union(area,b);
        Box roi=new Box(Math.max(0,area.left()-16),Math.max(0,area.top()-16),Math.min(width,area.right()+16),Math.min(height,area.bottom()+16));
        List<Row> translated=new ArrayList<>();
        for(Row row:rows)if(roi.overlaps(row.box())) {
            if(!roi.contains(row.box())) {
                if(area.overlaps(row.box()))throw new Halt(Reason.ROW_CROSSES_REGION);
                continue;
            }
            translated.add(new Row(row.text(),local(row.box(),roi),row.confidence(),row.origin()));
        }
        if(translated.size()>128)throw new Halt(Reason.INVALID_ROWS);
        byte[] gray=new byte[roi.width()*roi.height()];
        try {
            for(int y=0;y<roi.height();y++) { check(cancel);System.arraycopy(source,(roi.top()+y)*width+roi.left(),gray,y*roi.width(),roi.width()); }
            return new Crop(roi,gray,List.copyOf(translated));
        } catch(RuntimeException failure) { Arrays.fill(gray,(byte)0);throw failure; }
    }
    private static boolean yellowRing(int[] argb,int width,Box ring,BooleanSupplier cancel) {
        int samples=0,yellow=0;
        double cx=ring.centerX(),cy=ring.centerY(),rx=ring.width()/2.0,ry=ring.height()/2.0;
        for(int y=ring.top();y<ring.bottom();y++) {
            check(cancel);
            for(int x=ring.left();x<ring.right();x++) {
                double dx=(x+.5-cx)/rx,dy=(y+.5-cy)/ry,d=dx*dx+dy*dy;
                if(d<.70||d>1.05)continue;
                int c=argb[y*width+x],a=(c>>>24)&255,red=(c>>>16)&255,green=(c>>>8)&255,blue=c&255;
                // Count the already-observed dark ring stroke, not the light annulus background.
                // The owner has separately verified these colors against the same gray frame.
                if (((77*red+150*green+29*blue+128)>>>8)>=205) continue;
                samples++;
                if(a==255&&red>=200&&green>=100&&green<=225&&blue<=170&&red-green>=20&&green-blue>=25)yellow++;
            }
        }
        return samples>=ring.width()/2&&yellow*100L>=samples*80L;
    }
    private static void validateRows(List<Row> rows,int w,int h,BooleanSupplier cancel) {
        if(rows==null||rows.size()>MAX_ROWS)throw new Halt(Reason.INVALID_ROWS);
        for(Row row:rows) {
            check(cancel);
            if(row==null||row.text()==null||row.text().length()>1024||row.origin()!=Origin.STATIC_OCR
                    ||!valid(row.box(),w,h)||!Double.isFinite(row.confidence())||row.confidence()<0||row.confidence()>1)
                throw new Halt(Reason.INVALID_ROWS);
        }
    }
    private static boolean trusted(Row row) { return row.origin()==Origin.STATIC_OCR&&row.confidence()>=MIN_CONFIDENCE&&row.confidence()<=1; }
    private static Integer plus(String text) { var m=PLUS.matcher(text);if(!m.matches())return null;int n=Integer.parseInt(m.group(1));return n>0?n:null; }
    private static Integer plainNumber(String text) { if(!text.matches("[0-9]{1,4}"))return null;int n=Integer.parseInt(text);return n>0?n:null; }
    private static boolean conflict(String text) {
        String value=text.toLowerCase(Locale.ROOT);
        return value.contains("已完成")||value.contains("已领取")||value.contains("明天")||value.contains("锁定")
                ||value.contains("登录")||value.contains("completed")||value.contains("claimed")
                ||value.contains("tomorrow")||value.contains("locked")||value.contains("sign in");
    }
    private static boolean actionRole(OverviewUiRegions.Role role) { return role==OverviewUiRegions.Role.CHECK_IN||role==OverviewUiRegions.Role.DAILY_ACTIVITIES||role==OverviewUiRegions.Role.MORE_ACTIVITIES; }
    private static boolean activityRole(OverviewUiRegions.Role role) { return role==OverviewUiRegions.Role.DAILY_ACTIVITIES||role==OverviewUiRegions.Role.MORE_ACTIVITIES; }
    private static boolean valid(Box b,int w,int h) { return b!=null&&b.left()>=0&&b.top()>=0&&b.right()>b.left()&&b.bottom()>b.top()&&b.right()<=w&&b.bottom()<=h; }
    private static boolean strictlyInside(Box outer,Box inner) { return inner.left()>outer.left()&&inner.top()>outer.top()&&inner.right()<outer.right()&&inner.bottom()<outer.bottom(); }
    private static Box local(Box b,Box origin) { return new Box(b.left()-origin.left(),b.top()-origin.top(),b.right()-origin.left(),b.bottom()-origin.top()); }
    private static Box full(Box b,Box origin) { return new Box(b.left()+origin.left(),b.top()+origin.top(),b.right()+origin.left(),b.bottom()+origin.top()); }
    private static Box union(Box a,Box b) { return new Box(Math.min(a.left(),b.left()),Math.min(a.top(),b.top()),Math.max(a.right(),b.right()),Math.max(a.bottom(),b.bottom())); }
    private static void check(BooleanSupplier cancel) { if(cancel.getAsBoolean())throw new Halt(Reason.CANCELED); }
    private static OverviewFrameResult failed(long id,Reason reason) { return new OverviewFrameResult(id,List.of(),reason); }
    private static OverviewFrameResult.Item hold(int region,int item,OverviewUiRegions.Region r,Reason reason,Box boundary,int current,int total) {
        return new OverviewFrameResult.Item(region,item,r.role(),r.coarse(),boundary,null,null,Status.HOLD,reason,-1,current,total);
    }
    private static OverviewFrameResult.Item item(int region,int item,OverviewUiRegions.Region r,Box boundary,Box title,Box target,
            Status status,Reason reason,int points,Progress progress) {
        return new OverviewFrameResult.Item(region,item,r.role(),r.coarse(),boundary,title,target,status,reason,points,progress.current(),progress.total());
    }
    private OverviewRegionRecognizer() { }
}
