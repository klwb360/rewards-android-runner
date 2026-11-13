package com.aliothmoon.maafw.rewards;
import java.util.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.*;
import static com.aliothmoon.maafw.rewards.LiveCheckInVerification.*;
import static com.aliothmoon.maafw.rewards.LiveCheckInVerificationTest.*;
public final class CheckInRingRoiPlanTest {
 static Row row(String text,Box box,double c){return new Row(text,box,c,Origin.STATIC_OCR);}
 public static void main(String[] args){
  byte[] p=pixels();Frame f=frame(p,1,100,scope,fg,rows(null));
  var plan=CheckInRingRoiPlan.prepare(f.geometry(),f.text());
  check(plan!=null&&plan.ring().equals(new Box(245,88,300,143))&&plan.roi().equals(new Box(254,97,291,134)),"unique CV ring and inset");
  var proof=CheckInRingRoiPlan.accept(plan,List.of(row("10",plan.roi(),.96)));
  check(proof!=null&&proof.wholeRoiBox()&&proof.source().equals("OCR_ROI_TEXT"),"whole ROI text only");
  Target t=targetFromRoi(f,proof,120);
  check(t!=null&&t.x()==272.5&&t.y()==115.5&&t.nativeNumber()==null&&t.numberSource().equals("OCR_ROI_TEXT"),"ring center target; no pretend digit box");
  check(targetFromRoi(frame(p,1,100,scope,fg,rows(null)),proof,120)==null,"same frame evidence identity");
  check(CheckInRingRoiPlan.accept(plan,List.of(row("10",plan.roi(),.84)))==null,"low ROI score");
  check(CheckInRingRoiPlan.accept(plan,List.of(row("10+",plan.roi(),.99)))==null,"mixed characters");
  check(CheckInRingRoiPlan.accept(plan,List.of())==null,"empty");
  check(CheckInRingRoiPlan.accept(plan,List.of(row("10",plan.roi(),.99),row("10",plan.roi(),.99)))==null,"multiple ROI rows");
  check(CheckInRingRoiPlan.accept(plan,List.of(row("10",new Box(253,97,291,134),.99)))==null,"ROI global coordinate escape");
  var padded=rows(null);padded.add(row("10",new Box(244,108,282,124),.92));var pf=frame(p,1,100,scope,fg,padded);
  check(CheckInRingRoiPlan.prepare(pf.geometry(),pf.text())!=null,"retain padded exact native envelope");
  var merged=rows(null);merged.add(row("10 10",new Box(185,109,281,123),.95));var mf=frame(p,1,100,scope,fg,merged);
  check(CheckInRingRoiPlan.prepare(mf.geometry(),mf.text())!=null,"merged row spans another observed tick");
  var broad=rows(null);broad.add(row("10 20",new Box(245,109,350,123),.95));var bf=frame(p,1,100,scope,fg,broad);
  check(CheckInRingRoiPlan.prepare(bf.geometry(),bf.text())!=null,"broad unlocalized numeric line can be disambiguated by ROI");
  var glued=rows(null);glued.add(row("10151550",new Box(245,109,350,123),.10));var gf=frame(p,1,100,scope,fg,glued);
  var gp=CheckInRingRoiPlan.prepare(gf.geometry(),gf.text());
  check(gp!=null&&gp.nonlocalNumericRow()&&CheckInRingRoiPlan.accept(gp,List.of(row("10",gp.roi(),.95)))!=null,"low-score unlocalized glued digits require independent ROI");
  var shortLine=rows(null);shortLine.add(row("1015",new Box(245,109,350,123),.95));var sf=frame(p,1,100,scope,fg,shortLine);
  check(CheckInRingRoiPlan.prepare(sf.geometry(),sf.text())==null,"four-digit local-sized token rejected");
  var fraction=rows(null);fraction.add(row("10/15",new Box(245,109,350,123),.95));var ff=frame(p,1,100,scope,fg,fraction);
  check(CheckInRingRoiPlan.prepare(ff.geometry(),ff.text())==null,"wide fraction not treated as numeric merge");
  var conflict=rows(null);conflict.add(row("10 20",new Box(254,109,284,123),.95));var cf=frame(p,1,100,scope,fg,conflict);
  check(CheckInRingRoiPlan.prepare(cf.geometry(),cf.text())==null,"local mixed numeric conflict");
  var words=rows(null);words.add(row("10 明天",new Box(185,109,281,123),.95));var wf=frame(p,1,100,scope,fg,words);
  check(CheckInRingRoiPlan.prepare(wf.geometry(),wf.text())==null,"tomorrow conflict");
  var low=rows(null);low.add(row("10",new Box(244,108,282,124),.4));var lf=frame(p,1,100,scope,fg,low);
  check(CheckInRingRoiPlan.prepare(lf.geometry(),lf.text())==null,"low full OCR key evidence");
  byte[] done=p.clone();fill(done,245,88,300,143,250);tick(done,272);
  check(compare(t,frame(done,2,200,scope,fg,rows(null)),150,250)==Outcome.OBSERVED_SAME_LOCATION_MARKED_COMPLETE_UNATTRIBUTED,"ROI target same-ring tick");
  System.out.println("PASS 19 ROI assertions (synthetic data; not native/device proof)");
 }
}


