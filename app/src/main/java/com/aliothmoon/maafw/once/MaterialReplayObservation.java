package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.DailyCheckInRegion;
import com.aliothmoon.maafw.rewards.RewardRegionGate;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionGate.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Pure bridge for native OCR on generated crop pixels only. Caller supplies one same-frame
 * OCR batch; this class cannot authenticate that binding. No raster or raw text is logged.
 * STATIC_OCR labels the OCR input type, not real-world material provenance. The caller owns
 * the single-use fake selection budget. An observation itself is immutable and passive. */
final class MaterialReplayObservation {
    enum Kind { CHECKIN, DAILY, MORE }
    private final Kind kind;
    private final long sequence;
    private final DailyCheckInRegion.Result checkin;
    private final DailyCheckInRegion.VisualTargetResult visualPreview;
    private final RewardRegionLocator.Result cards;
    private final List<Row> rows;
    private final List<Integer> candidates;
    private final List<SkippedItem> skipped;
    private final List<String> cardHashes;
    private final String pixelHash, summary;
    private final boolean eligible;

    private MaterialReplayObservation(Kind kind,long sequence,DailyCheckInRegion.Result checkin,
            DailyCheckInRegion.VisualTargetResult visualPreview,RewardRegionLocator.Result cards,List<Row> rows,List<Integer> candidates,
            List<SkippedItem> skipped,List<String> cardHashes,String pixelHash,boolean eligible,String summary) {
        this.kind=kind;this.sequence=sequence;this.checkin=checkin;this.visualPreview=visualPreview;this.cards=cards;
        this.rows=Collections.unmodifiableList(new ArrayList<>(rows));
        this.candidates=List.copyOf(candidates);this.skipped=List.copyOf(skipped);
        this.cardHashes=List.copyOf(cardHashes);this.pixelHash=pixelHash;this.eligible=eligible;
        this.summary=summary.substring(0,Math.min(summary.length(),1800));
    }
    Kind kind(){return kind;}
    long sequence(){return sequence;}
    List<Integer> candidates(){return candidates;}
    String summary(){return summary;}
    DailyCheckInRegion.Result checkin(){return checkin;}
    boolean hasVisualPreview(){return visualPreview!=null&&visualPreview.status()==DailyCheckInRegion.Status.CANDIDATE;}
    RewardRegionLocator.Result cards(){return cards;}

    static MaterialReplayObservation observe(Kind kind,long sequence,byte[] gray,int w,int h,
            List<FrameStructureEvidence.Line> lines,BooleanSupplier canceled) {
        BooleanSupplier cancel=canceled==null?()->false:canceled;
        Object token=new Object();
        var binding=new FrameStructureEvidence.Binding(token,token,token,token);
        var quality=OcrRowsAssessment.assess(binding,binding,lines,w,h);
        int low=0,titles=0,days=0,numbers=0;
        List<Row> rows=new ArrayList<>();
        boolean shape=quality.shapeValid();
        String title=kind==Kind.CHECKIN?"签入":kind==Kind.DAILY?"每日活动":"更多活动";
        if(shape) for(var line:lines){
            if(line.score()<.85)low++;
            if(line.text().equals(title))titles++;
            if(line.text().matches("(?:第[1-7]天|Day ?[1-7])"))days++;
            if(line.text().matches("[+＋]?[ 0-9０-９]+"))numbers++;
            rows.add(new Row(line.text(),new Box(line.x(),line.y(),line.x()+line.width(),line.y()+line.height()),line.score(),Origin.STATIC_OCR));
            if(kind==Kind.CHECKIN&&line.text().length()>128)shape=false;
        }
        // Check-in confidence is scoped by its own observer. Every original row is retained.
        // A visual preview never supplies a Day identity or an executable/fake selection.
        boolean valid=kind!=null&&sequence>0&&shape&&quality.nonempty()>0&&(low==0||kind==Kind.CHECKIN)
            &&gray!=null&&(long)w*h==gray.length;
        boolean stopped=cancel.getAsBoolean();
        String reason=stopped?"CANCELED":valid?"VALID":"INVALID_NATIVE_ROWS_OR_FRAME";
        DailyCheckInRegion.Result checkin=null;
        DailyCheckInRegion.VisualTargetResult visualPreview=null;
        RewardRegionLocator.Result cards=null;
        List<Integer> candidates=new ArrayList<>();
        List<SkippedItem> skipped=List.of();List<String> hashes=new ArrayList<>();
        String hash="";int surfaces=0,rings=0,ticks=0,badges=0;
        if(valid&&!stopped){
            // A private snapshot binds hashing and both visual observers to identical bytes.
            byte[] pixels=gray.clone();
            try {
                Key key=new Key(Source.SYNTHETIC_FIXTURE,"native-material-replay-"+kind.name(),sequence,kind.name());
                hash=hash(pixels,w,new Box(0,0,w,h));
                if(kind==Kind.CHECKIN){
                    var geometry=DailyCheckInRegion.observeGray8(key,pixels,w,h,cancel);
                    checkin=DailyCheckInRegion.associate(geometry,new DailyCheckInRegion.TextEvidence(key,rows));
                    visualPreview=DailyCheckInRegion.previewVisualTarget(geometry,new DailyCheckInRegion.TextEvidence(key,rows));
                    surfaces=geometry.surfaces().size();rings=geometry.rings().size();ticks=geometry.ticks().size();
                    reason=checkin.status().name();
                    if(checkin.candidate()!=null)candidates.add(checkin.candidate().day());
                } else {
                    var geometry=RewardRegionLocator.observeGray8(key,pixels,w,h,cancel);
                    List<Row> headings=new ArrayList<>(),parents=new ArrayList<>(),fractions=new ArrayList<>();
                    for(Row row:rows){
                        if(row.text().equals("每日活动")||row.text().equals("更多活动")||row.text().equals("签入"))headings.add(row);
                        if(row.text().equals("今日积分"))parents.add(row);
                        if(fraction(row.text()))fractions.add(row);
                    }
                    boolean complete=geometry.status()==RewardRegionLocator.Status.OBSERVED&&!geometry.cards().isEmpty();
                    for(Card card:geometry.cards())complete&=card.fullyVisible();
                    boolean aligned=false;
                    boolean aggregate=!parents.isEmpty();
                    Key progressKey=aggregate?new Key(key.source(),key.materialId(),key.observation(),kind.name()+"-PAGE_AGGREGATE"):key;
                    ProgressHeader parent=null;
                    if(complete&&headings.size()==1&&fractions.size()==1&&parents.size()<=1){
                        Row section=headings.get(0),a=aggregate?parents.get(0):section,b=fractions.get(0);
                        int top=geometry.cards().get(0).box().top();
                        aligned=section.text().equals(title)&&section.box().left()<=.2*w&&section.box().bottom()<=top
                            &&a.box().left()<=.2*w&&b.box().left()>=.65*w
                            &&a.box().bottom()<=top&&b.box().bottom()<=top&&!a.box().overlaps(b.box())
                            &&Math.abs(a.box().centerY()-b.box().centerY())<=.6*Math.max(a.box().height(),b.box().height());
                        if(aggregate){
                            aligned&=a.box().bottom()<section.box().top()&&b.box().bottom()<section.box().top();
                            parent=new ProgressHeader(progressKey,HeaderLevel.PAGE_AGGREGATE,a.text(),a.box(),b.box(),
                                Math.min(a.confidence(),b.confidence()),Origin.STATIC_OCR);
                        }
                    }
                    List<Reading> readings=fractions.size()==1?List.of(new Reading(progressKey,fractions.get(0).text(),
                        fractions.get(0).confidence(),complete,aligned,aggregate?CounterRole.AGGREGATE_PROGRESS:CounterRole.REGION_PROGRESS,TextBasis.CONTEXTUAL_OCR)):List.of();
                    Assessment gate=RewardRegionGate.assess(progressKey,readings);
                    List<Section> sections=new ArrayList<>();
                    for(Row heading:headings)sections.add(new Section(key,heading.text(),heading.box(),heading.confidence(),heading.origin()));
                    // Even COMPLETE_SKIP must not hide invalid/overlapping native rows.
                    boolean overlap=false;
                    for(int i=0;i<rows.size();i++)for(int j=i+1;j<rows.size();j++)overlap|=rows.get(i).box().overlaps(rows.get(j).box());
                    if(overlap || (gate.gate()==Gate.COMPLETE_SKIP && rowFailure(geometry,rows)!=null))
                        gate=RewardRegionGate.assess(progressKey,List.of());
                    cards=RewardRegionLocator.associate(geometry,gate,parent,sections,rows);
                    skipped=RewardRegionLocator.observeCompletedItems(geometry,rows);
                    reason=cards.status().name();surfaces=geometry.cards().size();
                    for(Card card:geometry.cards()){
                        badges+=card.badges().size();ticks+=card.tickMarks().size();hashes.add(hash(pixels,w,card.box()));
                    }
                    for(var c:cards.candidates())candidates.add(index(c.visualCardId()));
                }
            } finally {Arrays.fill(pixels,(byte)0);}
        }
        stopped|=cancel.getAsBoolean();
        if(stopped){candidates.clear();reason="CANCELED";}
        StringBuilder s=new StringBuilder("NATIVE_OCR_ON_SYNTHETIC_PIXELS kind=").append(kind)
            .append(" sequence=").append(sequence).append(" cropOrigin=0,0 coordinateSpace=CROP_LOCAL_ONLY width=").append(w)
            .append(" height=").append(h).append(" rows=").append(quality.rows()).append(" rowShape=").append(quality.reason())
            .append(" lowConfidence085=").append(low).append(" titles=").append(titles).append(" dayLabels=").append(days)
            .append(" numberRows=").append(numbers).append(" surfaces=").append(surfaces).append(" rings=").append(rings)
            .append(" ticks=").append(ticks).append(" badges=").append(badges).append(" skipped=").append(skipped.size())
            .append(" status=").append(reason);
        if(cards!=null&&cards.progress().fraction()!=null)s.append(" progressRole=").append(cards.progress().role()).append(" fraction=").append(cards.progress().fraction().numerator()).append('/').append(cards.progress().fraction().denominator());
        if(!stopped&&visualPreview!=null){
            s.append(" visualStatus=").append(visualPreview.status());
            if(visualPreview.status()==DailyCheckInRegion.Status.CANDIDATE){
                var v=visualPreview;var b=v.numeral();var ring=v.ring();
                s.append(" VISUAL_CIRCLE_PREVIEW_ONLY date=UNPROVEN dayIdentity=NONE actionAuthority=NONE")
                    .append(" points=").append(v.displayedPoints()).append(" xy=").append(v.cropX()).append(',').append(v.cropY())
                    .append(" nativeNumeralBox=").append(b.left()).append(',').append(b.top()).append(',').append(b.right()).append(',').append(b.bottom())
                    .append(" cvRing=").append(ring.left()).append(',').append(ring.top()).append(',').append(ring.right()).append(',').append(ring.bottom())
                    .append(" auxiliaryRowsRetained=").append(v.auxiliaryRows());
            }
        }
        if(!stopped&&checkin!=null&&checkin.candidate()!=null){var c=checkin.candidate();append(s,c.day(),c.displayedPoints(),c.cropX(),c.cropY(),c.numeral());}
        if(!stopped&&cards!=null)for(var c:cards.candidates())append(s,index(c.visualCardId()),c.displayedPoints(),c.cropX(),c.cropY(),c.badgeBox());
        return new MaterialReplayObservation(kind,sequence,checkin,stopped?null:visualPreview,cards,rows,candidates,skipped,hashes,hash,valid&&!stopped,s.toString());
    }
    private static void append(StringBuilder s,int slot,int points,double x,double y,Box b){
        if(s.length()>1550)return;
        s.append(" candidate=").append(slot).append(" points=").append(points).append(" xy=").append(x).append(',').append(y)
            .append(" bounds=").append(b.left()).append(',').append(b.top()).append(',').append(b.right()).append(',').append(b.bottom());
    }
    private static int index(String id){return Integer.parseInt(id.substring("visual-card-".length()))+1;}
    private static boolean fraction(String text){
        return text.matches("[ \\u00a0\\u2009\\u202f]*[0-9０-９]{1,4}[ \\u00a0\\u2009\\u202f]*[/／][ \\u00a0\\u2009\\u202f]*[0-9０-９]{1,4}[ \\u00a0\\u2009\\u202f]*");
    }
    private static String hash(byte[] p,int w,Box box){
        try {MessageDigest md=MessageDigest.getInstance("SHA-256");
            for(int y=box.top();y<box.bottom();y++)md.update(p,y*w+box.left(),box.width());
            byte[] digest=md.digest();StringBuilder out=new StringBuilder(64);
            for(byte b:digest)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
            Arrays.fill(digest,(byte)0);return out.toString();
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    String compare(MaterialReplayObservation after,int selected){
        if(after==null||!eligible||!after.eligible||!candidates.contains(selected))return "BLOCKED";
        if(kind!=after.kind)return "SCOPE_CHANGE_STOP";
        if(after.sequence<=sequence||pixelHash.equals(after.pixelHash))return "REPEATED_OR_STALE_IMAGE_STOP";
        if(kind==Kind.CHECKIN)return DailyCheckInRegion.compare(checkin,after.checkin).outcome().name();
        if(cards==null||after.cards==null)return "BLOCKED";
        var a=cards.geometry();var b=after.cards.geometry();
        if(a.width()!=b.width()||a.height()!=b.height()||a.cards().size()!=b.cards().size())return "SCOPE_CHANGE_STOP";
        String rowFailure=rowFailure(b,after.rows);
        if(rowFailure!=null)return "AFTER_"+rowFailure;
        rowFailure=rowFailure(a,rows);
        if(rowFailure!=null)return "BEFORE_"+rowFailure;
        var delta=RewardRegionGate.compare(cards.progress(),after.cards.progress());
        if(delta.comparison()!=Comparison.COUNTER_INCREASE_UNATTRIBUTED&&delta.comparison()!=Comparison.COUNTER_COMPLETE_UNATTRIBUTED)return "COUNTER_CONFLICT_STOP";
        if(after.cards.status()!=Status.CANDIDATES && after.cards.status()!=Status.SKIPPED_VISIBLE_ITEMS
                && after.cards.status()!=Status.SKIP_COMPLETE)return "AFTER_ASSOCIATION_"+after.cards.status()+"_STOP";
        Candidate target=null;for(var c:cards.candidates())if(index(c.visualCardId())==selected)target=c;
        SkippedItem completed=null;for(var item:after.skipped)if(index(item.visualCardId())==selected)completed=item;
        if(target==null||completed==null||target.displayedPoints()!=completed.displayedNumber()
                ||delta.observedDelta()!=target.displayedPoints())return "NO_MATCHING_LOCAL_COMPLETION_STOP";
        // Complete gates return early in the locator; every other card still needs unchanged
        // pixels, OCR and geometry, not merely an increased counter.
        for(int i=0;i<a.cards().size();i++){
            Card x=a.cards().get(i),y=b.cards().get(i);
            if(i==selected-1){
                if(!x.box().equals(y.box())||!x.fullyVisible()||!y.fullyVisible())return "SCOPE_CHANGE_STOP";
                continue;
            }
            if(!x.equals(y)||!x.fullyVisible()||!y.fullyVisible())return "OTHER_CARD_GEOMETRY_CHANGED_STOP";
            if(!cardHashes.get(i).equals(after.cardHashes.get(i)))return "OTHER_CARD_PIXELS_CHANGED_STOP";
            List<Row> oldRows=inside(rows,x.box()),newRows=inside(after.rows,y.box());
            if(oldRows.size()!=newRows.size())return "OTHER_CARD_OCR_COUNT_OR_OWNERSHIP_CHANGED_STOP";
            if(!rowKeys(oldRows,x,false).equals(rowKeys(newRows,y,false)))return "OTHER_CARD_OCR_TEXT_CHANGED_STOP";
            if(!rowKeys(oldRows,x,true).equals(rowKeys(newRows,y,true)))return "OTHER_CARD_OCR_ROLE_CHANGED_STOP";
            SkippedItem oldSkip=skippedAt(skipped,i),newSkip=skippedAt(after.skipped,i);
            if((oldSkip==null)!=(newSkip==null) || oldSkip!=null &&
                    (oldSkip.displayedNumber()!=newSkip.displayedNumber() || !oldSkip.tick().equals(newSkip.tick())
                    || oldSkip.numberOrigin()!=newSkip.numberOrigin()))return "OTHER_CARD_COMPLETION_ASSOCIATION_CHANGED_STOP";
        }
        return "OBSERVED_SELECTED_CARD_MARKED_COMPLETE_UNATTRIBUTED";
    }
    private static List<Row> inside(List<Row> rows,Box box){
        List<Row> out=new ArrayList<>();for(Row row:rows)if(box.overlaps(row.box()))out.add(row);return out;
    }
    private static SkippedItem skippedAt(List<SkippedItem> items,int i){
        for(var item:items)if(index(item.visualCardId())==i+1)return item;return null;
    }
    private static boolean pointToken(String text){
        return text.matches("[+＋﹢][ \\u00a0\\u2009\\u202f]*[0-9０-９]{1,4}");
    }
    private static boolean titleRow(Row row,Card card){
        int right=card.badges().isEmpty()?card.tickMarks().get(0).diskBox().left():card.badges().get(0).box().left();
        return row.box().right()<right && row.box().top()<card.box().top()+card.box().height()*.5
            && row.text().length()>=2 && !row.text().matches("[0-9０-９+＋/／ .]+");
    }
    private static List<String> rowKeys(List<Row> rows,Card card,boolean roles){
        List<String> keys=new ArrayList<>();
        for(Row row:rows){
            String role=pointToken(row.text())?"POINT":titleRow(row,card)?"TITLE":"BODY";
            for(int i=0;i<card.badges().size();i++)if(card.badges().get(i).box().contains(row.box()))role+="_BADGE"+i;
            keys.add((roles?row.origin()+":"+role+":":"")+row.text());
        }
        Collections.sort(keys);return keys;
    }
    /** Independent validation also runs before the locator's complete-gate early return.
     * Boxes are measurements, but full-card, badge and number/tick ownership are not optional. */
    private static String rowFailure(Geometry geometry,List<Row> rows){
        if(geometry.status()!=Status.OBSERVED)return "GEOMETRY_"+geometry.status()+"_STOP";
        for(int i=0;i<rows.size();i++){
            Row row=rows.get(i);
            if(row.origin()!=Origin.STATIC_OCR || !Double.isFinite(row.confidence())
                    || row.confidence()<.85 || row.confidence()>1)return "ROW_SCORE_OR_ORIGIN_INVALID_STOP";
            for(int j=i+1;j<rows.size();j++)if(row.box().overlaps(rows.get(j).box()))return "ROW_OVERLAP_STOP";
            int owners=0;
            for(Card card:geometry.cards())if(card.box().overlaps(row.box())){
                if(!card.fullyVisible() || !card.box().contains(row.box()))return "ROW_CARD_BOUNDARY_STOP";
                owners++;
            }
            if(owners>1)return "ROW_MULTIPLE_CARD_OWNERS_STOP";
            if(owners==0&&pointToken(row.text()))return "POINT_WITHOUT_CARD_OWNER_STOP";
        }
        List<SkippedItem> completed=RewardRegionLocator.observeCompletedItems(geometry,rows);
        for(int i=0;i<geometry.cards().size();i++){
            Card card=geometry.cards().get(i);Box b=card.box();
            if(!card.fullyVisible())return "INCOMPLETE_CARD_STOP";
            for(int j=i+1;j<geometry.cards().size();j++)if(b.overlaps(geometry.cards().get(j).box()))return "CARD_OVERLAP_STOP";
            List<Row> local=inside(rows,b);
            if(!card.tickMarks().isEmpty()){
                SkippedItem done=skippedAt(completed,i);
                if(done==null)return "NUMBER_TICK_ASSOCIATION_STOP";
                int numeric=0;
                for(Row row:local){
                    if(row.text().toLowerCase(java.util.Locale.ROOT).trim().matches("(?:锁定|已锁定|登录|登入|明天|locked|sign in|tomorrow)"))return "ROW_TICK_ROLE_CONFLICT_STOP";
                    if(row.text().matches("[0-9０-９]{1,4}"))numeric++;
                    if(row.box().overlaps(done.tick().diskBox()))return "ROW_TICK_OVERLAP_STOP";
                }
                if(numeric!=1)return "NUMBER_TICK_MULTIPLICITY_STOP";
                continue;
            }
            if(card.badges().size()!=1)return "BADGE_OWNERSHIP_STOP";
            Box badge=card.badges().get(0).box(),safe=new Box(badge.left()+2,badge.top()+2,badge.right()-2,badge.bottom()-2);
            double relX=(badge.left()-b.left())/(double)b.width(),relW=badge.width()/(double)b.width();
            double relH=badge.height()/(double)b.height(),relY=(badge.centerY()-b.top())/b.height();
            if(!b.contains(badge)||relX<.70||relW<.16||relW>.26||relH<.24||relH>.51||relY<.38||relY>.62
                    ||(double)b.width()/b.height()<2||(double)b.width()/b.height()>4.5)return "BADGE_GEOMETRY_STOP";
            int tokens=0;boolean title=false;
            for(Row row:local){
                String t=row.text().toLowerCase(java.util.Locale.ROOT).trim();
                if(t.contains("已完成")||t.contains("已领取")||t.contains("completed")||t.contains("claimed"))return "ROW_COMPLETION_ROLE_CONFLICT_STOP";
                if(badge.overlaps(row.box())&&!safe.contains(row.box()))return "ROW_BADGE_BOUNDARY_STOP";
                if(badge.contains(row.box())&&t.matches("(?:小时|分钟|分|时|天|明天|锁定|已锁定|登录|登入|hour|hours|min|minutes|day|days|tomorrow|locked|sign in)"))return "ROW_BADGE_ROLE_CONFLICT_STOP";
                if(pointToken(row.text())){
                    if(!row.text().matches(".*[1-9１-９].*"))return "POINT_VALUE_INVALID_STOP";
                    tokens++;if(!safe.contains(row.box()))return "POINT_BADGE_OWNERSHIP_STOP";
                }else title|=titleRow(row,card);
            }
            if(tokens!=1)return "POINT_TOKEN_MULTIPLICITY_STOP";
            if(!title)return "CARD_TITLE_OWNERSHIP_STOP";
        }
        return null;
    }
}
