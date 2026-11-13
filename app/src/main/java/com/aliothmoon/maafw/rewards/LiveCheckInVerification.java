package com.aliothmoon.maafw.rewards;
import java.util.HashSet;
import java.util.Set;
import com.aliothmoon.maafw.rewards.RewardRegionGate.Source;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Origin;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Row;
/** Pure evidence comparator, NOT an actor or source authentication. A trusted runtime must
 * supply its own producer-owned scope/foreground tokens, frame ID, elapsed-realtime capture
 * timestamps and hard expiry. It separately fences ownership, mapping, foreground and input.
 * Frame is transient; Target stores only bounded scalars, boxes, tokens and the pixel hash. */
public final class LiveCheckInVerification {
 private LiveCheckInVerification() {}
 public enum Outcome { BLOCKED, STALE_OR_SCOPE_CHANGED, NO_LOCAL_COMPLETION,
   OBSERVED_SAME_LOCATION_MARKED_COMPLETE_UNATTRIBUTED }
 public record Frame(Object scope,Object foreground,long frameId,long capturedElapsed,long expiresElapsed,
   DailyCheckInRegion.Geometry geometry,DailyCheckInRegion.TextEvidence text) {}
 public static final class Target {
  private final Object scope,foreground; private final long frameId,captured,expires;
  private final int width,height; private final String hash; private final Box panel,ring,nativeNumber;
  private final double x,y; private final Set<Box> otherTicks; private final String numberSource;
  private Target(Frame f,Box panelBox,Box ringBox,Box numberBox,String source){
   scope=f.scope();foreground=f.foreground();frameId=f.frameId();captured=f.capturedElapsed();
   expires=f.expiresElapsed();width=f.geometry().width();height=f.geometry().height();hash=f.geometry().pixelHash();
   panel=panelBox;ring=ringBox;nativeNumber=numberBox;x=ring.centerX();y=ring.centerY();numberSource=source;
   var ticks=new HashSet<Box>(); for(var t:f.geometry().ticks())if(panel.contains(t.disk()))ticks.add(t.disk());
   otherTicks=Set.copyOf(ticks);
  }
  public Box panel(){return panel;} public Box ring(){return ring;} public Box nativeNumber(){return nativeNumber;}
  public double x(){return x;} public double y(){return y;} public String numberSource(){return numberSource;}
  public int width(){return width;} public int height(){return height;}
  public long frameId(){return frameId;} public long capturedElapsed(){return captured;} public long expiresElapsed(){return expires;}
 }
 /** Exact ASCII native "10" only. x/y is full-display only when runtime supplies origin 0,0, scale 1. */
 public static Target target(Frame f,long nowElapsed){
  if(!usable(f,nowElapsed))return null;
  var v=DailyCheckInRegion.previewVisualTarget(f.geometry(),f.text());
  if(v.status()!=DailyCheckInRegion.Status.CANDIDATE||v.displayedPoints()!=10)return null;
  int matches=0;for(Row r:f.text().rows())if(r.box().equals(v.numeral())&&r.origin()==Origin.STATIC_OCR
    &&r.text().equals("10")&&Double.isFinite(r.confidence())&&r.confidence()>=.85&&r.confidence()<=1)matches++;
  if(matches!=1)return null;
  var tickBoxes=new HashSet<Box>();for(var t:f.geometry().ticks())if(v.panel().overlaps(t.disk()))
   if(!v.panel().contains(t.disk())||!tickBoxes.add(t.disk()))return null;
  return new Target(f,v.panel(),v.ring(),v.numeral(),"OCR_NATIVE_BOX");
 }
 /** Necessary freshness/identity check only, never an input authorization or foreground proof. */
 public static boolean matchesForAction(Target t,Object scope,Object foreground,long frameId,long nowElapsed){
  return t!=null&&t.scope==scope&&t.foreground==foreground&&t.frameId==frameId
   &&nowElapsed>=t.captured&&nowElapsed<t.expires;
 }
 /** Proof is made only by a single successful same-image native ROI query. Its box is ROI-level
  * text evidence, not a digit localization. Do not retain Frame/Proof after scalar extraction. */
 public static Target targetFromRoi(Frame f,CheckInRingRoiPlan.Proof proof,long nowElapsed){
  if(!usable(f,nowElapsed)||proof==null||!proof.matches(f.geometry(),f.text()))return null;
  var ticks=new HashSet<Box>();for(var t:f.geometry().ticks())if(proof.panel().overlaps(t.disk()))
   if(!proof.panel().contains(t.disk())||!ticks.add(t.disk()))return null;
  return new Target(f,proof.panel(),proof.ring(),null,"OCR_ROI_TEXT");
 }
 private static boolean usable(Frame f,long now){
  return f!=null&&f.scope()!=null&&f.foreground()!=null&&f.frameId()>0&&f.capturedElapsed()>=0
   &&now>=f.capturedElapsed()&&now<f.expiresElapsed()&&f.geometry()!=null
   &&f.geometry().status()==DailyCheckInRegion.Status.OBSERVED&&f.geometry().key()!=null
   &&f.geometry().key().source()==Source.REAL_DISPLAY_NATIVE&&f.text()!=null&&f.text().rows()!=null;
 }
 /** Only scalar diagnostic results escape; no OCR rows are stored. Counts of -1 mean the
  * corresponding evidence was unavailable. Scope and target-local checks are bounded. */
 public record Verification(Outcome outcome,boolean panelValid,int oldRingCount,
                            int targetTickCount,boolean localConflict) {}
 private static boolean validBox(Box b,int w,int h){return b!=null&&b.left()>=0&&b.top()>=0
  &&b.right()<=w&&b.bottom()<=h&&b.width()>0&&b.height()>0;}
 private static boolean forbidden(String text){
  String s=text.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\u00a0\\u2009\\u202f_-]+","");
  for(String m:java.util.List.of("锁","鎖","明天","明日","登录","登入","登錄","不可用","无法领取","無法領取",
   "不可领取","不可領取",
   "每日活动","lock","tomorrow","login","signin","notavailable","unavailable"))
   if(s.contains(m))return true;
  return false;
 }
 private static boolean panelValid(Frame f,Box expected){
  var g=f.geometry();var evidence=f.text();if(evidence.rows().size()>128||!java.util.Objects.equals(g.key(),evidence.key()))return false;
  for(Row r:evidence.rows())if(r==null||r.text()==null||r.text().length()>128||r.origin()==null
   ||!Double.isFinite(r.confidence())||r.confidence()<0||r.confidence()>1||!validBox(r.box(),g.width(),g.height()))return false;
  Row title=null;DailyCheckInRegion.Surface selected=null;
  for(var surface:g.surfaces())for(Row r:evidence.rows())if(r.text().equals("签入")&&surface.box().overlaps(r.box())){
   Box b=surface.box(),zone=new Box(b.left(),b.top(),b.left()+(int)(.25*b.width()),b.top()+(int)(.40*b.height()));
   if(title!=null||!surface.complete()||!b.equals(expected)||!b.contains(r.box())||!zone.contains(r.box())
    ||r.origin()!=Origin.STATIC_OCR||r.confidence()<.85)return false;
   title=r;selected=surface;
  }
  if(title==null||selected==null)return false;
  Box zone=new Box(expected.left(),expected.top(),expected.left()+(int)(.25*expected.width()),expected.top()+(int)(.40*expected.height()));
  for(Row r:evidence.rows())if(expected.overlaps(r.box()))
   if(!expected.contains(r.box())||forbidden(r.text())||(r!=title&&zone.overlaps(r.box())))return false;
  return true;
 }
 private static boolean localConflict(Frame after,Box ring){
  for(Row r:after.text().rows())if(ring.overlaps(r.box())){
   // Explicit OCR check glyph may corroborate the CV shape; low/unknown local rows stop.
   if((r.text().equals("✓")||r.text().equals("✔"))&&r.origin()==Origin.STATIC_OCR&&r.confidence()>=.85)continue;
   // An original, wide numeric line is unlocalized auxiliary evidence, not a digit in this
   // particular slot. No text is split, removed, promoted or stored.
   boolean wide=r.box().width()>=1.5*ring.width()&&r.box().top()>=ring.top()&&r.box().bottom()<=ring.bottom()
    &&r.box().centerY()>=ring.top()+.20*ring.height()&&r.box().centerY()<=ring.bottom()-.20*ring.height();
   boolean numeric=r.text().length()<=34&&(r.text().matches("[0-9]{5,28}")
    ||r.text().matches("[0-9]{1,4}(?: +[0-9]{1,4}){1,6}"));
   if(wide&&numeric&&r.origin()==Origin.STATIC_OCR)continue;
   return true;
  }
  return false;
 }
 /** Runtime independently proves foreground, display mapping, cleanup and a single action.
  * Ring outline can remain; only a fresh, unambiguous new tick at the original ring counts. */
 public static Verification compareDetailed(Target before,Frame after,long actionElapsed,long nowElapsed){
  if(before==null||!usable(after,nowElapsed))return new Verification(Outcome.BLOCKED,false,-1,-1,false);
  var g=after.geometry();
  if(before.scope!=after.scope()||before.foreground!=after.foreground()||before.expires!=after.expiresElapsed()
   ||nowElapsed>=before.expires||after.frameId()==before.frameId||actionElapsed<before.captured
   ||after.capturedElapsed()<=actionElapsed||before.width!=g.width()||before.height!=g.height()
   ||before.hash.equals(g.pixelHash()))return new Verification(Outcome.STALE_OR_SCOPE_CHANGED,false,-1,-1,false);
  if(!panelValid(after,before.panel))return new Verification(Outcome.NO_LOCAL_COMPLETION,false,-1,-1,false);
  int rings=0;boolean ambiguousRing=false;
  for(Box ring:g.rings())if(before.ring.overlaps(ring)){
   rings++;if(!before.panel.contains(ring)||Math.abs(before.ring.centerX()-ring.centerX())>.15*before.ring.width()
    ||Math.abs(before.ring.centerY()-ring.centerY())>.15*before.ring.height()
    ||ring.width()<.8*before.ring.width()||ring.width()>1.2*before.ring.width()
    ||ring.height()<.8*before.ring.height()||ring.height()>1.2*before.ring.height())ambiguousRing=true;
  }
  boolean conflict=localConflict(after,before.ring);
  var otherTicks=new HashSet<Box>();boolean otherAmbiguous=false;
  for(var tick:g.ticks())if(before.panel.overlaps(tick.disk())&&!before.ring.overlaps(tick.disk()))
   if(!before.panel.contains(tick.disk())||!otherTicks.add(tick.disk()))otherAmbiguous=true;
  int ticks=0;boolean ambiguousTick=false;
  for(var tick:g.ticks())if(before.ring.overlaps(tick.disk())){
   Box d=tick.disk();ticks++;
   if(!Double.isFinite(tick.similarity())||tick.similarity()<.94||!before.ring.contains(d)
    ||Math.abs(before.ring.centerX()-d.centerX())>.15*before.ring.width()
    ||Math.abs(before.ring.centerY()-d.centerY())>.15*before.ring.height())ambiguousTick=true;
  }
  boolean complete=!conflict&&!ambiguousRing&&rings<=1&&!otherAmbiguous&&before.otherTicks.equals(otherTicks)
   &&!ambiguousTick&&ticks==1;
  return new Verification(complete?Outcome.OBSERVED_SAME_LOCATION_MARKED_COMPLETE_UNATTRIBUTED:Outcome.NO_LOCAL_COMPLETION,
   true,rings,ticks,conflict);
 }
 public static Outcome compare(Target before,Frame after,long actionElapsed,long nowElapsed){
  return compareDetailed(before,after,actionElapsed,nowElapsed).outcome();
 }
}

