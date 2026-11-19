package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Correlates fresh card pixels and native, untranslated OCR coordinates with one structural frame. */
final class NewsCardOcrPlan {
    private final NewsFeedModel.Frame frame;
    private final Box crop, card;
    private final List<Box> rois;
    private final String imageHash, visualKey;
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
        Box search;String visualKey="";
        if(entry){
            search=ReadingEntryOcr.window(gray,crop.width(),local,expanded);
        }else{
            var candidate=frame.card();
            if(!NewsFeedModel.positive(candidate.source()) || !card.contains(candidate.source())
                    ||!NewsFeedModel.positive(candidate.image()) || !card.contains(candidate.image()))return null;
            Box badge=local(candidate.source(),crop);
            int dx=expanded?Math.max(2,badge.width()/12):0;
            int dy=expanded?Math.max(2,badge.height()/8):0;
            search=new Box(Math.max(local.left()+1,badge.left()-dx),Math.max(local.top()+1,badge.top()-dy),
                    Math.min(local.right()-1,badge.right()+dx),Math.min(local.bottom()-1,badge.bottom()+dy));
            visualKey=thumbnailKey(pixels,crop.width(),local(candidate.image(),crop));
        }
        return new NewsCardOcrPlan(frame,crop,card,List.of(search),CardNativeMockReceipt.digest(gray),visualKey,current,expanded);
    }
    private NewsCardOcrPlan(NewsFeedModel.Frame frame,Box crop,Box card,List<Box>rois,String hash,String visualKey,
                            BooleanSupplier current,boolean expanded){
        this.frame=frame;this.crop=crop;this.card=card;this.rois=List.copyOf(rois);this.imageHash=hash;
        this.visualKey=visualKey;this.current=current;
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
        var rows=batches.get(0);
        if(rows==null||rows.size()>8)return Evidence.hold("BADGE_READ_UNCONFIRMED");
        // Only a successful, decoded all:[] is an empty list. Missing/invalid detail stays null.
        Box query=rois.get(0);
        java.util.ArrayList<FrameStructureEvidence.Line> fragments=new java.util.ArrayList<>();
        boolean uncertain=false;
        for(var row:rows){
            if(row==null||row.text()==null||row.text().length()>128||compact(row.text()).isEmpty()
                    ||!Double.isFinite(row.score())||row.score()<.85||row.score()>1
                    ||row.width()<=0||row.height()<=0 || row.x()<=query.left()||row.y()<=query.top()
                    ||(long)row.x()+row.width()>=query.right()||(long)row.y()+row.height()>=query.bottom()){
                uncertain=true;
                continue;
            }
            String part=compact(row.text());
            if(part.contains("广告")||NewsFeedModel.sourceAd(row.text()))
                return new Evidence(NewsFeedModel.Kind.AD,visualKey,-1,-1,"AD_BADGE");
            if(part.endsWith("广")||part.startsWith("告"))fragments.add(row);
        }
        for(var first:fragments)if(compact(first.text()).endsWith("广")){
            for(var next:fragments)if(next!=first&&compact(next.text()).startsWith("告")&&adjacent(first,next))
                return new Evidence(NewsFeedModel.Kind.AD,visualKey,-1,-1,"AD_BADGE");
        }
        if(!fragments.isEmpty())return Evidence.hold("BADGE_PARTIAL");
        if(uncertain)return Evidence.hold("BADGE_ROW_UNCERTAIN");
        if(visualKey.isEmpty())return Evidence.hold("THUMBNAIL_NOT_DISTINCT");
        Box image=frame.card().image();
        return new Evidence(NewsFeedModel.Kind.ARTICLE,visualKey,(int)image.centerX(),(int)image.centerY(),"BADGE_ABSENT");
    }

    private static boolean adjacent(FrameStructureEvidence.Line a,FrameStructureEvidence.Line b){
        int h=Math.min(a.height(),b.height()),w=Math.min(a.width(),b.width());
        int overlapY=Math.min(a.y()+a.height(),b.y()+b.height())-Math.max(a.y(),b.y());
        int dx=b.x()-a.x()-a.width();
        if(b.x()>a.x()&&overlapY*2>=h&&dx>=-w/3&&dx<=h)return true;
        int overlapX=Math.min(a.x()+a.width(),b.x()+b.width())-Math.max(a.x(),b.x());
        int dy=b.y()-a.y()-a.height();
        return b.y()>a.y()&&overlapX*2>=w&&dy>=-h/3&&dy<=h/2;
    }

    // Hash a normalized grid over the central photograph, excluding the corner menu, text and source strip.
    private static String thumbnailKey(int[] pixels,int width,Box image){
        int left=image.left()+image.width()/8, top=image.top()+image.height()/4;
        int areaW=image.width()*3/4, areaH=image.height()*5/8;
        if(areaW<24||areaH<18)return "";
        byte[] cells=new byte[12*9*6];
        int minR=255,minG=255,minB=255,maxR=0,maxG=0,maxB=0,offset=0;
        for(int row=0;row<9;row++)for(int col=0;col<12;col++){
            int r=0,g=0,b=0,center=0;
            for(int by=0;by<3;by++)for(int bx=0;bx<3;bx++){
                int x=left+(col*6+bx*2+1)*areaW/72,y=top+(row*6+by*2+1)*areaH/54;
                int px=pixels[y*width+x];r+=(px>>>16)&255;g+=(px>>>8)&255;b+=px&255;
                if(bx==1&&by==1)center=px;
            }
            r/=9;g/=9;b/=9;
            int centerR=(center>>>16)&255,centerG=(center>>>8)&255,centerB=center&255;
            minR=Math.min(minR,centerR);maxR=Math.max(maxR,centerR);
            minG=Math.min(minG,centerG);maxG=Math.max(maxG,centerG);
            minB=Math.min(minB,centerB);maxB=Math.max(maxB,centerB);
            cells[offset++]=(byte)(r/16);cells[offset++]=(byte)(g/16);cells[offset++]=(byte)(b/16);
            cells[offset++]=(byte)(centerR/16);cells[offset++]=(byte)(centerG/16);cells[offset++]=(byte)(centerB/16);
        }
        return Math.max(maxR-minR,Math.max(maxG-minG,maxB-minB))>=30?CardNativeMockReceipt.digest(cells):"";
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
