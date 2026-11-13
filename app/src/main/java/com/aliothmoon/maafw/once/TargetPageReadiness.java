package com.aliothmoon.maafw.once;
/** Post-capture admission of page semantics. No current coarse query can admit a task. */
final class TargetPageReadiness {
 enum Decision { REJECT_OWN_UI, REJECT_UNRECOGNIZED, REJECT_CONFLICT, REJECT_SIGN_IN, NOT_ESTABLISHED }
 static Decision evaluate(RewardsObservation observation,boolean ownUiHidden){
  if(!ownUiHidden)return Decision.REJECT_OWN_UI;
  if(observation==null || observation.page()==RewardsObservation.Page.UNKNOWN)return Decision.REJECT_UNRECOGNIZED;
  return switch(observation.page()){
   case CONFLICT -> Decision.REJECT_CONFLICT;
   case SIGN_IN_NEEDED -> Decision.REJECT_SIGN_IN;
   default -> Decision.NOT_ESTABLISHED;
  };
 }
 static String report(RewardsObservation observation,boolean ownUiHidden){
  return "Candidate: "+(observation==null?"NONE":observation.page())+" · Target readiness: "+evaluate(observation,ownUiHidden)
   +"。同帧仅有粗分类；来源、完整任务结构及计数均未证实。拒绝业务规划和执行。";
 }
 static String report(RewardsObservation observation,boolean ownUiHidden,FrameStructureEvidence.Result structure){
  Decision decision=evaluate(observation,ownUiHidden);
  if(decision!=Decision.NOT_ESTABLISHED || structure==null || !structure.supported())return report(observation,ownUiHidden);
  return "Candidate: "+observation.page()+" · OCR counter-row layout: SUPPORTED · task counters: "+structure.counters()
   +" · task kind: "+structure.task()+" · fraction unit: "+structure.unit()+" · observed single-row fraction: "+structure.current()+"/"+structure.target()
   +"。仅本帧明确任务计数行结构。卡片、Bing 身份、目标来源、账户与积分到账未证实；拒绝业务规划和执行。";
 }
}
