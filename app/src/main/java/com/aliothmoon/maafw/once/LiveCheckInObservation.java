package com.aliothmoon.maafw.once;
import com.aliothmoon.maafw.rewards.DailyCheckInRegion;
import com.aliothmoon.maafw.rewards.RewardRegionGate;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import java.util.*;
import java.util.function.BooleanSupplier;
/** Same owned FULL_DISPLAY bytes and native all[] rows. No crop rescale, supplied target
 * bounds, tree input, screenshot files, raw-text output, or actor. Scalar evidence only. */
final class LiveCheckInObservation {
 private static String box(RewardRegionLocator.Box b){return b==null?"NONE":b.left()+","+b.top()+","+b.right()+","+b.bottom();}
 static String observe(byte[] gray,int w,int h,List<FrameStructureEvidence.Line> lines,BooleanSupplier cancel){
  if(gray==null || lines==null || cancel==null || (long)w*h!=gray.length)return "REAL_NATIVE_CHECKIN INVALID_INPUT";
  Object token=new Object();var binding=new FrameStructureEvidence.Binding(token,token,token,token);
  if(!OcrRowsAssessment.assess(binding,binding,lines,w,h).shapeValid())return "REAL_NATIVE_CHECKIN BAD_OCR";
  var key=new RewardRegionGate.Key(RewardRegionGate.Source.REAL_DISPLAY_NATIVE,"business-20261111-v13",1,"CHECKIN");
  var rows=new ArrayList<RewardRegionLocator.Row>();
  for(var r:lines)rows.add(new RewardRegionLocator.Row(r.text(),new RewardRegionLocator.Box(r.x(),r.y(),r.x()+r.width(),r.y()+r.height()),r.score(),RewardRegionLocator.Origin.STATIC_OCR));
  var g=DailyCheckInRegion.observeGray8(key,gray,w,h,cancel);
  var v=DailyCheckInRegion.previewVisualTarget(g,new DailyCheckInRegion.TextEvidence(key,rows));
  var s=new StringBuilder("REAL_NATIVE_CHECKIN fullDisplay=").append(w).append('x').append(h)
   .append(" cropOrigin=0,0 scale=1 coordinateSpace=FULL_DISPLAY sourcePublisher=HOST_WINDOW_CROSSCHECK_REQUIRED")
   .append(" nativeRows=").append(lines.size()).append(" geometry=").append(g.status())
   .append(" surfaces=").append(g.surfaces().size()).append(" rings=").append(g.rings().size())
   .append(" ticks=").append(g.ticks().size()).append(" visual=").append(v.status());
  RewardRegionLocator.Box panel=null;int titles=0;
  for(var surface:g.surfaces())for(var r:rows)if(r.text().equals("签入")&&r.confidence()>=.85&&surface.complete()&&surface.box().contains(r.box())){
   titles++;panel=surface.box();
  }
  if(titles!=1)panel=null;
  s.append(" exactTitlePanels=").append(titles).append(" panel=").append(box(panel));
  if(v.status()==DailyCheckInRegion.Status.CANDIDATE){
   s.append(" displayedPoints=").append(v.displayedPoints()).append(" target=").append(v.cropX()).append(',').append(v.cropY())
    .append(" numeral=").append(box(v.numeral())).append(" ring=").append(box(v.ring()));
   for(var r:rows)if(r.box().equals(v.numeral()))s.append(" numberConfidence=").append(r.confidence());
  }
  // Diagnostics preserve every original native row and box. This is not an action grant.
  if(panel!=null){
   int localRings=0;
   for(var ring:g.rings())if(panel.overlaps(ring)){
    localRings++;s.append(" diagnosticRing=").append(box(ring));
    if(panel.contains(ring)){
     int ix=Math.max(2,(int)Math.ceil(.20*ring.width()));
     int iy=Math.max(2,(int)Math.ceil(.20*ring.height()));
     var safe=new RewardRegionLocator.Box(ring.left()+ix,ring.top()+iy,ring.right()-ix,ring.bottom()-iy);
     s.append(" diagnosticSafe=").append(box(safe));int intersections=0;
     for(var r:rows)if(ring.overlaps(r.box())){
      String kind=r.text().equals("10")?"EXACT_ASCII_10":r.text().isEmpty()?"EMPTY":
        r.text().matches("[0-9]+")?"OTHER_PLAIN_INTEGER":"OTHER";
      s.append(" diagnosticRowClass=").append(kind).append(" rowBox=").append(box(r.box()))
       .append(" rowScore=").append(r.confidence()).append(" insideSafe=").append(safe.contains(r.box()));
      intersections++;
     }
     s.append(" diagnosticIntersectingRows=").append(intersections);
    } else s.append(" diagnosticCrossesPanel=true");
   }
   s.append(" diagnosticPanelRings=").append(localRings);
  }
  var balances=new ArrayList<RewardRegionLocator.Row>();
  for(var r:rows)if(r.confidence()>=.85 && r.box().top()<.30*h && r.box().left()<.10*w){
   if(r.text().matches("[0-9]{1,3}(?:,[0-9]{3})*积分"))balances.add(r);
   if(r.text().matches("[0-9]{1,3}(?:,[0-9]{3})*")){
    int anchors=0;for(var a:rows)if(a.text().equals("积分")&&a.confidence()>=.85 && a.box().left()>=r.box().right()
      &&a.box().left()-r.box().right()<.10*w &&Math.abs(a.box().centerY()-r.box().centerY())<.5*Math.max(a.box().height(),r.box().height()))anchors++;
    if(anchors==1)balances.add(r);
   }
  }
  if(balances.size()==1){var r=balances.get(0);s.append(" availablePoints=").append(r.text().replace("积分","").replace(",",""))
   .append(" balanceNativeBox=").append(box(r.box())).append(" balanceConfidence=").append(r.confidence());}
  else s.append(" availablePoints=UNPROVEN");
  if(panel!=null){
   int localTicks=0;for(var tick:g.ticks())if(panel.contains(tick.disk())){
    s.append(" tick=").append(box(tick.disk())).append(" tickShape=").append(tick.similarity());localTicks++;
   }
   s.append(" localTickCount=").append(localTicks);
   int[] counts=new int[7];
   for(var r:rows)if(panel.contains(r.box())&&r.confidence()>=.85){
    for(int day=1;day<=7;day++)if(r.text().equals("Day "+day)||r.text().equals("Day"+day)||r.text().equals("第"+day+"天")){
     counts[day-1]++;s.append(" day").append(day).append("Box=").append(box(r.box())).append(" confidence=").append(r.confidence());
    }
   }
   s.append(" independentDays=").append(Arrays.stream(counts).filter(n->n==1).count());
  }
  if(cancel.getAsBoolean())return "REAL_NATIVE_CHECKIN CANCELED";
  return s.append(" actionAuthority=NONE nativeActions=0 calendarDate=UNPROVEN accountCredit=UNPROVEN").toString();
 }
 private LiveCheckInObservation(){}
}

