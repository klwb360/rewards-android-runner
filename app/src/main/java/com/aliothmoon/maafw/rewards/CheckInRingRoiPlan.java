package com.aliothmoon.maafw.rewards;
import java.util.List;
import java.util.Locale;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Origin;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Row;
/** Same-owned-image, one ROI OCR query. Plan and Proof are transient: caller discards them
 * with raw OCR rows after scalar Target creation. Neither object authenticates the producer. */
public final class CheckInRingRoiPlan {
 private CheckInRingRoiPlan(){}
 public static final class Plan {
  private final DailyCheckInRegion.Geometry g;private final DailyCheckInRegion.TextEvidence t;
  private final Box panel,ring,roi;private final boolean nonlocalNumericRow;
  private Plan(DailyCheckInRegion.Geometry g,DailyCheckInRegion.TextEvidence t,Box p,Box r,Box q,boolean n)
   {this.g=g;this.t=t;panel=p;ring=r;roi=q;nonlocalNumericRow=n;}
  public Box panel(){return panel;}public Box ring(){return ring;}public Box roi(){return roi;}
  public boolean nonlocalNumericRow(){return nonlocalNumericRow;}
 }
 public static final class Proof {
  private final Plan plan;private final boolean wholeRoi;
  private Proof(Plan p,boolean whole){plan=p;wholeRoi=whole;}
  public Box panel(){return plan.panel;}public Box ring(){return plan.ring;}
  public boolean wholeRoiBox(){return wholeRoi;}public String source(){return "OCR_ROI_TEXT";}
  boolean matches(DailyCheckInRegion.Geometry g,DailyCheckInRegion.TextEvidence t){return plan.g==g&&plan.t==t;}
 }
 private static boolean valid(Box b,int w,int h){return b!=null&&b.left()>=0&&b.top()>=0&&b.right()<=w
   &&b.bottom()<=h&&b.width()>0&&b.height()>0;}
 private static boolean forbidden(String text){
  String s=text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\u00a0\\u2009\\u202f_-]+","");
  for(String marker:List.of("锁","鎖","明天","明日","登录","登入","登錄","不可用","无法领取","無法領取",
   "不可领取","不可領取","已领取","已領取","领取完成","領取完成","已签入","已簽入","已签到","已簽到",
   "已完成","每日活动","lock","tomorrow","login","signin","notavailable","unavailable","claimcomplete","claimed","completed"))
   if(s.contains(marker))return true;
  return false;
 }
 /** A wide original OCR row crossing the circle can be retained only as unlocalized auxiliary
  * evidence: 5-28 contiguous digits or 2-7 space-separated 1-4 digit groups, at most 34 chars,
  * horizontally at least 1.5 circles wide and wholly in the circle's vertical band. Its score
  * is neither used nor upgraded. No raw row is removed, rewritten, cropped or split. */
 public static Plan prepare(DailyCheckInRegion.Geometry g,DailyCheckInRegion.TextEvidence t){
  if(g==null||g.status()!=DailyCheckInRegion.Status.OBSERVED||t==null||t.rows()==null
    ||!java.util.Objects.equals(g.key(),t.key())||t.rows().size()>128)return null;
  for(Row r:t.rows())if(r==null||r.text()==null||r.text().length()>128||r.origin()==null
    ||!valid(r.box(),g.width(),g.height())||!Double.isFinite(r.confidence())
    ||r.confidence()<0||r.confidence()>1)return null;
  DailyCheckInRegion.Surface selected=null;Row title=null;
  for(var s:g.surfaces())for(Row r:t.rows())if(r.text().equals("签入")&&s.box().overlaps(r.box())){
   Box z=new Box(s.box().left(),s.box().top(),s.box().left()+(int)(.25*s.box().width()),
    s.box().top()+(int)(.40*s.box().height()));
   if(title!=null||!s.complete()||!s.box().contains(r.box())||!z.contains(r.box())
     ||r.origin()!=Origin.STATIC_OCR||r.confidence()<.85)return null;
   selected=s;title=r;
  }
  if(selected==null)return null;
  Box panel=selected.box();Box titleZone=new Box(panel.left(),panel.top(),panel.left()+(int)(.25*panel.width()),
    panel.top()+(int)(.40*panel.height()));
  for(Row r:t.rows())if(panel.overlaps(r.box())){
   if(!panel.contains(r.box())||forbidden(r.text())||(r!=title&&titleZone.overlaps(r.box())))return null;
  }
  Box ring=null;for(Box b:g.rings())if(panel.overlaps(b)){
   if(!panel.contains(b)||ring!=null)return null;ring=b;
  }
  if(ring==null||titleZone.overlaps(ring))return null;
  for(var tick:g.ticks())if(ring.overlaps(tick.disk()))return null;
  int rows=0;boolean nonlocal=false;
  for(Row r:t.rows())if(ring.overlaps(r.box())){
   if(++rows>1||r.origin()!=Origin.STATIC_OCR)return null;
   if(r.text().equals("10")){if(r.confidence()<.85)return null;}
   else {
    boolean broad=r.box().width()>=1.5*ring.width()&&r.box().top()>=ring.top()&&r.box().bottom()<=ring.bottom()
      &&r.box().centerY()>=ring.top()+.20*ring.height()&&r.box().centerY()<=ring.bottom()-.20*ring.height();
    boolean digits=r.text().length()<=34&&(r.text().matches("[0-9]{5,28}")
      ||r.text().matches("[0-9]{1,4}(?: +[0-9]{1,4}){1,6}"));
    if(!broad||!digits)return null;nonlocal=true;
   }
  }
  int ix=Math.max(2,(int)Math.ceil(.15*ring.width())),iy=Math.max(2,(int)Math.ceil(.15*ring.height()));
  Box roi=new Box(ring.left()+ix,ring.top()+iy,ring.right()-ix,ring.bottom()-iy);
  return roi.width()>0&&roi.height()>0?new Plan(g,t,panel,ring,roi,nonlocal):null;
 }
 /** Maa v5.15 boxes are original-image coordinates. Whole-ROI fallback is text evidence only,
  * not a localized digit. No second query should be made if acceptance fails. */
 public static Proof accept(Plan p,List<Row> rows){
  if(p==null||rows==null||rows.size()!=1)return null;
  Row r=rows.get(0);if(r==null||r.text()==null||!r.text().equals("10")||r.origin()!=Origin.STATIC_OCR
   ||!Double.isFinite(r.confidence())||r.confidence()<.85||r.confidence()>1||r.box()==null
   ||r.box().width()<=0||r.box().height()<=0||!p.roi.contains(r.box()))return null;
  return new Proof(p,r.box().equals(p.roi));
 }
}
