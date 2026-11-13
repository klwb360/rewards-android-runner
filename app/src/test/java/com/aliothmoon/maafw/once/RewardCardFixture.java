package com.aliothmoon.maafw.once;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Entirely synthetic full-display raster and original-coordinate native-shaped rows. */
final class RewardCardFixture {
    static final int W=523,H=1000;
    static void fill(byte[] p,int l,int t,int r,int b,int value){for(int y=t;y<b;y++)Arrays.fill(p,y*W+l,y*W+r,(byte)value);}
    static byte[] pixels(boolean complete,boolean second){
        byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);
        fill(p,25,249,509,440,255);
        if(!complete)fill(p,388,316,490,373,80);
        else tick(p,465,332);
        if(second){fill(p,25,457,509,648,255);fill(p,388,524,490,581,80);}
        return p;
    }
    static void tick(byte[] p,int left,int top){
        for(int y=top;y<top+25;y++)for(int x=left;x<left+25;x++)
            if(Math.pow(x+.5-(left+12.5),2)+Math.pow(y+.5-(top+12.5),2)<=156.25)p[y*W+x]=(byte)110;
        String[] mask={"00000000011","00000000111","00000001110","10000011100",
            "11000111000","11101110000","01111100000","00111000000"};
        for(int y=0;y<8;y++)for(int x=0;x<11;x++)if(mask[y].charAt(x)=='1')p[(top+8+y)*W+left+7+x]=(byte)255;
    }
    static FrameStructureEvidence.Line line(String text,int x,int y,int w,int h){return new FrameStructureEvidence.Line(text,x,y,w,h,.95);}
    static List<FrameStructureEvidence.Line> rows(boolean complete,boolean second){
        var rows=new ArrayList<FrameStructureEvidence.Line>();
        rows.add(line("每日活动",25,210,110,25));rows.add(line(complete?"10/30":"0/30",388,210,92,25));
        rows.add(line("First activity",55,270,265,25));
        rows.add(complete?line("10",433,335,24,17):line("+10",414,331,51,26));
        if(second){rows.add(line("Second activity",55,478,265,25));rows.add(line("+10",414,539,51,26));}
        rows.add(line("更多活动",25,788,110,25));
        return rows;
    }
    static LiveRewardCardObservation.Frame observe(byte[] p,List<FrameStructureEvidence.Line> rows){
        return LiveRewardCardObservation.observe("synthetic-card-scope",1,p,W,H,rows,()->false);
    }
    private RewardCardFixture(){}
}
