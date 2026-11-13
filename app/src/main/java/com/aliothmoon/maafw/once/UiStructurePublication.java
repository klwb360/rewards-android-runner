package com.aliothmoon.maafw.once;
/** One-shot UI-structure publication only. No image/OCR claim or action permission. */
final class UiStructurePublication {
 private final NativeUiStructureProtocol.Report report;private final boolean sourceAndCleanup;private final long deadline;private boolean used;
 UiStructurePublication(NativeUiStructureProtocol.Report report,boolean sourceAndCleanup,long deadline){this.report=report;this.sourceAndCleanup=sourceAndCleanup;this.deadline=deadline;}
 synchronized String publish(long now,boolean current,boolean hidden,boolean permissionSource){
  boolean ok=!used&&sourceAndCleanup&&report!=null&&report.qualified()&&current&&hidden&&permissionSource&&now<deadline&&now>=report.finished()&&NativeProgressPublication.frameFresh(now,report.started());used=true;
  String head="counterSource=UI_STRUCTURE evidence="+(ok?"QUALIFIED":"UNQUALIFIED")+" imageAudit=NOT_REQUESTED screenshots=0 ocr=0 atomicWithImage=false actionAuthority=false ledgerChanged=false";
  if(!ok)return head+" SEARCH=UNKNOWN READING=UNKNOWN publicationFence=REJECTED_AT_APPEND";
  return head+(report.structureBound()?" counterBinding=STRUCTURE_BOUND_CN_EXPLICIT_POINTS":"")+" acquiredStart="+report.started()+" acquiredEnd="+report.acquired()+" helperFinished="+report.finished()+" nodes="+report.nodeCount()+" characters="+report.charCount()+" windowId="+report.windowId()+" displayId="+report.displayId()+" "+task("SEARCH",report.search())+" "+task("READING",report.reading())+readiness(report.readiness())+(report.fieldDiagnostics()==null?"":" "+report.fieldDiagnostics().summary())+" publicationFence=CURRENT_AT_APPEND";
 }
 private static String readiness(NativeUiReadyDiagnostics.Result r){
  if(r==null)return "";
  return " readinessDiagnosticOnly=true pageState="+((r.signIn()>0||r.error()>0)?"HOLD":"UNESTABLISHED")+" idle="+r.idle()+" webViewCount="+r.webViews()+" loadingCount="+r.loading()+" signInCount="+r.signIn()+" errorCount="+r.error()+" fixedLabelMask="+r.labelMask()+" searchTitleNodes="+r.searchTitles()+" readingTitleNodes="+r.readingTitles()+" bareFractionShapeNodes="+r.fractionShapes();
 }
 private static String task(String name,NativeUiStructureProtocol.Task task){
  if(task.status()!=NativeUiStructureProtocol.Status.KNOWN)return name+"="+task.status()+" matches="+task.matchCount();
  return name+"=KNOWN n="+task.current()+" d="+task.target()+" unit="+task.unit()+" state="+(task.current()==task.target()?"COMPLETE_READ_ONLY":"INCOMPLETE_READ_ONLY")+" matches="+task.matchCount();
 }
}
