package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.List;

/** Local OCR window and scalar measurements for the reading entry on Rewards. */
final class ReadingEntryOcr {
    static Box window(byte[] gray,int width,Box card,boolean expanded) {
        Box inside=new Box(card.left()+1,card.top()+1,card.right()-1,card.bottom()-1);
        if(expanded)return inside;
        int insetX=Math.max(3,card.width()/24), insetY=Math.max(2,card.height()/28);
        int left=card.left()+insetX, right=card.right()-insetX;
        int top=card.top()+insetY, bottom=card.bottom()-insetY;
        // Text contrasts with the local surface in both themes. Isolated solid icons should
        // not determine a line: several separated ink runs must span a word on the same row.
        int[] tones=new int[32];
        for(int y=top;y<bottom;y+=4)
            for(int x=left;x<right;x+=4)tones[(gray[y*width+x]&255)/8]++;
        int mode=0;
        for(int i=1;i<tones.length;i++)if(tones[i]>tones[mode])mode=i;
        int background=mode*8+4;
        int start=-1,last=-1;
        int gap=Math.max(2,card.height()/74), minHeight=Math.max(4,card.height()/35);
        for(int y=top;y<=bottom+gap;y++) {
            int runs=0,first=-1,end=-1;
            boolean previous=false;
            if(y<bottom)for(int x=left;x<right;x++) {
                int pixel=gray[y*width+x]&255;
                boolean ink=background>=128 ? pixel<=background-60 : pixel>=background+60;
                if(ink) {
                    if(!previous)runs++;
                    if(first<0)first=x;
                    end=x;
                }
                previous=ink;
            }
            if(runs>=5 && end-first>=Math.max(14,card.width()/18)) {
                if(start<0)start=y;
                last=y;
            } else if(start>=0 && y-last>gap) {
                int height=last-start+1;
                if(height>=minHeight && height<card.height()/2) {
                    int pad=Math.max(5,height/2);
                    return new Box(inside.left(),Math.max(inside.top(),start-pad),
                            inside.right(),Math.min(inside.bottom(),last+1+pad));
                }
                start=-1;
            }
        }
        return new Box(inside.left(),top,inside.right(),Math.min(inside.bottom(),top+card.height()*2/3));
    }

    private static boolean title(FrameStructureEvidence.Line row) {
        if(row==null||row.text()==null||row.text().length()>512)return false;
        String compact=NewsCardOcrPlan.compact(row.text());
        return compact.equals("阅读以赚取")||compact.equals("readtoearn");
    }

    // Bits 1/2/4/8 denote contact with or extension beyond the left/top/right/bottom ROI edge.
    private static int edges(FrameStructureEvidence.Line row,Box roi) {
        return (row.x()<=roi.left()?1:0) | (row.y()<=roi.top()?2:0)
                | ((long)row.x()+row.width()>=roi.right()?4:0)
                | ((long)row.y()+row.height()>=roi.bottom()?8:0);
    }

    static String diagnostic(boolean expanded,Box roi,List<FrameStructureEvidence.Line> rows) {
        StringBuilder out=new StringBuilder("ENTRY1 mode=").append(expanded?"WIDE":"TIGHT")
                .append(" roi=").append(roi.left()).append(',').append(roi.top()).append(',')
                .append(roi.width()).append(',').append(roi.height()).append(" rows=").append(rows==null?-1:rows.size());
        if(rows!=null)for(int i=0;i<Math.min(rows.size(),12);i++) {
            var row=rows.get(i);
            out.append(" r").append(i).append('=');
            if(row==null){out.append("NULL");continue;}
            long score=Double.isFinite(row.score())&&row.score()>=0&&row.score()<=1?Math.round(row.score()*1000):-1;
            out.append(title(row)?'T':'O').append(',').append(score).append(',').append(row.x()).append(',')
                    .append(row.y()).append(',').append(row.width()).append(',').append(row.height())
                    .append(',').append(edges(row,roi));
        }
        return out.toString();
    }

    static NewsCardOcrPlan.Evidence match(List<FrameStructureEvidence.Line> rows,Box search,Box cropOnScreen) {
        if(rows==null||rows.isEmpty()||rows.size()>12)return NewsCardOcrPlan.Evidence.hold("ENTRY_ROW_COUNT");
        FrameStructureEvidence.Line target=null;
        for(var row:rows) {
            if(row==null||row.text()==null||row.text().length()>512||NewsCardOcrPlan.compact(row.text()).isEmpty()
                    ||!Double.isFinite(row.score())||row.score()<0||row.score()>1
                    ||row.width()<=0||row.height()<=0)return NewsCardOcrPlan.Evidence.hold("ENTRY_ROW_MALFORMED");
            if(row.x()<search.left()||row.y()<search.top()||(long)row.x()+row.width()>search.right()
                    ||(long)row.y()+row.height()>search.bottom())
                return NewsCardOcrPlan.Evidence.hold("ENTRY_ROW_OUTSIDE_ROI");
            if(!title(row))continue;
            if(target!=null)return NewsCardOcrPlan.Evidence.hold("ENTRY_TITLE_DUPLICATE");
            target=row;
        }
        if(target==null)return NewsCardOcrPlan.Evidence.hold("ENTRY_TITLE_MISSING");
        if(edges(target,search)!=0)return NewsCardOcrPlan.Evidence.hold("ENTRY_TITLE_ON_EDGE");
        if(target.score()<.85)return NewsCardOcrPlan.Evidence.hold("ENTRY_TITLE_LOW_CONFIDENCE");
        return new NewsCardOcrPlan.Evidence(NewsFeedModel.Kind.ENTRY,"",cropOnScreen.left()+target.x()+target.width()/2,
                cropOnScreen.top()+target.y()+target.height()/2,"VERIFIED_ENTRY");
    }

    private ReadingEntryOcr() {}
}
