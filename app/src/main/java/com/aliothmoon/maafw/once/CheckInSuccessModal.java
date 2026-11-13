package com.aliothmoon.maafw.once;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import java.util.*;
import java.util.function.BooleanSupplier;
/** Pure bounded native-row evidence only, shared by both real capture routes.
 * Does not own a scope, frame, actor or publication permission. Null means no exact known modal title. */
final class CheckInSuccessModal {
 enum State { COMPLETE, HOLD }
 record Result(State state,String summary) { boolean complete(){return state==State.COMPLETE;} }
 /** Same native frame only. No external UI strings/boxes become evidence.
  * A possible modal that is incomplete/ambiguous holds, rather than using background ticks. */
 static Result observe(List<FrameStructureEvidence.Line> lines,int width,int height,BooleanSupplier cancel){
  Object token=new Object();var binding=new FrameStructureEvidence.Binding(token,token,token,token);
  if(cancel==null||cancel.getAsBoolean())return hold("CANCELED");
  var quality=OcrRowsAssessment.assess(binding,binding,lines,width,height);
  if(!quality.shapeValid())return hold("INVALID_NATIVE_ROWS");
  List<FrameStructureEvidence.Line> titles=new ArrayList<>(),reminders=new ArrayList<>(),declines=new ArrayList<>();
  for(var row:lines){
   String value=modalText(row.text());
   if(successTitle(value))titles.add(row);
   if(value.equals("明天提醒我"))reminders.add(row);
   if(value.equals("不,谢谢")||value.equals("不,謝謝"))declines.add(row);
  }
  if(titles.isEmpty())return null; // No known modal. Preserve the original check-mark fallback.
  String anchors=" titleCount="+titles.size()+" reminderCount="+reminders.size()+" declineCount="+declines.size()
   +" nativeRows="+lines.size()+" backgroundLowRows="+quality.lowConfidence();
  if(titles.size()!=1||reminders.size()!=1||declines.size()!=1)return modalHold("ANCHOR_COUNT",anchors);
  var title=titles.get(0);var reminder=reminders.get(0);var decline=declines.get(0);
  anchors+=anchor("title",title)+anchor("reminder",reminder)+anchor("decline",decline);
  if(title.score()<.85||reminder.score()<.85||decline.score()<.85)return modalHold("CRITICAL_LOW_CONFIDENCE",anchors);
  var tb=lineBox(title);var rb=lineBox(reminder);var db=lineBox(decline);
  if(!modalBox(tb,width,height)||!modalBox(rb,width,height)||!modalBox(db,width,height)
    ||tb.top()<.10*height||tb.bottom()>.75*height||rb.top()<=tb.bottom()||db.top()<=tb.bottom()
    ||Math.abs(tb.centerX()-width/2.0)>.28*width)return modalHold("ANCHOR_LAYOUT",anchors);
  boolean vertical=rb.bottom()<=db.top()&&db.top()-rb.bottom()<=.15*height
   &&Math.abs(rb.centerX()-db.centerX())<=.22*width;
  boolean horizontal=Math.abs(rb.centerY()-db.centerY())<=.04*height&&!rb.overlaps(db);
  if(!vertical&&!horizontal)return modalHold("BUTTON_LAYOUT",anchors);
  int buttonTop=Math.min(rb.top(),db.top());
  List<FrameStructureEvidence.Line> bodyRows=new ArrayList<>();
  for(var row:lines){
   var box=lineBox(row);
   if(box.top()>=tb.bottom()&&box.bottom()<=buttonTop&&modalBox(box,width,height)
     &&Math.abs(box.centerX()-tb.centerX())<=.28*width)bodyRows.add(row);
  }
  anchors+=" bodyCandidateRows="+bodyRows.size();
  for(int i=0;i<bodyRows.size()&&i<6;i++){var row=bodyRows.get(i);String v=modalText(row.text());anchors+=" bodyRow"+i+"Class="+(v.startsWith("你今天已获得")?"EARNED_PREFIX":v.contains("连续签到")?"CHECKIN_CONTEXT":"OTHER")+anchor("bodyRow"+i,row);}
  bodyRows.sort(Comparator.comparingInt(FrameStructureEvidence.Line::y).thenComparingInt(FrameStructureEvidence.Line::x));
  int bodyMatches=0;double bodyMinScore=0;RewardRegionLocator.Box bodyBox=null;int bodyLineCount=0;
  for(int from=0;from<bodyRows.size();from++){
   StringBuilder joined=new StringBuilder();var first=lineBox(bodyRows.get(from));var union=first;
   double minScore=1;int previousBottom=first.top();
   for(int to=from;to<bodyRows.size()&&to<from+4;to++){
    var row=bodyRows.get(to);var box=lineBox(row);
    if(to>from&&(box.top()<previousBottom||box.top()-previousBottom>.045*height))break;
    joined.append(modalText(row.text()));minScore=Math.min(minScore,row.score());union=union(union,box);previousBottom=box.bottom();
    if(modalBody(joined.toString())&&first.top()-tb.bottom()<=.10*height
      &&buttonTop-union.bottom()<=.15*height&&union.height()<=.24*height){
     bodyMatches++;bodyMinScore=minScore;bodyBox=union;bodyLineCount=to-from+1;
    }
   }
  }
  anchors+=" bodyMatches="+bodyMatches+" bodyLines="+bodyLineCount+" bodyMinScore="+bodyMinScore
   +" bodyBox="+(bodyBox==null?"NONE":box(bodyBox));
  if(bodyMatches!=1)return modalHold("BODY_CONTEXT_OR_LAYOUT",anchors);
  if(bodyMinScore<.85)return modalHold("BODY_LOW_CONFIDENCE",anchors);
  var modalBounds=union(union(tb,bodyBox),union(rb,db));
  if(modalBounds.height()>.68*height)return modalHold("MODAL_SPAN",anchors);
  for(var row:lines){
   if(modalBounds.overlaps(lineBox(row))&&negativeModalText(modalText(row.text())))
    return modalHold("LOCAL_NEGATIVE_CONFLICT",anchors);
  }
  if(cancel.getAsBoolean())return hold("CANCELED");
  return new Result(State.COMPLETE,
   "modalState=COMPLETE modalEvidence=SAME_FRAME_NATIVE_OCR"
   +anchors+" modalEvidenceBounds="+box(modalBounds)+" observedPixelBoundary=false"
   +" confidenceThreshold=0.85 localSemanticCoverage=BOUNDED_OBSERVED_ROWS_NOT_EXHAUSTIVE"
   +" reminderOptIn=NOT_SELECTED points=NOT_EVALUATED");
 }
 static String modalText(String value){
  if(value==null)return "";StringBuilder out=new StringBuilder();
  for(int i=0;i<value.length();){int cp=value.codePointAt(i);i+=Character.charCount(cp);
   if(!Character.isWhitespace(cp)&&!Character.isSpaceChar(cp))out.appendCodePoint(cp=='！'?'!':cp=='，'?',':cp);
  }
  return out.toString();
 }
 static boolean successTitle(String text){return text.matches("成功(?:签入|签到|簽入|簽到)!{0,2}");}
 static boolean modalBody(String text){
  // The amount is contextual syntax, never the completion condition or credited balance.
  return text.matches("你今天已(?:获得|獲得)[0-9]{1,5}积分[!。.]?(?:连续签到|连续签入|連續簽到|連續簽入)将赢得更多[Rr][Ee][Ww][Aa][Rr][Dd][Ss]积分[!。.]?");
 }
 static boolean negativeModalText(String text){
  return text.contains("未成功")||text.contains("未完成")||text.contains("失败")||text.contains("失敗")
   ||text.contains("签到未")||text.contains("签入未")||text.contains("签入失败")||text.contains("签到失败");
 }
 private static boolean modalBox(RewardRegionLocator.Box b,int w,int h){
  return b.left()>=.035*w&&b.right()<=.965*w&&b.top()>=.08*h&&b.bottom()<=.96*h
   &&b.width()>=.025*w&&b.height()>=.006*h&&b.height()<=.10*h;
 }
 private static RewardRegionLocator.Box lineBox(FrameStructureEvidence.Line r){return new RewardRegionLocator.Box(r.x(),r.y(),r.x()+r.width(),r.y()+r.height());}
 private static RewardRegionLocator.Box union(RewardRegionLocator.Box a,RewardRegionLocator.Box b){return new RewardRegionLocator.Box(Math.min(a.left(),b.left()),Math.min(a.top(),b.top()),Math.max(a.right(),b.right()),Math.max(a.bottom(),b.bottom()));}
 private static String anchor(String label,FrameStructureEvidence.Line row){return " "+label+"Box="+box(lineBox(row))+" "+label+"Score="+row.score();}
 private static Result modalHold(String reason,String scalars){return new Result(State.HOLD,"modalState=HOLD modalCandidate=true reason="+reason+scalars+" points=NOT_EVALUATED");}
 private static Result hold(String reason){return new Result(State.HOLD,"modalState=HOLD reason="+reason);}
 private static String box(RewardRegionLocator.Box b){return b.left()+","+b.top()+","+b.right()+","+b.bottom();}
 private CheckInSuccessModal(){}
}
