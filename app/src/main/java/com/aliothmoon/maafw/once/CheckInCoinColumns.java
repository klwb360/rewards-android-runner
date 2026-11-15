package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Same-crop color geometry only. It neither reads OCR rows nor changes their coordinates. */
final class CheckInCoinColumns {
    private static final int DAYS=7,MAX_PIXELS=2_097_152;

    record Result(String status,List<Box> columns,List<Integer> boundaries,List<Box> dayRois,int currentDay) {
        Result {
            columns=List.copyOf(columns);boundaries=List.copyOf(boundaries);dayRois=List.copyOf(dayRois);
        }
        boolean ready(){return status.equals("READY");}
    }

    static Result locate(int[] argb,int width,int height,Box panel,Box ring,BooleanSupplier canceled){
        if(stopped(canceled))return denied("CANCELED");
        if(width<32||height<32||width>4096||height>4096||(long)width*height>MAX_PIXELS
                ||argb==null||argb.length!=(long)width*height||panel==null||ring==null
                ||panel.width()<4||panel.height()<4||panel.left()<0||panel.top()<0
                ||panel.right()>width||panel.bottom()>height||!panel.contains(ring)
                ||ring.width()<16||ring.height()<16||ring.width()>.25*panel.width()
                ||ring.width()<.80*ring.height()||ring.width()>1.25*ring.height())
            return denied("INVALID_GEOMETRY");
        int size=Math.min(ring.width(),ring.height());
        int half=Math.max(1,(int)Math.floor(.20*ring.height())),cy=(int)Math.floor(ring.centerY());
        int top=cy-half,bottom=cy+half;
        if(top<panel.top()||bottom>panel.bottom())return denied("INVALID_GEOMETRY");
        int minimum=Math.max(2,(int)Math.ceil(.07*(bottom-top)));
        int mergeGap=Math.max(1,(int)Math.floor(.12*size));
        int[] projection=new int[panel.width()];
        try {
            for(int y=top;y<bottom;y++){
                if(stopped(canceled))return denied("CANCELED");
                for(int x=panel.left();x<panel.right();x++)if(yellow(argb[y*width+x]))projection[x-panel.left()]++;
            }
            // Close only short within-coin gaps between the thin current outline and its inner coin.
            List<Box> columns=new ArrayList<>();int start=-1;
            for(int x=0;x<=projection.length;x++){
                if((x&127)==0&&stopped(canceled))return denied("CANCELED");
                boolean on=x<projection.length&&projection[x]>=minimum;
                if(on&&start<0)start=x;
                if(!on&&start>=0){
                    Box next=new Box(panel.left()+start,top,panel.left()+x,bottom);
                    if(!columns.isEmpty()&&next.left()-columns.get(columns.size()-1).right()<=mergeGap){
                        Box previous=columns.remove(columns.size()-1);
                        next=new Box(previous.left(),top,next.right(),bottom);
                    }
                    columns.add(next);start=-1;
                }
            }
            // Never select seven from a larger set or synthesize a missing column.
            if(columns.size()!=DAYS)return denied("COIN_COLUMN_COUNT");
            int margin=Math.max(1,(int)Math.floor(.05*size));
            int minimumGap=(int)Math.ceil(.20*size);
            double[] pitches=new double[DAYS-1];
            for(int i=0;i<DAYS;i++){
                if(stopped(canceled))return denied("CANCELED");
                Box column=columns.get(i);
                if(column.left()<=panel.left()+margin||column.right()>=panel.right()-margin
                        ||column.width()<.45*size||column.width()>1.25*size)
                    return denied("COIN_COLUMN_SHAPE");
                long yellowPixels=0;
                for(int x=column.left();x<column.right();x++)yellowPixels+=projection[x-panel.left()];
                if(yellowPixels*100L<35L*column.width()*(bottom-top))return denied("COIN_COLUMN_SPARSE");
                if(i>0){
                    Box previous=columns.get(i-1);
                    if(column.left()-previous.right()<minimumGap)return denied("NO_COIN_VALLEY");
                    pitches[i-1]=column.centerX()-previous.centerX();
                }
            }
            double[] ordered=pitches.clone();Arrays.sort(ordered);
            double pitch=(ordered[2]+ordered[3])/2;
            Arrays.fill(ordered,0);
            if(pitch<.11*panel.width()||pitch>.16*panel.width())return denied("COIN_PITCH");
            for(double distance:pitches)if(distance<.80*pitch||distance>1.20*pitch)return denied("COIN_PITCH");
            int currentDay=-1;
            for(int i=0;i<DAYS;i++){
                Box column=columns.get(i);
                if(ring.centerX()>=column.left()&&ring.centerX()<column.right()){
                    int overlap=Math.min(column.right(),ring.right())-Math.max(column.left(),ring.left());
                    if(currentDay!=-1||overlap<.85*ring.width()
                            ||Math.abs(column.centerX()-ring.centerX())>.10*ring.width())
                        return denied("RING_COLUMN_MISMATCH");
                    currentDay=i+1;
                }
            }
            if(currentDay<0)return denied("RING_COLUMN_MISMATCH");
            // Midpoints of actual blank valleys partition the panel once. No horizontal expansion.
            List<Integer> boundaries=new ArrayList<>();boundaries.add(panel.left());
            for(int i=1;i<DAYS;i++){
                Box previous=columns.get(i-1),next=columns.get(i);
                boundaries.add(previous.right()+(next.left()-previous.right()+1)/2);
            }
            boundaries.add(panel.right());
            int bandTop=panel.top()+(int)Math.ceil(.72*panel.height());
            int dayTop=Math.max(panel.top(),bandTop-(int)Math.ceil(.10*(panel.bottom()-bandTop)));
            List<Box> rois=new ArrayList<>();
            for(int i=0;i<DAYS;i++){
                int left=boundaries.get(i),right=boundaries.get(i+1);
                if(right-left<.65*pitch||right-left>1.50*pitch)return denied("COIN_SLOT_SHAPE");
                Box roi=new Box(left,dayTop,right,panel.bottom());
                if(roi.width()<4||roi.height()<4||!panel.contains(roi))return denied("INVALID_GEOMETRY");
                rois.add(roi);
            }
            if(stopped(canceled))return denied("CANCELED");
            return new Result("READY",columns,boundaries,rois,currentDay);
        }finally{Arrays.fill(projection,0);}
    }

    /** Same opaque-yellow mask as LocalCardMockPlan's current-ring color check. */
    private static boolean yellow(int color){
        int red=(color>>>16)&255,green=(color>>>8)&255,blue=color&255;
        return (color>>>24)==255&&red>=200&&green>=100&&green<=225&&blue<=170
                &&red-green>=20&&green-blue>=25;
    }
    private static boolean stopped(BooleanSupplier canceled){return canceled!=null&&canceled.getAsBoolean();}
    private static Result denied(String reason){return new Result(reason,List.of(),List.of(),List.of(),-1);}
    private CheckInCoinColumns(){}
}
