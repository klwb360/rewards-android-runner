package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.List;

/** Fixed local ROI measurements only. No OCR string, text hash, raster or native handle is retained. */
final class LocalCardRoiDiagnostic {
    static final int ROI_COUNT=9,MAX_ROWS=128,MAX_SAMPLES=4,SCORE_BUCKET_SCALE=20;
    enum Role { TITLE, NUMBER, DAY1, DAY2, DAY3, DAY4, DAY5, DAY6, DAY7 }
    enum State { PLANNED, POST_ATTEMPTED, POSTED, TERMINAL, DETAIL_READING, DETAIL_DECODED, DETAIL_REJECTED }
    enum CountClass { UNAVAILABLE, ZERO, ONE, MULTIPLE }
    enum ScoreClass { INVALID, BELOW_085, AT_LEAST_085 }
    enum TokenClass { INVALID, BLANK, EXPECTED_TITLE, POSITIVE_NUMBER, EXPECTED_DAY, OTHER_DAY, OTHER_TEXT }
    enum Relation { INVALID, OUTSIDE, TOUCHES, INSIDE }
    record RowSample(int index,int x,int y,int width,int height,int scoreBucket,
            ScoreClass scoreClass,TokenClass tokenClass,Relation relation) { }
    record RoiReport(int index,Role role,Box roi,State state,boolean postAttempted,boolean posted,
            boolean completed,int nativeStatus,boolean decoded,OcrDetailDecoder.Parse parse,
            OcrRowsAssessment.Reason quality,int rawCount,boolean rawCountCapped,int deliveredCount,
            boolean deliveryObserved,CountClass countClass,int minScoreBucket,int maxScoreBucket,
            int lowScoreRows,int invalidScoreRows,int outsideRows,int touchingRows,int invalidBoxRows,
            int tokenMismatchRows,List<RowSample> samples,boolean samplesTruncated) {
        RoiReport { samples=List.copyOf(samples); }
        private boolean problem(){return !completed||!decoded||parse!=OcrDetailDecoder.Parse.OK
                ||quality!=OcrRowsAssessment.Reason.VALID||rawCount!=1||deliveredCount!=1
                ||!deliveryObserved||lowScoreRows>0||invalidScoreRows>0||outsideRows>0
                ||touchingRows>0||invalidBoxRows>0||tokenMismatchRows>0;}
    }
    static final class Report {
        private final int width,height;
        private final List<RoiReport> rois;
        private Report(int width,int height,List<RoiReport> rois){this.width=width;this.height=height;this.rois=List.copyOf(rois);}
        List<RoiReport> rois(){return rois;}
        int attemptedQueries(){return (int)rois.stream().filter(RoiReport::postAttempted).count();}
        int postedQueries(){return (int)rois.stream().filter(RoiReport::posted).count();}
        int completedQueries(){return (int)rois.stream().filter(RoiReport::completed).count();}
        int decodedQueries(){return (int)rois.stream().filter(RoiReport::decoded).count();}
        String firstProblemRole(){for(var roi:rois)if(roi.problem())return roi.role().name();return "NONE";}
        List<Integer> rawCounts(){List<Integer> values=new ArrayList<>();for(var r:rois)values.add(r.rawCount());return List.copyOf(values);}
        List<Integer> deliveredCounts(){List<Integer> values=new ArrayList<>();for(var r:rois)values.add(r.deliveredCount());return List.copyOf(values);}
        String summary(){
            StringBuilder out=new StringBuilder("roiDiagnosticVersion=1 roiAttempts=").append(attemptedQueries())
                    .append(" roiPosted=").append(postedQueries()).append(" roiCompleted=").append(completedQueries())
                    .append(" roiDecoded=").append(decodedQueries()).append(" firstProblemRole=").append(firstProblemRole());
            for(var roi:rois)out.append(" roi").append(roi.index()).append("Role=").append(roi.role())
                    .append(" roi").append(roi.index()).append("Raw=").append(roi.rawCount())
                    .append(" roi").append(roi.index()).append("Delivered=").append(roi.deliveredCount())
                    .append(" roi").append(roi.index()).append("Count=").append(roi.countClass());
            return out.toString();
        }
        String json(){
            StringBuilder out=new StringBuilder("{\"version\":1,\"width\":").append(width).append(",\"height\":").append(height)
                    .append(",\"coordinateSpace\":\"CROP_LOCAL_ORIGINAL_NATIVE_NO_OFFSET\",\"scoreBucketScale\":20,\"minimumScore\":0.85")
                    .append(",\"attemptedQueries\":").append(attemptedQueries()).append(",\"postedQueries\":").append(postedQueries())
                    .append(",\"completedQueries\":").append(completedQueries()).append(",\"decodedQueries\":").append(decodedQueries())
                    .append(",\"firstProblemRole\":\"").append(firstProblemRole()).append("\",\"rois\":[");
            for(int i=0;i<rois.size();i++){
                if(i>0)out.append(',');var r=rois.get(i);Box b=r.roi();
                out.append("{\"index\":").append(r.index()).append(",\"role\":\"").append(r.role())
                        .append("\",\"roi\":[").append(b.left()).append(',').append(b.top()).append(',').append(b.width()).append(',').append(b.height())
                        .append("],\"state\":\"").append(r.state()).append("\",\"postAttempted\":").append(r.postAttempted())
                        .append(",\"posted\":").append(r.posted()).append(",\"completed\":").append(r.completed())
                        .append(",\"nativeStatus\":").append(r.nativeStatus()).append(",\"decoded\":").append(r.decoded())
                        .append(",\"parse\":\"").append(fixed(r.parse())).append("\",\"quality\":\"").append(fixed(r.quality()))
                        .append("\",\"rawCount\":").append(r.rawCount()).append(",\"rawCountCapped\":").append(r.rawCountCapped())
                        .append(",\"deliveredCount\":").append(r.deliveredCount()).append(",\"deliveryObserved\":").append(r.deliveryObserved())
                        .append(",\"rawCountClass\":\"").append(count(r.rawCount())).append("\",\"countClass\":\"").append(r.countClass()).append("\",\"minScoreBucket\":").append(r.minScoreBucket())
                        .append(",\"maxScoreBucket\":").append(r.maxScoreBucket()).append(",\"lowScoreRows\":").append(r.lowScoreRows())
                        .append(",\"invalidScoreRows\":").append(r.invalidScoreRows()).append(",\"outsideRows\":").append(r.outsideRows())
                        .append(",\"touchingRows\":").append(r.touchingRows()).append(",\"invalidBoxRows\":").append(r.invalidBoxRows())
                        .append(",\"tokenMismatchRows\":").append(r.tokenMismatchRows()).append(",\"samplesTruncated\":").append(r.samplesTruncated())
                        .append(",\"samples\":[");
                for(int at=0;at<r.samples().size();at++){
                    if(at>0)out.append(',');var row=r.samples().get(at);
                    out.append("{\"index\":").append(row.index()).append(",\"box\":[").append(row.x()).append(',').append(row.y())
                            .append(',').append(row.width()).append(',').append(row.height()).append("],\"scoreBucket\":").append(row.scoreBucket())
                            .append(",\"scoreClass\":\"").append(row.scoreClass()).append("\",\"tokenClass\":\"").append(row.tokenClass())
                            .append("\",\"relation\":\"").append(row.relation()).append("\"}");
                }
                out.append("]}");
            }
            return out.append("]}\n").toString();
        }
    }
    private static final class Slot {
        final Role role;final Box roi;
        State state=State.PLANNED;
        boolean postAttempted,posted,completed,decoded,deliveryObserved;
        int nativeStatus=-1,rawCount=-1,deliveredCount=-1;
        boolean rawCountCapped;
        OcrDetailDecoder.Parse parse;
        OcrRowsAssessment.Reason quality;
        int minBucket=-1,maxBucket=-1,low,invalidScore,outside,touching,invalidBox,mismatch;
        List<RowSample> samples=List.of();
        Slot(Role role,Box roi){this.role=role;this.roi=roi;}
    }
    private final int width,height;
    private final Slot[] slots=new Slot[ROI_COUNT];
    LocalCardRoiDiagnostic(List<Box> rois,int width,int height){
        if(rois==null||rois.size()!=ROI_COUNT||width<1||height<1||width>4096||height>4096
                ||(long)width*height>LocalCardMockPlan.MAX_PIXELS)throw new IllegalArgumentException("Bounded fixed ROI plan required");
        this.width=width;this.height=height;
        for(int i=0;i<ROI_COUNT;i++){Box b=rois.get(i);
            if(b==null||b.left()<0||b.top()<0||b.right()>width||b.bottom()>height||b.width()<1||b.height()<1)
                throw new IllegalArgumentException("Crop-local ROI required");
            slots[i]=new Slot(Role.values()[i],b);
        }
    }
    private Slot slot(int index){if(index<0||index>=ROI_COUNT)throw new IllegalArgumentException("Fixed ROI index required");return slots[index];}
    void attempted(int index){Slot s=slot(index);if(s.state!=State.PLANNED)throw new IllegalStateException("ROI already attempted");s.postAttempted=true;s.state=State.POST_ATTEMPTED;}
    void posted(int index){Slot s=slot(index);if(s.state!=State.POST_ATTEMPTED)throw new IllegalStateException("ROI post order");s.posted=true;s.state=State.POSTED;}
    void completed(int index,int nativeStatus){Slot s=slot(index);
        if(s.state!=State.POSTED||(nativeStatus!=3000&&nativeStatus!=4000))throw new IllegalStateException("ROI completion order");
        s.completed=true;s.nativeStatus=nativeStatus;s.state=State.TERMINAL;
    }
    void detailStarted(int index){Slot s=slot(index);if(s.state!=State.TERMINAL)throw new IllegalStateException("ROI detail order");s.state=State.DETAIL_READING;}
    void rows(int index,List<FrameStructureEvidence.Line> lines){
        Slot s=slot(index);if(s.state!=State.DETAIL_READING||s.deliveryObserved)throw new IllegalStateException("Single ROI delivery required");
        s.deliveryObserved=true;if(lines==null||lines.size()>MAX_ROWS)return;s.deliveredCount=lines.size();
        List<RowSample> samples=new ArrayList<>();
        for(int i=0;i<lines.size();i++){
            var row=lines.get(i);ScoreClass score=score(row);int bucket=score==ScoreClass.INVALID?-1:(int)Math.floor(row.score()*SCORE_BUCKET_SCALE);
            if(score==ScoreClass.INVALID)s.invalidScore++;else{
                s.minBucket=s.minBucket<0?bucket:Math.min(s.minBucket,bucket);s.maxBucket=Math.max(s.maxBucket,bucket);
                if(score==ScoreClass.BELOW_085)s.low++;
            }
            Relation relation=relation(s.roi,row);
            if(relation==Relation.INVALID)s.invalidBox++;else if(relation==Relation.OUTSIDE)s.outside++;else if(relation==Relation.TOUCHES)s.touching++;
            TokenClass token=token(s.role,row);if(!expected(s.role,token))s.mismatch++;
            if(samples.size()<MAX_SAMPLES){boolean valid=validBox(row);
                samples.add(new RowSample(i+1,valid?row.x():-1,valid?row.y():-1,valid?row.width():-1,valid?row.height():-1,bucket,score,token,relation));}
        }
        s.samples=List.copyOf(samples);
    }
    void decoded(int index,OcrDetailDecoder.Result result){
        Slot s=slot(index);if(s.state!=State.DETAIL_READING||s.decoded||result==null)throw new IllegalStateException("Single ROI decode receipt required");
        s.decoded=true;s.parse=result.getParse();var q=result.getQuality();
        if(q!=null){s.quality=q.reason();s.rawCount=Math.min(MAX_ROWS+1,Math.max(0,q.rows()));s.rawCountCapped=q.rows()>MAX_ROWS;}
        else if(s.parse==OcrDetailDecoder.Parse.ROW_LIMIT){s.rawCount=MAX_ROWS+1;s.rawCountCapped=true;}
        s.state=result.transportAccepted()?State.DETAIL_DECODED:State.DETAIL_REJECTED;
    }
    void sizeRejected(int index){Slot s=slot(index);if(s.state!=State.DETAIL_READING)throw new IllegalStateException("ROI detail size order");s.parse=OcrDetailDecoder.Parse.SIZE;s.state=State.DETAIL_REJECTED;}
    Report snapshot(){
        List<RoiReport> values=new ArrayList<>();
        for(int i=0;i<ROI_COUNT;i++){Slot s=slots[i];
            values.add(new RoiReport(i+1,s.role,s.roi,s.state,s.postAttempted,s.posted,s.completed,s.nativeStatus,s.decoded,s.parse,s.quality,
                    s.rawCount,s.rawCountCapped,s.deliveredCount,s.deliveryObserved,count(s.deliveredCount),s.minBucket,s.maxBucket,
                    s.low,s.invalidScore,s.outside,s.touching,s.invalidBox,s.mismatch,s.samples,s.deliveredCount>MAX_SAMPLES));
        }
        return new Report(width,height,values);
    }
    private static CountClass count(int n){return n<0?CountClass.UNAVAILABLE:n==0?CountClass.ZERO:n==1?CountClass.ONE:CountClass.MULTIPLE;}
    private static String fixed(Enum<?> value){return value==null?"NOT_OBSERVED":value.name();}
    private static ScoreClass score(FrameStructureEvidence.Line row){return row==null||!Double.isFinite(row.score())||row.score()<0||row.score()>1
            ?ScoreClass.INVALID:row.score()<.85?ScoreClass.BELOW_085:ScoreClass.AT_LEAST_085;}
    private boolean validBox(FrameStructureEvidence.Line row){return row!=null&&row.x()>=0&&row.y()>=0&&row.width()>0&&row.height()>0
            &&(long)row.x()+row.width()<=width&&(long)row.y()+row.height()<=height;}
    private Relation relation(Box roi,FrameStructureEvidence.Line row){
        if(!validBox(row))return Relation.INVALID;Box b=new Box(row.x(),row.y(),row.x()+row.width(),row.y()+row.height());
        if(!roi.contains(b))return Relation.OUTSIDE;
        return b.left()==roi.left()||b.top()==roi.top()||b.right()==roi.right()||b.bottom()==roi.bottom()?Relation.TOUCHES:Relation.INSIDE;
    }
    private static TokenClass token(Role role,FrameStructureEvidence.Line row){
        if(row==null||row.text()==null||row.text().length()>128)return TokenClass.INVALID;
        String text=row.text();if(blank(text))return TokenClass.BLANK;
        if(role==Role.TITLE&&text.equals("签入"))return TokenClass.EXPECTED_TITLE;
        if(role.ordinal()>=2&&day(text,role.ordinal()-1))return TokenClass.EXPECTED_DAY;
        for(int day=1;day<=7;day++)if(day(text,day))return TokenClass.OTHER_DAY;
        if(text.matches("[0-9]{1,4}")&&Integer.parseInt(text)>0)return TokenClass.POSITIVE_NUMBER;
        return TokenClass.OTHER_TEXT;
    }
    private static boolean expected(Role role,TokenClass token){return role==Role.TITLE?token==TokenClass.EXPECTED_TITLE
            :role==Role.NUMBER?token==TokenClass.POSITIVE_NUMBER:token==TokenClass.EXPECTED_DAY;}
    private static boolean day(String text,int day){return text.equals("Day "+day)||text.equals("Day"+day)||text.equals("第"+day+"天");}
    private static boolean blank(String text){for(int i=0;i<text.length();){int c=text.codePointAt(i);if(!Character.isWhitespace(c))return false;i+=Character.charCount(c);}return true;}
}
