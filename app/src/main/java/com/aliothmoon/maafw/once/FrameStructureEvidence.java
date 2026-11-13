package com.aliothmoon.maafw.once;
import io.github.klwb360.rewards.core.RewardsCore;
import java.util.*;
/** Bounded OCR-row layout evidence. Not an accessibility tree, package identity or actor authority. */
final class FrameStructureEvidence {
 record Line(String text,int x,int y,int width,int height,double score) {}
 record Binding(Object source,Object owner,Object epoch,Object frame) {}
 enum Unit { OCR_TEXT_POINTS, UNESTABLISHED }
 record Result(boolean supported,int counters,int current,int target,RewardsCore.Task task,Unit unit) {
  static Result rejected(){return new Result(false,0,0,0,null,Unit.UNESTABLISHED);}
 }
 static String syntheticReceipt(Result result,boolean eligible){
  if(!eligible)return "NOT_OBSERVED";
  if(result==null||!result.supported())return "SYNTHETIC_NATIVE_OCR: UNSUPPORTED";
  return "SYNTHETIC_NATIVE_OCR task="+result.task()+" unit="+result.unit()+" fraction="+result.current()+"/"+result.target();
 }
 static boolean same(Binding a,Binding b){return a!=null&&b!=null&&a.source()!=null&&a.owner()!=null&&a.epoch()!=null&&a.frame()!=null&&a.source()==b.source()&&a.owner()==b.owner()&&a.epoch()==b.epoch()&&a.frame()==b.frame();}
 /** Supported layout: exact heading and explicit fraction in the SAME OCR row.
  * No spatial proximity alone supplies semantic parenthood. Cards remain unsupported.
  * RewardsCore receives only the already isolated single row, never unrelated balance text. */
 static boolean validLines(Binding actual,Binding expected,List<Line> lines,int width,int height){
  return OcrRowsAssessment.assess(actual,expected,lines,width,height).strictEligible();
 }
 /** Read-only task-specific projection of the SAME validated full OCR row set.
  * Accepts SEARCH/READING only. Other well-formed task rows are validated but
  * never aggregated. No package, freshness, cleanup, region or actor authority
  * is established here. The caller must establish those independently.
  * Unknown fractions, split labels, duplicate roles and low-quality rows reject. */
 static Result parseForTask(Binding actual,Binding expected,List<Line> lines,int width,int height,RewardsCore.Task requested){
  if(requested!=RewardsCore.Task.SEARCH&&requested!=RewardsCore.Task.READING)return Result.rejected();
  return parse(actual,expected,lines,width,height,requested);
 }
 static Result parse(Binding actual,Binding expected,List<Line> lines,int width,int height){
  return parse(actual,expected,lines,width,height,null);
 }
 private static Result parse(Binding actual,Binding expected,List<Line> lines,int width,int height,RewardsCore.Task requested){
  if(!validLines(actual,expected,lines,width,height))return Result.rejected();
  int count=0,current=0,target=0; RewardsCore.Task acceptedTask=null;Unit unit=Unit.UNESTABLISHED; Set<RewardsCore.Task> seen=new HashSet<>();
  for(Line line:lines){
   if(line==null||line.text()==null||line.text().length()>512||!Double.isFinite(line.score())||line.score()<0.8||line.score()>1||line.x()<0||line.y()<0||line.width()<=0||line.height()<=0||(long)line.x()+line.width()>width||(long)line.y()+line.height()>height)return Result.rejected();
   String text=RewardsCore.normalize(line.text());
   if(text.contains("sign in")||text.contains("登录")||text.contains("登入"))return Result.rejected();
   RewardsCore.Task task=null;
   if(text.matches("(?:daily set|每日活动|网页每日活动) [0-9]+/[0-9]+"))task=RewardsCore.Task.WEB;
   if(text.matches("(?:search to earn|搜索以赚取|search points|搜索积分) [0-9]+/[0-9]+"))task=RewardsCore.Task.SEARCH;
   if(text.matches("(?:read to earn|阅读以赚取) [0-9]+/[0-9]+"))task=RewardsCore.Task.READING;
   if(task==null){
    if(text.matches("(?:daily set|每日活动|网页每日活动|search to earn|搜索以赚取|search points|搜索积分|read to earn|阅读以赚取)(?: .*|$)"))return Result.rejected();
    if(requested!=null&&text.matches(".*[+-]?[0-9]+\\s*/\\s*[+-]?[0-9]+.*"))return Result.rejected(); // Unowned fraction.
    continue;
   }
   if(!seen.add(task))return Result.rejected(); // Duplicate roles remain ambiguous, including other tasks.
   int at=text.lastIndexOf(' ');String heading=text.substring(0,at),fraction=text.substring(at+1);
   List<RewardsCore.Node> nodes=List.of(new RewardsCore.Node("scope","",heading,"",false,true,true),new RewardsCore.Node("count","scope",fraction,"",false,true,true));
   RewardsCore.Observation parsed=RewardsCore.parse(nodes,task);
   if(parsed.status()!=RewardsCore.ParseStatus.OK)return Result.rejected();
   if(requested!=null&&task!=requested)continue; // Validate other role but never aggregate it.
   if(count!=0)return Result.rejected(); // Legacy API still refuses mixed tasks.
   if((long)current+parsed.counter().current()>Integer.MAX_VALUE||(long)target+parsed.counter().target()>Integer.MAX_VALUE)return Result.rejected(); count++;current=parsed.counter().current();target=parsed.counter().target();acceptedTask=task;unit=(heading.equals("search points")||heading.equals("搜索积分"))?Unit.OCR_TEXT_POINTS:Unit.UNESTABLISHED;
  }
  return count==0?Result.rejected():new Result(true,count,current,target,acceptedTask,unit);
 }
}
