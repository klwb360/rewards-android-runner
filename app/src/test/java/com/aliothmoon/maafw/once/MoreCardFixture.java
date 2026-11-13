package com.aliothmoon.maafw.once;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Synthetic only: repeated question cards, one Quote, and a clipped irrelevant sibling. */
final class MoreCardFixture {
    static final int W=523,H=1000;
    static FrameStructureEvidence.Line line(String text,int x,int y,int w,int h){return new FrameStructureEvidence.Line(text,x,y,w,h,.95);}
    static byte[] pixels(boolean complete){
        byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);
        for(int top:new int[]{249,427,605,900})RewardCardFixture.fill(p,25,top,509,Math.min(H,top+161),255);
        for(int top:new int[]{249,427})RewardCardFixture.fill(p,388,top+52,490,top+109,80);
        if(complete)RewardCardFixture.tick(p,465,674);else RewardCardFixture.fill(p,388,657,490,714,80);
        return p;
    }
    static List<FrameStructureEvidence.Line> rows(boolean complete){
        List<FrameStructureEvidence.Line> out=new ArrayList<>();
        out.add(line("更多活动",25,210,110,25));out.add(line(complete?"10/20":"5/20",388,210,92,25));
        out.add(line("Do you know the answer?",55,270,265,25));out.add(line("+5",414,316,51,26));
        out.add(new FrameStructureEvidence.Line("Do you know the answer?",55,448,265,25,.4));out.add(line("+5",414,494,51,26));
        out.add(line("Quote of the day",55,626,265,25));out.add(line("Start your day with a quote",55,665,265,20));
        out.add(complete?line("5",433,677,24,17):line("+5",414,672,51,26));
        out.add(new FrameStructureEvidence.Line("Unclear clipped sibling",55,970,265,25,.2));
        return out;
    }
    static LiveRewardCardObservation.Frame observe(byte[] p,List<FrameStructureEvidence.Line> rows){
        return LiveRewardCardObservation.observe("synthetic-more-scope",1,p,W,H,rows,()->false,
            LiveRewardCardObservation.TargetSpec.moreQuoteOfTheDay());
    }
    /** Entirely synthetic compact completion, not reconstructed from the user's native frame. */
    static byte[] compactPixels(){
        byte[] p=pixels(true);RewardCardFixture.fill(p,25,605,509,766,247);
        RewardCardFixture.fill(p,25,605,509,695,255);RewardCardFixture.tick(p,465,638);return p;
    }
    static List<FrameStructureEvidence.Line> compactRows(){
        var result=rows(true);result.removeIf(row->row.text().equals("Start your day with a quote"));
        for(int i=0;i<result.size();i++)if(result.get(i).text().equals("5")&&result.get(i).y()>600)
            result.set(i,line("5",433,641,24,17));
        return result;
    }
    private MoreCardFixture(){}
}


