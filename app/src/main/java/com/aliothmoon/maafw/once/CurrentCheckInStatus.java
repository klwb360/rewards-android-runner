package com.aliothmoon.maafw.once;
import com.aliothmoon.maafw.rewards.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
/** Independent v17, one-frame CURRENT_STATUS only. No Target, actor or input interface.
 * Reference boxes come from the sealed v15 native receipt, never action authority.
 * Neighbor boxes are explicitly older v13 history, not a fresh v15 before-frame. */
final class CurrentCheckInStatus {
 static final long STARTS_UTC=1794412800000L; // 2026-11-11 16:00 UTC
 static final long EXPIRES_UTC=1794416400000L; // 2026-11-11 17:00 UTC
 static final long TTL=90_000L;
 static final String DIRECTORY="rewards-current-status-20261111-v17";
 static final int WIDTH=1440,HEIGHT=2560,ROTATION=0;
 static final RewardRegionLocator.Box PANEL=new RewardRegionLocator.Box(64,1592,1376,2028);
 static final RewardRegionLocator.Box RING=new RewardRegionLocator.Box(652,1766,788,1902);
 private static final List<RewardRegionLocator.Box> V13_HISTORY=List.of(
  new RewardRegionLocator.Box(152,1792,236,1876),new RewardRegionLocator.Box(327,1792,411,1876),
  new RewardRegionLocator.Box(503,1792,587,1876));
 enum State { CURRENT_CHECKIN_SUCCESS_MODAL, CURRENT_ITEM_SHOWS_CHECK, HOLD }
 record Result(State state,String summary) {}
 static boolean current(long utc){return utc>=STARTS_UTC&&utc<EXPIRES_UTC;}
 static boolean admissionCurrent(long utc){return current(utc)&&utc<EXPIRES_UTC-TTL;}
 static void requireAdmissionCurrent(){if(!admissionCurrent(System.currentTimeMillis()))throw new SecurityException("Current-status admission expired");}
 static long deadline(long elapsed,long utc){
  if(elapsed<0||elapsed>Long.MAX_VALUE-TTL||!admissionCurrent(utc))throw new SecurityException("Current-status deadline unavailable");
  return elapsed+TTL;
 }
 static boolean geometryMatches(int w,int h,int rotation){return w==WIDTH&&h==HEIGHT&&rotation==ROTATION;}
 static RealCaptureClaim.BurnStore store(Path privateFiles){return (id,metadata)->{
  requireAdmissionCurrent();Path dir=privateFiles.resolve(DIRECTORY);Files.createDirectories(dir);
  try(FileChannel channel=FileChannel.open(dir.resolve("CURRENT_STATUS.burned"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
   ByteBuffer bytes=StandardCharsets.UTF_8.encode("current-status-v17 maxFrames=1 maxAccountActions=0 expiresUtc="+EXPIRES_UTC+"\n"+metadata);
   while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
  }
  try(FileChannel c=FileChannel.open(dir,StandardOpenOption.READ)){c.force(true);}
  try(FileChannel c=FileChannel.open(privateFiles,StandardOpenOption.READ)){c.force(true);}
 };}
 static Result observe(byte[] gray,int width,int height,List<FrameStructureEvidence.Line> lines,BooleanSupplier cancel){
  if(gray==null||lines==null||cancel==null||cancel.getAsBoolean()||width!=WIDTH||height!=HEIGHT||(long)width*height!=gray.length)
   return hold("INVALID_INPUT");
  Object token=new Object();var binding=new FrameStructureEvidence.Binding(token,token,token,token);
  if(!OcrRowsAssessment.assess(binding,binding,lines,width,height).shapeValid())return hold("INVALID_NATIVE_ROWS");
  Result modal=successModal(lines,width,height,cancel);
  if(modal!=null)return modal;
  var key=new RewardRegionGate.Key(RewardRegionGate.Source.REAL_DISPLAY_NATIVE,DIRECTORY,1,"CURRENT_STATUS");
  var g=DailyCheckInRegion.observeGray8(key,gray,width,height,cancel);
  if(cancel.getAsBoolean())return hold("CANCELED");
  if(g.status()!=DailyCheckInRegion.Status.OBSERVED)return hold("GEOMETRY_"+g.status());
  int panels=0,titles=0,ticks=0,rings=0,history=0,localRows=0;boolean tickShape=true,localConflict=false;
  for(var p:g.surfaces())if(p.complete()&&p.box().equals(PANEL))panels++;
  for(var row:lines){
   var box=new RewardRegionLocator.Box(row.x(),row.y(),row.x()+row.width(),row.y()+row.height());
   if(row.text().equals("签入")&&row.score()>=.85&&PANEL.contains(box))titles++;
   if(RING.overlaps(box)){localRows++;if(localConflict(row.text(),box,row.score()))localConflict=true;}
  }
  for(var r:g.rings())if(RING.overlaps(r))rings++; // Diagnostic only; a retained outline is not failure.
  StringBuilder measured=new StringBuilder();
  for(var tick:g.ticks()){
   if(V13_HISTORY.contains(tick.disk()))history++;
   if(RING.overlaps(tick.disk())){
    ticks++;var disk=tick.disk();
    tickShape&=Double.isFinite(tick.similarity())&&tick.similarity()>=.94&&RING.contains(disk)
     &&Math.abs(RING.centerX()-disk.centerX())<=.15*RING.width()
     &&Math.abs(RING.centerY()-disk.centerY())<=.15*RING.height();
    if(ticks<=4)measured.append(" tickDisk=").append(box(disk)).append(" tickGlyph=").append(box(tick.glyph())).append(" shape=").append(tick.similarity());
   }
  }
  boolean checked=admit(panels,titles,ticks,tickShape,localConflict);
  String summary="source=REAL_NATIVE_CURRENT_STATE reference=V15_SEALED_LOCATION_CROSS_RUN"
   +" fullDisplay="+width+"x"+height+" panel="+box(PANEL)+" referenceRing="+box(RING)
   +" nativeRows="+lines.size()+" panelMatches="+panels+" exactTitleCount="+titles
   +" targetTickCount="+ticks+" overlappingRingCount="+rings+" reliableTickShape="+tickShape
   +" localNativeRows="+localRows+" localConflict="+localConflict
   +" localSemanticCoverage=BOUNDED_OBSERVED_ROWS_NOT_EXHAUSTIVE"
   +" v13HistoricalNeighborMatches="+history+"/3 historicalNeighborsNotFreshBefore=true"
   +measured+" state="+(checked?State.CURRENT_ITEM_SHOWS_CHECK:State.HOLD)
   +" actionsThisRun=0 sameSessionComparison=false causalAttribution=UNPROVEN points=NOT_EVALUATED";
  return new Result(checked?State.CURRENT_ITEM_SHOWS_CHECK:State.HOLD,summary);
 }
 static Result successModal(List<FrameStructureEvidence.Line> lines,int width,int height,BooleanSupplier cancel){
  var modal=CheckInSuccessModal.observe(lines,width,height,cancel);
  if(modal==null)return null;
  State state=modal.complete()?State.CURRENT_CHECKIN_SUCCESS_MODAL:State.HOLD;
  return new Result(state,"source=REAL_NATIVE_CURRENT_STATE state="+state+" "+modal.summary()
   +" actionsThisRun=0 sameSessionComparison=false causalAttribution=UNPROVEN");
 }
 static boolean admit(int panels,int titles,int ticks,boolean reliableShape,boolean conflict){
  return panels==1&&titles==1&&ticks==1&&reliableShape&&!conflict;
 }
 static boolean localConflict(String text,RewardRegionLocator.Box box,double score){
  if(text==null||box==null||!Double.isFinite(score)||score<0||score>1)return true;
  if(score>=.85&&Set.of("✓","✔","√","已完成","已签到","已签入","已簽到","已簽入","completed","Completed").contains(text))return false;
  boolean wide=box.width()>=1.5*RING.width()&&box.top()>=RING.top()&&box.bottom()<=RING.bottom()
   &&box.centerY()>=RING.top()+.20*RING.height()&&box.centerY()<=RING.bottom()-.20*RING.height();
  boolean numeric=text.length()<=34&&(text.matches("[0-9]{5,28}")||text.matches("[0-9]{1,4}(?: +[0-9]{1,4}){1,6}"));
  return !(wide&&numeric); // Unlocalized merged digits stay auxiliary, never assigned to this item.
 }
 private static Result hold(String reason){return new Result(State.HOLD,"source=REAL_NATIVE_CURRENT_STATE state=HOLD reason="+reason+" actionsThisRun=0 causalAttribution=UNPROVEN");}
 private static String box(RewardRegionLocator.Box b){return b.left()+","+b.top()+","+b.right()+","+b.bottom();}
 private CurrentCheckInStatus(){}
}
