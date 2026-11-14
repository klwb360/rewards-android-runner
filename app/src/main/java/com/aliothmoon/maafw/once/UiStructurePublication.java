package com.aliothmoon.maafw.once;
/** One-shot UI-structure publication only. No image/OCR claim or action permission. */
final class UiStructurePublication {
 private final NativeUiStructureProtocol.Report report;private final boolean sourceAndCleanup;private final long deadline;private boolean used;
 private final UiRegionSourceEvidence regionSource;private final Object owner,session,epoch;
 UiStructurePublication(NativeUiStructureProtocol.Report report,boolean sourceAndCleanup,long deadline){this(report,sourceAndCleanup,deadline,null,null,null,null);}
 UiStructurePublication(NativeUiStructureProtocol.Report report,boolean sourceAndCleanup,long deadline,UiRegionSourceEvidence regionSource,Object owner,Object session,Object epoch){this.report=report;this.sourceAndCleanup=sourceAndCleanup;this.deadline=deadline;this.regionSource=regionSource;this.owner=owner;this.session=session;this.epoch=epoch;}
 synchronized String publish(long now,boolean current,boolean hidden,boolean permissionSource){
  boolean regions=report!=null&&report.regions()!=null;
  boolean regionBound=!regions||regionSource!=null&&regionSource.consume(owner,session,epoch,report,now);
  boolean ok=!used&&sourceAndCleanup&&report!=null&&report.qualified()&&current&&hidden&&permissionSource&&now<deadline&&now>=report.finished()&&NativeProgressPublication.frameFresh(now,report.started())&&regionBound;used=true;
  String head="counterSource=UI_STRUCTURE evidence="+(ok?"QUALIFIED":"UNQUALIFIED")+" imageAudit=NOT_REQUESTED screenshots=0 ocr=0 atomicWithImage=false actionAuthority=false ledgerChanged=false";
  if(!ok)return head+" SEARCH=UNKNOWN READING=UNKNOWN"+(regions?" SEARCH_REGION=UNKNOWN READING_REGION=UNKNOWN":"")+" publicationFence=REJECTED_AT_APPEND";
  return head+(report.structureBound()?" counterBinding=STRUCTURE_BOUND_CN_EXPLICIT_POINTS":"")+" acquiredStart="+report.started()+" acquiredEnd="+report.acquired()+" helperFinished="+report.finished()+" nodes="+report.nodeCount()+" characters="+report.charCount()+" windowId="+report.windowId()+" displayId="+report.displayId()+" "+task("SEARCH",report.search())+" "+task("READING",report.reading())+readiness(report.readiness())+(report.fieldDiagnostics()==null?"":" "+report.fieldDiagnostics().summary())+regions(report)+" publicationFence=CURRENT_AT_APPEND";
 }
 private static String regions(NativeUiStructureProtocol.Report report){
  if(report.regions()==null)return "";
  return " sourceBinding=TEMPLATE_BEFORE_AFTER coordinateSpace=ACCESSIBILITY_SCREEN imageBinding=NOT_ESTABLISHED helperRotation="+(report.rotationBefore()<0||report.rotationAfter()<0?"UNOBSERVED":"OBSERVED_MATCHING")+region("SEARCH",report.regions().search())+region("READING",report.regions().reading());
 }
 private static String region(String role,NativeUiRegions.Region region){return region==null?" "+role+"_REGION=UNKNOWN":" "+role+"_REGION=KNOWN card="+rect(region.card())+" summary="+(region.summary()==null?"UNKNOWN":rect(region.summary()));}
 private static String rect(NativeUiRegions.Rect rect){return "["+rect.left()+","+rect.top()+","+rect.right()+","+rect.bottom()+"]";}
 private static String readiness(NativeUiReadyDiagnostics.Result r){
  if(r==null)return "";
  return " readinessDiagnosticOnly=true pageState="+((r.signIn()>0||r.error()>0)?"HOLD":"UNESTABLISHED")+" idle="+r.idle()+" webViewCount="+r.webViews()+" loadingCount="+r.loading()+" signInCount="+r.signIn()+" errorCount="+r.error()+" fixedLabelMask="+r.labelMask()+" searchTitleNodes="+r.searchTitles()+" readingTitleNodes="+r.readingTitles()+" bareFractionShapeNodes="+r.fractionShapes();
 }
 private static String task(String name,NativeUiStructureProtocol.Task task){
  if(task.status()!=NativeUiStructureProtocol.Status.KNOWN)return name+"="+task.status()+" matches="+task.matchCount();
  return name+"=KNOWN n="+task.current()+" d="+task.target()+" unit="+task.unit()+" state="+(task.current()==task.target()?"COMPLETE_READ_ONLY":"INCOMPLETE_READ_ONLY")+" matches="+task.matchCount();
 }
}
