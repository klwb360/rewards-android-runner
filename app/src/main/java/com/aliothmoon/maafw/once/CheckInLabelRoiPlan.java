package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.DailyCheckInRegion;
import com.aliothmoon.maafw.rewards.RewardRegionGate.Key;
import com.aliothmoon.maafw.rewards.RewardRegionGate.Source;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import com.aliothmoon.maafw.once.FrameStructureEvidence.Line;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Synthetic-frame search geometry only. Does not supply OCR evidence or input authority. */
final class CheckInLabelRoiPlan {
    private CheckInLabelRoiPlan() {}
    record Plan(boolean admitted,String reason,List<Box> rois,Box band,
                int lowInBand,int lowOutsideBand,int width,int height,String rowDiagnostics) {
        Plan { rois=rois==null?List.of():List.copyOf(rois); }
    }
    record Replacement(boolean accepted,String reason,List<Line> rows,int acceptedLabels) {
        Replacement { rows=List.copyOf(rows); }
    }
    private static boolean canceled(BooleanSupplier cancel) {
        return cancel!=null&&cancel.getAsBoolean();
    }
    private static boolean shape(List<Line> rows,int w,int h) {
        Object token=new Object();
        var binding=new FrameStructureEvidence.Binding(token,token,token,token);
        return OcrRowsAssessment.assess(binding,binding,rows,w,h).shapeValid();
    }
    private static Box box(Line row) {
        return new Box(row.x(),row.y(),row.x()+row.width(),row.y()+row.height());
    }
    private static boolean valid(Box b,int w,int h) {
        return b!=null&&b.left()>=0&&b.top()>=0&&b.right()<=w&&b.bottom()<=h
            &&b.left()<b.right()&&b.top()<b.bottom();
    }
    private static Plan denied(String reason,int w,int h) {
        return new Plan(false,reason,List.of(),null,0,0,w,h,"");
    }
    static Plan prepare(byte[] gray,int width,int height,List<Line> fullRows,BooleanSupplier cancel) {
        if(canceled(cancel))return denied("CANCELED",width,height);
        if(!shape(fullRows,width,height))return denied("INVALID_ROWS",width,height);
        var geometry=DailyCheckInRegion.observeGray8(
            new Key(Source.SYNTHETIC_FIXTURE,"label-roi",1,"checkin"),gray,width,height,cancel);
        if(canceled(cancel))return denied("CANCELED",width,height);
        if(geometry.status()!=DailyCheckInRegion.Status.OBSERVED)
            return denied("NO_GEOMETRY",width,height);
        DailyCheckInRegion.Surface selected=null;
        int matches=0;
        for(var surface:geometry.surfaces())for(Line row:fullRows) {
            Box panel=surface.box(),title=box(row);
            if(row.text().equals("\u7b7e\u5165")&&panel.contains(title)
                &&title.left()<panel.left()+.25*panel.width()
                &&title.bottom()<panel.top()+.40*panel.height()) {
                if(row.score()<.85)return denied("LOW_TITLE",width,height);
                matches++;selected=surface;
            }
        }
        if(matches!=1)return denied("NO_UNIQUE_PANEL",width,height);
        if(!selected.complete())return denied("INCOMPLETE_PANEL",width,height);
        Box panel=selected.box();
        Box band=new Box(panel.left(),panel.top()+(int)Math.ceil(.72*panel.height()),panel.right(),panel.bottom());
        List<Box> rois=searches(band);
        for(Box roi:rois)if(!valid(roi,width,height)||!panel.contains(roi))return denied("INVALID_ROI",width,height);
        int inside=0,outside=0;
        for(Line row:fullRows)if(row.score()<.85) {
            if(band.contains(box(row)))inside++;else outside++;
        }
        if(canceled(cancel))return denied("CANCELED",width,height);
        return new Plan(true,fullLabelsAvailable(fullRows,panel)?"FULL_LABELS_ALREADY_AVAILABLE":"READY",rois,band,inside,outside,width,height,
            diagnostics(fullRows,geometry,panel,band));
    }
    // Optimization only: keep original native evidence for downstream assessment.
    // This does not admit the frame or discount any unrelated low-confidence row.
    private static boolean fullLabelsAvailable(List<Line> rows,Box panel) {
        Line[] days=new Line[7];
        Box lowerHalf=new Box(panel.left(),panel.top()+(int)Math.ceil(.5*panel.height()),panel.right(),panel.bottom());
        for(Line row:rows)for(int day=1;day<=7;day++) {
            if(!row.text().equals("Day "+day)&&!row.text().equals("Day"+day)
                &&!row.text().equals("\u7b2c"+day+"\u5929"))continue;
            if(days[day-1]!=null||row.score()<.85||!lowerHalf.contains(box(row)))return false;
            days[day-1]=row;
        }
        for(int i=0;i<7;i++) {
            if(days[i]==null)return false;
            if(i>0&&box(days[i-1]).right()>box(days[i]).left())return false;
        }
        return true;
    }
    private static List<Box> partitions(Box band) {
        List<Box> result=new ArrayList<>();
        for(int i=0;i<7;i++)result.add(new Box(band.left()+band.width()*i/7,band.top(),
            band.left()+band.width()*(i+1)/7,band.bottom()));
        return result;
    }
    private static List<Box> searches(Box band) {
        List<Box> result=new ArrayList<>();
        int dy=(int)Math.ceil(.10*band.height());
        // Band bottom and horizontal bounds are observed panel bounds. Its .72 top
        // leaves more than this upward margin inside the panel; prepare verifies it.
        for(Box core:partitions(band)) {
            int dx=(int)Math.ceil(.15*core.width());
            result.add(new Box(Math.max(band.left(),core.left()-dx),Math.max(0,band.top()-dy),
                Math.min(band.right(),core.right()+dx),band.bottom()));
        }
        return result;
    }
    private static String category(String text) {
        if(text.matches("[0-9]+"))return "DIGITS";
        if(text.matches("(?:Day ?[1-7]|第[1-7]天)(?:[ ]+(?:Day ?[1-7]|第[1-7]天))*"))return "DAY_TEXT";
        if(text.equals("\u7b7e\u5165"))return "TITLE";
        return "OTHER";
    }
    private static String relation(Box row,Box zone) {
        return zone.contains(row)?"IN":zone.overlaps(row)?"HIT":"OUT";
    }
    private static String markRelation(Box row,List<Box> marks) {
        int in=0,hit=0;
        for(Box mark:marks) { if(mark.contains(row))in++;if(mark.overlaps(row))hit++; }
        return in==1&&hit==1?"IN1":hit>0?"HIT"+hit:"OUT";
    }
    private static String diagnostics(List<Line> rows,DailyCheckInRegion.Geometry geometry,Box panel,Box band) {
        List<Box> disks=new ArrayList<>();
        for(var tick:geometry.ticks())disks.add(tick.disk());
        Box title=new Box(panel.left(),panel.top(),panel.left()+(int)Math.ceil(.25*panel.width()),
            panel.top()+(int)Math.ceil(.40*panel.height()));
        StringBuilder result=new StringBuilder();
        int[] counts=new int[4];
        // Presentation buckets only: the exact native scores and rows remain unchanged.
        for(int pass=0;pass<2;pass++)for(Line row:rows) {
            if((row.score()<.85)!=(pass==0))continue;
            String type=category(row.text());
            int k=switch(type){case "DIGITS"->0;case "DAY_TEXT"->1;case "TITLE"->2;default->3;};
            if(counts[k]>=4)continue;
            Box b=box(row);
            String bucket=row.score()<.5?"LT50":row.score()<.85?"50_84":"85_100";
            String entry=(result.length()==0?"":";")+type+" c="+bucket+" box="+b.left()+","+b.top()+","+b.right()+","+b.bottom()
                +" ring="+markRelation(b,geometry.rings())+" tick="+markRelation(b,disks)
                +" band="+relation(b,band)+" title="+relation(b,title)+" panel="+relation(b,panel);
            if(result.length()+entry.length()>700)return result.toString();
            result.append(entry);counts[k]++;
        }
        return result.toString();
    }
    private static Replacement denied(String reason,List<Line> rows) {
        return new Replacement(false,reason,rows,0);
    }
    static Replacement replace(Plan plan,List<Line> original,List<List<Line>> roiResults,BooleanSupplier cancel) {
        if(plan==null||!shape(original,plan.width(),plan.height()))return denied("INVALID_ORIGINAL",List.of());
        List<Line> safe=List.copyOf(original);
        if(canceled(cancel))return denied("CANCELED",safe);
        if(!plan.admitted()||!valid(plan.band(),plan.width(),plan.height())
            ||!plan.rois().equals(searches(plan.band())))return denied("INVALID_PLAN",safe);
        for(Box roi:plan.rois())if(!valid(roi,plan.width(),plan.height()))return denied("INVALID_ROI",safe);
        if(roiResults==null||roiResults.size()!=7)return denied("MISSING_BATCHES",safe);
        List<Line> labels=new ArrayList<>();
        List<Box> cores=partitions(plan.band());
        for(int i=0;i<7;i++) {
            if(canceled(cancel))return denied("CANCELED",safe);
            List<Line> batch=roiResults.get(i);
            if(batch==null||batch.size()!=1||!shape(batch,plan.width(),plan.height()))return denied("INVALID_BATCH",safe);
            Line row=batch.get(0);Box actual=box(row);int day=i+1;
            if(row.score()<.85)return denied("LOW_LABEL",safe);
            if(!row.text().equals("Day "+day)&&!row.text().equals("Day"+day)
                &&!row.text().equals("\u7b2c"+day+"\u5929"))return denied("WRONG_DAY",safe);
            // Maa v5.15 all[].box is already in full-image coordinates. Never offset it.
            Box roi=plan.rois().get(i);
            if(!roi.contains(actual)||!plan.band().contains(actual))return denied("LABEL_OUTSIDE_ROI",safe);
            // Recognition-only fallback may report the whole search ROI, not a text box.
            if(actual.left()<=roi.left()||actual.top()<=roi.top()
                ||actual.right()>=roi.right()||actual.bottom()>=roi.bottom())return denied("LABEL_TOUCHES_ROI",safe);
            Box core=cores.get(i);
            if(actual.centerX()<core.left()||actual.centerX()>=core.right()
                ||actual.width()>=.85*core.width())return denied("LABEL_OUTSIDE_CORE",safe);
            for(Line previous:labels)if(box(previous).overlaps(actual))return denied("OVERLAPPING_LABELS",safe);
            labels.add(row);
        }
        List<Line> replaced=new ArrayList<>();
        for(Line row:safe) {
            Box actual=box(row);
            if(plan.band().contains(actual))continue;
            if(plan.band().overlaps(actual))return denied("PARTIAL_BAND_ROW",safe);
            replaced.add(row);
        }
        replaced.addAll(labels);
        if(replaced.size()>128)return denied("ROW_LIMIT",safe);
        if(canceled(cancel))return denied("CANCELED",safe);
        return new Replacement(true,"REPLACED",replaced,7);
    }
}
