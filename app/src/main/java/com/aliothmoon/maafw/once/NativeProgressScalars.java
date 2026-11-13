package com.aliothmoon.maafw.once;
import io.github.klwb360.rewards.core.RewardsCore;
import java.util.List;
/** Ephemeral full-row projection. Stores no OCR, image, geometry or actor authority. */
final class NativeProgressScalars {
 private final OcrRowsAssessment quality;
 private final FrameStructureEvidence.Result search,reading;
 private final int searchHeadings,readingHeadings,sameRows,bareFractions;private int broadSearch,broadRead,contentSearch,contentRead;
 private NativeProgressScalars(OcrRowsAssessment q,FrameStructureEvidence.Result s,FrameStructureEvidence.Result r,int sh,int rh,int same,int bare){quality=q;search=s;reading=r;searchHeadings=sh;readingHeadings=rh;sameRows=same;bareFractions=bare;}
 static NativeProgressScalars observe(FrameStructureEvidence.Binding binding,List<FrameStructureEvidence.Line> rows,int w,int h){
  var q=OcrRowsAssessment.assess(binding,binding,rows,w,h);
  var s=FrameStructureEvidence.parseForTask(binding,binding,rows,w,h,RewardsCore.Task.SEARCH);
  var r=FrameStructureEvidence.parseForTask(binding,binding,rows,w,h,RewardsCore.Task.READING);
  int sh=0,rh=0,same=0,bare=0;
  if(q.shapeValid())for(var row:rows){
   String t=RewardsCore.normalize(row.text());
   boolean isSearch=t.matches("(?:search to earn|搜索以赚取|search points|搜索积分)(?: .*|$)");
   boolean isReading=t.matches("(?:read to earn|阅读以赚取)(?: .*|$)");
   if(isSearch)sh++;if(isReading)rh++;
   if((isSearch||isReading)&&t.matches(".* [0-9]+/[0-9]+"))same++;
   if(t.matches("[0-9]+\\s*/\\s*[0-9]+"))bare++;
  }
  var result=new NativeProgressScalars(q,s,r,sh,rh,same,bare);
  if(q.shapeValid())for(var row:rows){String t=RewardsCore.normalize(row.text());
   boolean bs=t.matches("(?s).*\\bsearch\\b.*")||t.contains("搜索"),br=t.matches("(?s).*\\bread\\b.*")||t.contains("阅读");
   if(bs)result.broadSearch++;if(br)result.broadRead++;
   boolean content=row.score()>=.8&&row.y()>=h/5&&(long)row.y()+row.height()<=(long)9*h/10;
   if(content&&bs)result.contentSearch++;if(content&&br)result.contentRead++;
  }
  return result;
 }
 boolean scanClue(){return searchHeadings>0||readingHeadings>0||search.supported()||reading.supported()||contentSearch>0||contentRead>0;}
 boolean scanRowsUsable(){return quality.shapeValid()&&quality.nonempty()>0;}
 String summary(boolean qualified){return summary(qualified,0,0,false);}
 String summary(boolean qualified,int swipes,int confirmed,boolean actor){
  return "progressEvidence="+(qualified?"QUALIFIED_NATIVE_FRAME":"UNQUALIFIED")+" "+task("SEARCH",search,qualified)+" "+task("READING",reading,qualified)
   +" rowQuality="+quality.reason()+" nativeRows="+quality.rows()+" nonemptyRows="+quality.nonempty()+" lowConfidenceRows="+quality.lowConfidence()
   +" searchHeadingRows="+searchHeadings+" readingHeadingRows="+readingHeadings+" sameRowFractionRows="+sameRows+" bareFractionRows="+bareFractions
   +" broadSearchRows="+broadSearch+" broadReadRows="+broadRead+" contentSearchClues="+contentSearch+" contentReadClues="+contentRead
   +" taskTaps=0 back=0 swipes="+swipes+" swipeConfirmedExits="+confirmed+" textEntry=0 actorConstructed="+actor+" causalAttribution=NOT_APPLICABLE";
 }
 private static String task(String name,FrameStructureEvidence.Result r,boolean qualified){
  if(!qualified||r==null||!r.supported())return name+"=UNKNOWN";
  return name+"=KNOWN "+name+"Fraction="+r.current()+"/"+r.target()+" "+name+"Unit="+r.unit()+" "+name+"Complete="+(r.current()==r.target())+" "+name+"Remaining="+(r.target()-r.current());
 }
}
