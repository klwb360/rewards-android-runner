package com.aliothmoon.maafw.once;
import java.util.List;
/** Bounded scalar quality report; never contains text, coordinates, hashes or identifiers. */
record OcrRowsAssessment(Reason reason,int rows,int nonempty,int lowConfidence) {
 enum Reason { VALID, BINDING, FRAME_BOUNDS, EMPTY_ROWS, ROW_LIMIT, NULL_ROW, TEXT_SHAPE, TEXT_LIMIT, SCORE, BOX }
 boolean shapeValid(){return reason==Reason.VALID;}
 boolean strictEligible(){return shapeValid() && nonempty>0 && lowConfidence==0;}
 static OcrRowsAssessment assess(FrameStructureEvidence.Binding actual,FrameStructureEvidence.Binding expected,
       List<FrameStructureEvidence.Line> lines,int width,int height){
  if(!FrameStructureEvidence.same(actual,expected))return new OcrRowsAssessment(Reason.BINDING,0,0,0);
  if(width<=0||height<=0||width>4096||height>4096||(long)width*height>4L*1024*1024)return new OcrRowsAssessment(Reason.FRAME_BOUNDS,0,0,0);
  if(lines==null||lines.isEmpty())return new OcrRowsAssessment(Reason.EMPTY_ROWS,0,0,0);
  if(lines.size()>128)return new OcrRowsAssessment(Reason.ROW_LIMIT,129,0,0);
  int nonempty=0,low=0;
  for(var line:lines){
   Reason invalid=null;
   if(line==null)invalid=Reason.NULL_ROW;
   else if(line.text()==null)invalid=Reason.TEXT_SHAPE;
   else if(line.text().length()>512)invalid=Reason.TEXT_LIMIT;
   else if(!Double.isFinite(line.score())||line.score()<0||line.score()>1)invalid=Reason.SCORE;
   else if(line.x()<0||line.y()<0||line.width()<=0||line.height()<=0||(long)line.x()+line.width()>width||(long)line.y()+line.height()>height)invalid=Reason.BOX;
   if(invalid!=null)return new OcrRowsAssessment(invalid,lines.size(),nonempty,low);
   if(!blank(line.text()))nonempty++;
   if(line.score()<0.8)low++;
  }
  return new OcrRowsAssessment(Reason.VALID,lines.size(),nonempty,low);
 }
 // Avoid introducing java.lang.String.isBlank (platform API 33) into minSdk28 code.
 private static boolean blank(String value){
  for(int i=0;i<value.length();){int cp=value.codePointAt(i);if(!Character.isWhitespace(cp))return false;i+=Character.charCount(cp);}
  return true;
 }
}
