package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Correlates fresh card pixels and native, untranslated OCR coordinates with one structural frame. */
final class NewsCardOcrPlan {
    private final NewsFeedModel.Frame frame;
    private final Box crop, card;
    private final List<Box> rois;
    private final String imageHash;
    private final BooleanSupplier current;
    private final boolean expanded;
    private String entryDiagnostic="";
    private boolean claimed, finished;

    record Evidence(NewsFeedModel.Kind kind,String key,int x,int y,String reason) {
        static Evidence hold(String reason){return new Evidence(NewsFeedModel.Kind.UNKNOWN,"",-1,-1,reason);}
    }

    static NewsCardOcrPlan prepare(NewsFeedModel.Frame frame,Box crop,int[] pixels,byte[] gray,
                                   boolean expanded,BooleanSupplier current) {
        if(frame==null||crop==null||current==null||!current.getAsBoolean()
                ||!NewsFeedModel.positive(crop)||pixels==null||gray==null
                ||(long)crop.width()*crop.height()!=pixels.length||pixels.length!=gray.length)return null;
        boolean entry=frame.page()==NewsFeedModel.Page.REWARDS;
        if(!entry && (frame.page()!=NewsFeedModel.Page.FEED || frame.card()==null))return null;
        Box card=entry?frame.entry():frame.card().box();
        if(!NewsFeedModel.positive(card)||frame.entryComplete()||!crop.contains(card)||!frame.viewport().contains(crop))return null;
        int outer=Math.max(6,card.width()/40);
        if(card.left()-crop.left()>outer||card.top()-crop.top()>outer
                ||crop.right()-card.right()>outer||crop.bottom()-card.bottom()>outer)return null;
        Box local=local(card,crop);
        if(!border(pixels,crop.width(),crop.height(),local))return null;
        List<Box> rois = new ArrayList<>();
        if(entry) {
            rois.add(ReadingEntryOcr.window(gray,crop.width(),local,expanded));
        } else {
            var candidate=frame.card();
            if(!NewsFeedModel.positive(candidate.source())||!card.contains(candidate.source()))return null;
            int padY=Math.max(4,card.height()/(expanded?22:45));
            Box src=candidate.source();
            int left=card.left()+Math.max(1,card.width()/90);
            int right=card.right()-Math.max(1,card.width()/40);
            int top=Math.max(card.top()+1,src.top()-padY);
            int bottom=Math.min(card.bottom()-1,src.bottom()+padY);
            if(candidate.title()!=null)bottom=Math.min(bottom,candidate.title().top());
            Box sr=new Box(left,top,right,bottom);
            if(!NewsFeedModel.positive(sr))return null;
            rois.add(local(sr,crop));
            if(candidate.kind()==NewsFeedModel.Kind.ARTICLE){
                Box title=candidate.title();
                if(!NewsFeedModel.positive(title)||!card.contains(title))return null;
                Box tr=new Box(Math.max(left,title.left()-Math.max(7,card.width()/32)),
                        Math.max(src.bottom(),title.top()-Math.max(7,card.height()/32)),
                        Math.min(right,title.right()+Math.max(7,card.width()/32)),
                        Math.min(card.bottom()-1,title.bottom()+Math.max(7,card.height()/32)));
                if(!NewsFeedModel.positive(tr))return null;
                rois.add(local(tr,crop));
            }
        }
        return new NewsCardOcrPlan(frame,crop,card,rois,CardNativeMockReceipt.digest(gray),current,expanded);
    }
    private NewsCardOcrPlan(NewsFeedModel.Frame frame,Box crop,Box card,List<Box>rois,String hash,BooleanSupplier current,
                            boolean expanded){
        this.frame=frame;this.crop=crop;this.card=card;this.rois=List.copyOf(rois);this.imageHash=hash;this.current=current;
        this.expanded=expanded;
        if(frame.page()==NewsFeedModel.Page.REWARDS)entryDiagnostic=ReadingEntryOcr.diagnostic(expanded,rois.get(0),null);
    }
    String entryDiagnostic(){return entryDiagnostic;}
    synchronized boolean claim(){if(claimed||!current.getAsBoolean())return false;claimed=true;return true;}
    List<Box> rois(){return rois;}
    boolean sameFrame(byte[] gray,int w,int h){return claimed&&!finished&&w==crop.width()&&h==crop.height()
            &&current.getAsBoolean()&&imageHash.equals(CardNativeMockReceipt.digest(gray));}

    Evidence finish(List<List<FrameStructureEvidence.Line>> batches) {
        if(!claimed||finished||!current.getAsBoolean())return Evidence.hold("STALE_CROP");
        finished=true;
        if(batches==null||batches.size()!=rois.size())return Evidence.hold("ROI_COUNT");
        if(frame.page()==NewsFeedModel.Page.REWARDS){
            var rows=batches.get(0);
            Box search=rois.get(0);
            entryDiagnostic=ReadingEntryOcr.diagnostic(expanded,search,rows);
            return ReadingEntryOcr.match(rows,search,crop);
        }
        List<List<FrameStructureEvidence.Line>> valid=new ArrayList<>();
        for(int i=0;i<batches.size();i++) {
            var lines=batches.get(i);
            if(lines==null||lines.isEmpty()||lines.size()>12)return Evidence.hold("ROI_ROWS");
            for(var l:lines)if(!valid(l,rois.get(i)))return Evidence.hold("ROI_SHAPE_OR_CONFIDENCE");
            valid.add(lines);
        }
        NewsFeedModel.Card candidate=frame.card();
        boolean hasLeftMetadata=false;
        for(var row:valid.get(0)){
            if(row.x()+crop.left()>card.left()+card.width()/3)continue;
            hasLeftMetadata=true;
            if(NewsFeedModel.sourceAd(row.text()))
                return new Evidence(NewsFeedModel.Kind.AD,"",-1,-1,"AD_BADGE");
        }
        String source=join(valid.get(0),false);
        if(hasLeftMetadata && source.startsWith("广告"))
            return new Evidence(NewsFeedModel.Kind.AD,"",-1,-1,"AD_BADGE");
        if(candidate.kind()!=NewsFeedModel.Kind.ARTICLE||valid.size()!=2)return Evidence.hold("NO_NEWS_IDENTITY");
        String title=join(valid.get(1),true);
        String publisher=compact(candidate.publisher()), expected=compact(candidate.headline());
        if(publisher.isEmpty() || !source.matches(Pattern.quote(publisher)
                +"[·•丨|]?(?:刚刚|昨天|[0-9]{1,3}(?:(?:分钟|小时|天|周|个月)前?|(?:m(?:in)?|h(?:our)?s?|d(?:ay)?s?|weeks?)(?:ago)?))"))
            return Evidence.hold("PUBLISHER_MISMATCH");
        if(!titleMatch(expected,title))return Evidence.hold("TITLE_MISMATCH");
        Box target=candidate.title();
        return new Evidence(NewsFeedModel.Kind.ARTICLE,candidate.key(),(int)target.centerX(),(int)target.centerY(),"VERIFIED_NEWS");
    }
    private static boolean titleMatch(String expected,String observed) {
        if(expected.equals(observed))return true;
        String strip=observed.replaceFirst("(?:\\.{3}|…)+$","");
        return strip.length()>=8 && strip.length()<observed.length() && expected.startsWith(strip);
    }
    private static boolean valid(FrameStructureEvidence.Line l,Box r){
        if(l==null||l.text()==null||l.text().length()>512||compact(l.text()).isEmpty()||!Double.isFinite(l.score())
                ||l.score()<.85||l.score()>1||l.width()<=0||l.height()<=0)return false;
        return l.x()>r.left()&&l.y()>r.top()&&(long)l.x()+l.width()<r.right()&&(long)l.y()+l.height()<r.bottom();
    }
    private static String join(List<FrameStructureEvidence.Line> rows,boolean multiline){
        List<FrameStructureEvidence.Line> sorted=new ArrayList<>(rows);
        // Group each rendered title line before reading left to right. Baselines may differ by a few pixels.
        sorted.sort(Comparator.comparingInt(FrameStructureEvidence.Line::y));
        List<FrameStructureEvidence.Line> line=new ArrayList<>();
        StringBuilder text=new StringBuilder();
        int baseline=-1, band=0;
        for(var row:sorted){
            if(multiline&&!line.isEmpty()&&row.y()>baseline+band) {
                appendLine(text,line);line.clear();
            }
            if(line.isEmpty()){baseline=row.y();band=Math.max(3,row.height()/2);}
            line.add(row);
        }
        appendLine(text,line);return text.toString();
    }
    private static void appendLine(StringBuilder out,List<FrameStructureEvidence.Line> line){
        line.sort(Comparator.comparingInt(FrameStructureEvidence.Line::x));
        for(var r:line)out.append(compact(r.text()));
    }
    static String compact(String s){return LiveCardIdentity.normalize(s).replaceAll("[\\s\\p{Z}]+","").toLowerCase(Locale.ROOT);}
    private static Box local(Box b,Box crop){return new Box(b.left()-crop.left(),b.top()-crop.top(),b.right()-crop.left(),b.bottom()-crop.top());}
    private static int diff(int a,int b){return Math.max(Math.abs((a>>16&255)-(b>>16&255)),
            Math.max(Math.abs((a>>8&255)-(b>>8&255)),Math.abs((a&255)-(b&255))));}

    // A same-card border must be visible. For tight crops use the rounded corners plus exterior colour;
    // padded crops can attest opposite straight edges directly. Content never supplies its own bounds.
    private static boolean border(int[] pixels,int w,int h,Box b){
        if(b.width()<60||b.height()<36)return false;
        int pad=Math.max(2,Math.min(b.width(),b.height())/60),left=b.left(),right=b.right()-1;
        if(left>=2&&right+2<w){
            int bilateral=0;
            for(int q=2;q<=8;q++){
                int y=b.top()+b.height()*q/10;
                if(diff(pixels[y*w+left-2],pixels[y*w+left+pad])>=6
                        &&diff(pixels[y*w+right+2],pixels[y*w+right-pad])>=6)bilateral++;
            }
            if(bilateral>=4)return true;
        }
        int[] corners={pixels[b.top()*w+left],pixels[b.top()*w+right],
                pixels[(b.bottom()-1)*w+left],pixels[(b.bottom()-1)*w+right]};
        int agrees=0;
        int middle=(int)b.centerY();
        for(int i=0;i<corners.length;i++){
            int inside=pixels[middle*w+(i%2==0?left+pad:right-pad)];
            if(diff(corners[0],corners[i])<=12&&diff(corners[i],inside)>=6)agrees++;
        }
        return agrees>=3;
    }
}
