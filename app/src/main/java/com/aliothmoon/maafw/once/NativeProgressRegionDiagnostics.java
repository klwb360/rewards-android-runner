package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import com.aliothmoon.maafw.rewards.RewardRegionGate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Independent full-GRAY8 box/row diagnostics. Pixel components and row co-location
 * do not prove closed card boundaries, semantic ownership, counters or input authority. */
final class NativeProgressRegionDiagnostics {
    static final int MAX_REGIONS=8,MAX_ROW_INDICES=8,STRIP=2;
    enum State { PROCESSED, ROW_SHAPE_REJECTED, INVALID_IMAGE, CANCELED, DETECTOR_HOLD }
    enum Conflict { MULTIPLE_LABEL_ROWS, MULTIPLE_BARE_FRACTIONS, BOUNDARY_CLUE, MULTI_REGION_CLUE }
    private record Pixels(int count,int bright,long sum) {
        String mean(){return count==0?"NA":decimal(sum/(double)count);}
    }
    private record Side(Pixels inner,Pixels outer,boolean outerClipped,int support,int paired) {
        String summary(){return inner.mean()+"/"+outer.mean()+"/"+(inner.count()==0||outer.count()==0?"NA":decimal(inner.sum()/(double)inner.count()-outer.sum()/(double)outer.count()))+"@"+inner.count()+","+outer.count()+","+outerClipped+" support="+(paired==0?"NA":decimal(support/(double)paired))+" supportPairs="+support+"/"+paired;}
    }
    private record Region(int index,Box box,boolean frameClipped,Pixels pixels,List<Side> sides,
                          int contained,int cross,int boundary,int multi,int lowInside,int lowBoundary,
                          int readingLabels,int searchLabels,int genericRead,int genericSearch,int fractions,int malformed,List<Conflict> conflicts,List<Integer> containedRows,List<Integer> boundaryRows) {
        private Region {conflicts=List.copyOf(conflicts);sides=List.copyOf(sides);containedRows=List.copyOf(containedRows);boundaryRows=List.copyOf(boundaryRows);}
        String summary(){return "region="+index+" box="+box.left()+","+box.top()+","+box.right()+","+box.bottom()+" bboxFrameClipped="+frameClipped
            +" bboxBrightRatio="+decimal(pixels.bright()/(double)pixels.count())+" brightPixels="+pixels.bright()+" bboxPixels="+pixels.count()
            +" edgeL="+sides.get(0).summary()+" edgeT="+sides.get(1).summary()+" edgeR="+sides.get(2).summary()+" edgeB="+sides.get(3).summary()
            +" contained="+contained+" crossBoundary="+cross+" boundaryTouchOrCross="+boundary+" multiHit="+multi+" lowInside="+lowInside+" lowBoundary="+lowBoundary
            +" readingLabelRows="+readingLabels+" searchLabelRows="+searchLabels+" genericReadRows="+genericRead+" genericSearchRows="+genericSearch+" bareFractionRows="+fractions+" malformedFractionRows="+malformed+" titleFractionConflictCount="+conflicts.size()+" titleFractionConflictFlags="+conflicts+" readingHint="+(readingLabels>0?"READING_CLUE_PRESENT_HOLD":"HOLD")
            +" containedRowIndices="+containedRows+" containedIndicesTruncated="+(contained>containedRows.size())
            +" boundaryRowIndices="+boundaryRows+" boundaryIndicesTruncated="+(boundary>boundaryRows.size());}
    }
    private final NativeProgressLayoutDiagnostics layout;private final int[] grayBins;private final int readingRegions,searchRegions;private final int overlapPairs,nestedPairs;private final int[] membershipTotals,conflictTotals;
    private final State state;private final OcrRowsAssessment quality;private final RewardRegionLocator.Status detector;
    private final List<Region> regions;private final int total,contained,cross,boundary,multi,outside,lowInside,lowBoundary,lowOutside;
    private NativeProgressRegionDiagnostics(State state,OcrRowsAssessment quality,RewardRegionLocator.Status detector,List<Region> regions,int total,
            int contained,int cross,int boundary,int multi,int outside,int lowInside,int lowBoundary,int lowOutside,NativeProgressLayoutDiagnostics layout,int[] grayBins,int readingRegions,int searchRegions,int overlapPairs,int nestedPairs,int[] membershipTotals,int[] conflictTotals){
        this.membershipTotals=membershipTotals.clone();this.conflictTotals=conflictTotals.clone();this.overlapPairs=overlapPairs;this.nestedPairs=nestedPairs;this.layout=layout;this.grayBins=grayBins.clone();this.readingRegions=readingRegions;this.searchRegions=searchRegions;
        this.state=state;this.quality=quality;this.detector=detector;this.regions=List.copyOf(regions);this.total=total;
        this.contained=contained;this.cross=cross;this.boundary=boundary;this.multi=multi;this.outside=outside;this.lowInside=lowInside;this.lowBoundary=lowBoundary;this.lowOutside=lowOutside;
    }
    private static NativeProgressRegionDiagnostics hold(State state,OcrRowsAssessment quality,RewardRegionLocator.Status detector){return new NativeProgressRegionDiagnostics(state,quality,detector,List.of(),0,0,0,0,0,0,0,0,0,null,new int[3],0,0,0,0,new int[4],new int[Conflict.values().length]);}
    static NativeProgressRegionDiagnostics observe(FrameStructureEvidence.Binding actual,FrameStructureEvidence.Binding expected,
            byte[] gray,List<FrameStructureEvidence.Line> fullRows,int width,int height,BooleanSupplier cancel){
        var quality=OcrRowsAssessment.assess(actual,expected,fullRows,width,height);
        if(!quality.shapeValid())return hold(State.ROW_SHAPE_REJECTED,quality,null);
        if(cancel==null||width<32||height<32||gray==null||gray.length!=(long)width*height)return hold(State.INVALID_IMAGE,quality,null);
        try{
            if(cancel.getAsBoolean())return hold(State.CANCELED,quality,null);
            var layout=NativeProgressLayoutDiagnostics.observe(actual,expected,fullRows,width,height);
            int[] bins=new int[3];for(int i=0;i<gray.length;i++){if((i&1023)==0&&cancel.getAsBoolean())return hold(State.CANCELED,quality,null);int value=gray[i]&255;bins[value>=252?2:value>=244?1:0]++;}
            // Descriptive algorithm tag only. It is not an owner credential and is never emitted.
            var key=new RewardRegionGate.Key(RewardRegionGate.Source.REAL_DISPLAY_NATIVE,"native-region-diagnostic",1,"full-gray");
            var geometry=RewardRegionLocator.observeVisibleMoreGray8(key,gray,width,height,cancel);
            if(cancel.getAsBoolean()||geometry.status()==RewardRegionLocator.Status.CANCELED)return hold(State.CANCELED,quality,geometry.status());
            if(geometry.status()!=RewardRegionLocator.Status.OBSERVED&&geometry.status()!=RewardRegionLocator.Status.NO_CARDS)return hold(State.DETECTOR_HOLD,quality,geometry.status());
            var cards=geometry.cards();int nr=cards.size(),nt=fullRows.size();
            int overlapPairs=0,nestedPairs=0;
            for(int a=0;a<nr;a++)for(int b=a+1;b<nr;b++){
                if(cancel.getAsBoolean())throw new Canceled();Box x=cards.get(a).box(),y=cards.get(b).box();
                if(x.overlaps(y))overlapPairs++;if(x.contains(y)||y.contains(x))nestedPairs++;
            }
            int[] hits=new int[nt],contains=new int[nt];boolean[] crosses=new boolean[nt],boundaries=new boolean[nt];
            NativeProgressLayoutDiagnostics.RegionTag[] tags=new NativeProgressLayoutDiagnostics.RegionTag[nt];
            int contained=0,cross=0,boundary=0,multi=0,outside=0,lowInside=0,lowBoundary=0,lowOutside=0;
            for(int rowIndex=0;rowIndex<nt;rowIndex++){
                if(cancel.getAsBoolean())return hold(State.CANCELED,quality,geometry.status());
                var row=fullRows.get(rowIndex);Box rb=rowBox(row);tags[rowIndex]=NativeProgressLayoutDiagnostics.regionTag(row.text());
                for(var card:cards){Box box=card.box();if(!box.overlaps(rb))continue;hits[rowIndex]++;boolean inside=box.contains(rb);if(inside)contains[rowIndex]++;else crosses[rowIndex]=true;
                    if(!inside||touches(box,rb))boundaries[rowIndex]=true;}
                if(contains[rowIndex]>0)contained++;if(crosses[rowIndex])cross++;if(boundaries[rowIndex])boundary++;if(hits[rowIndex]>1)multi++;if(hits[rowIndex]==0)outside++;
                if(row.score()<.8){if(hits[rowIndex]==0)lowOutside++;else if(boundaries[rowIndex])lowBoundary++;else lowInside++;}
            }
            List<Region> emitted=new ArrayList<>();int readingRegions=0,searchRegions=0;int[] memberships=new int[4],conflictCounts=new int[Conflict.values().length];
            // Traverse every detected region even after the independent eight-region output cap.
            for(int regionIndex=0;regionIndex<nr;regionIndex++){
                if(cancel.getAsBoolean())return hold(State.CANCELED,quality,geometry.status());
                var card=cards.get(regionIndex);Box box=card.box();int in=0,cr=0,bd=0,mh=0,li=0,lb=0,reading=0,search=0,generic=0,genericSearch=0,fractions=0,malformed=0,clueBoundary=0,clueMulti=0;
                List<Integer> inRows=new ArrayList<>(),bdRows=new ArrayList<>();
                for(int rowIndex=0;rowIndex<nt;rowIndex++){
                    var row=fullRows.get(rowIndex);Box rb=rowBox(row);if(!box.overlaps(rb))continue;boolean inside=box.contains(rb),boundaryRow=!inside||touches(box,rb);var tag=tags[rowIndex];
                    boolean clue=readingLabel(tag)||searchLabel(tag)||tag!=null&&(tag.role()==NativeProgressLayoutDiagnostics.Role.BARE_FRACTION||tag.role()==NativeProgressLayoutDiagnostics.Role.MALFORMED_FRACTION);
                    if(clue&&boundaryRow)clueBoundary++;if(clue&&hits[rowIndex]>1)clueMulti++;
                    if(inside){in++;if(inRows.size()<MAX_ROW_INDICES)inRows.add(rowIndex);if(readingLabel(tag))reading++;if(searchLabel(tag))search++;
                        if(tag!=null&&(tag.role()==NativeProgressLayoutDiagnostics.Role.GENERIC_READ_TOKEN||tag.role()==NativeProgressLayoutDiagnostics.Role.OTHER_READ_CONTAINING))generic++;
                        if(tag!=null&&(tag.role()==NativeProgressLayoutDiagnostics.Role.GENERIC_SEARCH_TOKEN||tag.role()==NativeProgressLayoutDiagnostics.Role.OTHER_SEARCH_CONTAINING))genericSearch++;
                        if(tag!=null&&tag.role()==NativeProgressLayoutDiagnostics.Role.MALFORMED_FRACTION)malformed++;
                        if(tag!=null&&tag.role()==NativeProgressLayoutDiagnostics.Role.BARE_FRACTION)fractions++;}
                    else cr++;
                    if(boundaryRow){bd++;if(bdRows.size()<MAX_ROW_INDICES)bdRows.add(rowIndex);}
                    if(hits[rowIndex]>1)mh++;if(row.score()<.8){if(boundaryRow)lb++;else li++;}
                }
                if(reading>0)readingRegions++;if(search>0)searchRegions++;
                List<Conflict> conflicts=new ArrayList<>();if(reading+search>1)conflicts.add(Conflict.MULTIPLE_LABEL_ROWS);if(fractions>1)conflicts.add(Conflict.MULTIPLE_BARE_FRACTIONS);
                if(clueBoundary>0)conflicts.add(Conflict.BOUNDARY_CLUE);if(clueMulti>0)conflicts.add(Conflict.MULTI_REGION_CLUE);
                memberships[0]+=search;memberships[1]+=reading;memberships[2]+=fractions;memberships[3]+=malformed;
                for(Conflict conflict:conflicts)conflictCounts[conflict.ordinal()]++;
                if(regionIndex<MAX_REGIONS){
                    Pixels pixels=measure(gray,width,box,cancel);List<Side> sides=sides(gray,width,height,box,cancel);
                    emitted.add(new Region(regionIndex,box,!card.fullyVisible(),pixels,sides,in,cr,bd,mh,li,lb,reading,search,generic,genericSearch,fractions,malformed,conflicts,inRows,bdRows));
                }
            }
            if(cancel.getAsBoolean())return hold(State.CANCELED,quality,geometry.status());
            return new NativeProgressRegionDiagnostics(State.PROCESSED,quality,geometry.status(),emitted,nr,contained,cross,boundary,multi,outside,lowInside,lowBoundary,lowOutside,layout,bins,readingRegions,searchRegions,overlapPairs,nestedPairs,memberships,conflictCounts);
        }catch(Canceled ignored){return hold(State.CANCELED,quality,RewardRegionLocator.Status.CANCELED);}
        catch(RuntimeException unavailable){return hold(State.DETECTOR_HOLD,quality,null);}
    }
    private static Box rowBox(FrameStructureEvidence.Line row){return new Box(row.x(),row.y(),row.x()+row.width(),row.y()+row.height());}
    private static boolean touches(Box box,Box row){return row.left()==box.left()||row.right()==box.right()||row.top()==box.top()||row.bottom()==box.bottom();}
    private static boolean readingLabel(NativeProgressLayoutDiagnostics.RegionTag tag){
        if(tag==null)return false;return switch(tag.alias()){
            case READ_TO_EARN,READ_EARN_ZH,READ_AND_EARN,READ_POINTS_HYP_ZH,READ_EARN_POINTS_HYP_ZH->true;
            default->false;
        };
    }
    private static boolean searchLabel(NativeProgressLayoutDiagnostics.RegionTag tag){
        if(tag==null)return false;return switch(tag.alias()){
            case SEARCH_TO_EARN,SEARCH_EARN_ZH,SEARCH_POINTS,SEARCH_POINTS_ZH,SEARCH_AND_EARN,SEARCH_WITH_BING,SEARCH_ON_BING,SEARCH_THE_WEB,SEARCH_NOW,SEARCH_TO_EARN_POINTS,SEARCH_POINTS_HYP_ZH,SEARCH_EARN_POINTS_HYP_ZH,SEARCH_VIA_POINTS_HYP_ZH->true;
            default->false;
        };
    }
    private static final class Canceled extends RuntimeException {private static final long serialVersionUID=1L;}
    private static Pixels measure(byte[] gray,int width,Box box,BooleanSupplier cancel){
        int count=0,bright=0;long sum=0;
        for(int y=box.top();y<box.bottom();y++)for(int x=box.left();x<box.right();x++){
            if((count&1023)==0&&cancel.getAsBoolean())throw new Canceled();int value=gray[y*width+x]&255;count++;sum+=value;if(value>=252)bright++;
        }
        return new Pixels(count,bright,sum);
    }
    private static List<Side> sides(byte[] gray,int w,int h,Box b,BooleanSupplier cancel){
        int ix=Math.min(STRIP,b.width()),iy=Math.min(STRIP,b.height());List<Side> result=new ArrayList<>();
        Box[] inner={new Box(b.left(),b.top(),b.left()+ix,b.bottom()),new Box(b.left(),b.top(),b.right(),b.top()+iy),new Box(b.right()-ix,b.top(),b.right(),b.bottom()),new Box(b.left(),b.bottom()-iy,b.right(),b.bottom())};
        Box[] outer={new Box(Math.max(0,b.left()-STRIP),b.top(),b.left(),b.bottom()),new Box(b.left(),Math.max(0,b.top()-STRIP),b.right(),b.top()),new Box(b.right(),b.top(),Math.min(w,b.right()+STRIP),b.bottom()),new Box(b.left(),b.bottom(),b.right(),Math.min(h,b.bottom()+STRIP))};
        for(int i=0;i<4;i++){Pixels in=measure(gray,w,inner[i],cancel),out=measure(gray,w,outer[i],cancel);int expected=STRIP*(i%2==0?b.height():b.width());int[] support=edgeSupport(gray,w,h,b,i,cancel);result.add(new Side(in,out,out.count()<expected,support[0],support[1]));}
        return result;
    }
    // Compare equally distant original pixels on opposite sides of each bbox edge.
    // Only in-frame pairs count; absent outer pixels are not invented edge evidence.
    private static int[] edgeSupport(byte[] gray,int w,int h,Box b,int side,BooleanSupplier cancel){
        int supported=0,paired=0,length=side%2==0?b.height():b.width();
        for(int t=0;t<length;t++)for(int d=0;d<STRIP;d++){
            if((paired&1023)==0&&cancel.getAsBoolean())throw new Canceled();
            int ix,iy,ox,oy;
            if(side==0){ix=b.left()+d;ox=b.left()-1-d;iy=oy=b.top()+t;}
            else if(side==2){ix=b.right()-1-d;ox=b.right()+d;iy=oy=b.top()+t;}
            else if(side==1){iy=b.top()+d;oy=b.top()-1-d;ix=ox=b.left()+t;}
            else{iy=b.bottom()-1-d;oy=b.bottom()+d;ix=ox=b.left()+t;}
            if(ix<b.left()||ix>=b.right()||iy<b.top()||iy>=b.bottom()||ox<0||ox>=w||oy<0||oy>=h)continue;
            paired++;if(Math.abs((gray[iy*w+ix]&255)-(gray[oy*w+ox]&255))>=16)supported++;
        }
        return new int[]{supported,paired};
    }
    private static String decimal(double value){return String.format(Locale.ROOT,"%.4f",value);}
    String summary(boolean qualified){
        String head="structureEvidence="+(qualified&&state==State.PROCESSED?"QUALIFIED":"UNQUALIFIED")+" state="+state+" detector="+(detector==null?"NOT_AVAILABLE":detector)
            +" counterAuthority=false actionAuthority=false semanticState=HOLD boundaryTopology=UNPROVEN rowQuality="+quality.reason()+" nativeRows="+quality.rows()+" lowConfidenceRows="+quality.lowConfidence();
        if(!qualified||state!=State.PROCESSED)return head+" regions=SUPPRESSED";
        StringBuilder out=new StringBuilder(head).append(" regionTotal=").append(total).append(" emitted=").append(regions.size()).append(" truncated=").append(total>regions.size())
            .append(" containedRows=").append(contained).append(" crossBoundaryRows=").append(cross).append(" boundaryTouchOrCrossRows=").append(boundary)
            .append(" multiHitRows=").append(multi).append(" outsideRows=").append(outside).append(" lowInsideRows=").append(lowInside).append(" lowBoundaryRows=").append(lowBoundary).append(" lowOutsideRows=").append(lowOutside)
            .append(" readingClueRegionTotal=").append(readingRegions).append(" searchClueRegionTotal=").append(searchRegions)
            .append(" frameGray0to243=").append(grayBins[0]).append(" frameGray244to251=").append(grayBins[1]).append(" frameGray252to255=").append(grayBins[2]).append(" detectorBrightThreshold=252_UNCHANGED")
            .append(" regionBoxOverlapPairs=").append(overlapPairs).append(" regionBoxNestedPairs=").append(nestedPairs).append(" labelsClueOnly=true labelProvenance=MIXED_SOURCE_AND_HYPOTHESIS boundaryDefinition=OVERLAPPING_ROWS_ONLY supportDefinition=PAIRED_ABS_DIFF_GE_16")
            .append(" boxCoordinates=HALF_OPEN edgeFeatures=INNER_MEAN/OUTER_MEAN/DIFFERENCE@INNER_COUNT,OUTER_COUNT,CLIPPED stripPixels=2");
        out.append(" allRegionMembershipTotalsSearchLabel=").append(membershipTotals[0]).append(" allRegionMembershipTotalsReadingLabel=").append(membershipTotals[1])
            .append(" allRegionMembershipTotalsBareFraction=").append(membershipTotals[2]).append(" allRegionMembershipTotalsMalformedFraction=").append(membershipTotals[3])
            .append(" membershipSemantics=MAY_DOUBLECOUNT_OVERLAP_NO_OWNERSHIP");
        for(Conflict conflict:Conflict.values())out.append(" conflictRegionTotal_").append(conflict).append('=').append(conflictTotals[conflict.ordinal()]);
        for(Region region:regions)out.append('\n').append(region.summary());
        if(layout!=null)out.append("\nsameFrameLayoutProjection=true\n").append(layout.summary(true));return out.toString();
    }
}
