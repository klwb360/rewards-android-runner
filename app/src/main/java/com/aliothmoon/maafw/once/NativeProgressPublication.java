package com.aliothmoon.maafw.once;
/** Single-use scalar token. Neither this token nor a fixture boolean authenticates a native frame.
 * The live runtime supplies its actual ownership/source/cleanup fences at the UI append boundary. */
final class NativeProgressPublication {
 private final NativeProgressScalars scalars;private NativeProgressLayoutDiagnostics layout;private boolean layoutMode;private NativeProgressRegionDiagnostics regions;private boolean structureMode;
 private final boolean prequalified;
 private final long lower,phaseEnd,sessionEnd;
 private boolean used;private int swipes,confirmed;private boolean actor;
 NativeProgressPublication(NativeProgressScalars scalars,boolean prequalified,long lower,long phaseEnd,long sessionEnd){this.scalars=scalars;this.prequalified=prequalified;this.lower=lower;this.phaseEnd=phaseEnd;this.sessionEnd=sessionEnd;}
 NativeProgressPublication(NativeProgressScalars s,boolean healthy,long lower,long phase,long session,int swipes,int confirmed,boolean actor){this(s,healthy,lower,phase,session);if(swipes<0||swipes>2||confirmed<0||confirmed>swipes)throw new IllegalArgumentException("scan counts");this.swipes=swipes;this.confirmed=confirmed;this.actor=actor;}
 NativeProgressPublication(NativeProgressLayoutDiagnostics layout,boolean prequalified,long lower,long phaseEnd,long sessionEnd){this((NativeProgressScalars)null,prequalified,lower,phaseEnd,sessionEnd);this.layout=layout;layoutMode=true;}
 NativeProgressPublication(NativeProgressRegionDiagnostics regions,boolean prequalified,long lower,long phaseEnd,long sessionEnd){this((NativeProgressScalars)null,prequalified,lower,phaseEnd,sessionEnd);this.regions=regions;structureMode=true;}
 static boolean frameFresh(long now,long lower){return lower>=0&&now>=lower&&now-lower<=15000;}
 synchronized String publish(long now,boolean current,boolean hidden,boolean sourceReady){
  boolean fresh=frameFresh(now,lower)&&now<phaseEnd&&now<sessionEnd;
  boolean qualified=!used&&prequalified&&current&&hidden&&sourceReady&&fresh;
  used=true;
  return (structureMode?(regions==null?"structureEvidence=UNQUALIFIED counterAuthority=false actionAuthority=false regions=SUPPRESSED":regions.summary(qualified)):layoutMode?(layout==null?"layoutEvidence=UNQUALIFIED counterAuthority=false actionAuthority=false candidates=SUPPRESSED":layout.summary(qualified)):scalars==null?"progressEvidence=UNQUALIFIED SEARCH=UNKNOWN READING=UNKNOWN":scalars.summary(qualified,swipes,confirmed,actor))
   +" publicationFence="+(qualified&&(scalars!=null||layout!=null||regions!=null)?"CURRENT_AT_APPEND":"REJECTED_AT_APPEND");
 }
}
