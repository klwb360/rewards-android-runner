package com.aliothmoon.maafw.once;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Synthetic 4-card order: trivia A, trivia B, completed puzzle, completed Quote.
 * Public task labels are config references; these are not reconstructed native OCR. */
final class AutonomousCardFixture {
    static final int W=523,H=1000;
    static final String TITLE="Do you know the answer?";
    static final String A="Challenge yourself with these trivia questions";
    static final String B="Test your knowledge on these topics";
    static byte[] pixels(boolean aComplete,boolean bComplete){
        byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);
        item(p,249,aComplete?90:161,aComplete);item(p,427,bComplete?90:161,bComplete);
        item(p,605,90,true);item(p,731,90,true);return p;
    }
    private static void item(byte[] p,int top,int height,boolean complete){
        RewardCardFixture.fill(p,25,top,509,top+height,255);
        if(complete)RewardCardFixture.tick(p,465,top+33);else RewardCardFixture.fill(p,388,top+52,490,top+109,80);
    }
    static List<FrameStructureEvidence.Line> rows(boolean aComplete,boolean bComplete){
        List<FrameStructureEvidence.Line> out=new ArrayList<>();out.add(MoreCardFixture.line("更多活动",25,210,110,25));
        out.add(MoreCardFixture.line(aComplete?"15/20":"10/20",388,210,92,25));
        rowItem(out,TITLE,A,249,aComplete);rowItem(out,TITLE,B,427,bComplete);
        rowItem(out,"Complete this puzzle",null,605,true);rowItem(out,"Quote of the day",null,731,true);return out;
    }
    private static void rowItem(List<FrameStructureEvidence.Line> rows,String title,String body,int top,boolean complete){
        // Fixed family C lane is an explicit schema assumption; auxiliary old strict layout stays unchanged.
        rows.add(MoreCardFixture.line(title,TITLE.equals(title)?123:55,top+21,TITLE.equals(title)?240:265,25));
        if(!complete&&body!=null)rows.add(MoreCardFixture.line(body,123,top+61,240,20));
        rows.add(complete?MoreCardFixture.line("5",433,top+36,24,17):MoreCardFixture.line("+5",414,top+67,51,26));
    }
    static LiveRewardCardObservation.Frame observe(byte[] p,List<FrameStructureEvidence.Line> rows){
        return LiveRewardCardObservation.observe("synthetic-autonomous",1,p,W,H,rows,()->false,LiveRewardCardObservation.TargetSpec.moreTriviaChallenge());
    }
    private AutonomousCardFixture(){}
}
