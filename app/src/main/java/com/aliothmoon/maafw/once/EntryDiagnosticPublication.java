package com.aliothmoon.maafw.once;
/** Final append only; no node metadata or diagnostic authority leaks through rejected qualification. */
final class EntryDiagnosticPublication {
 private final NativeEntryDiagnosticProtocol.Report report;private final boolean qualified;private final long deadline;private boolean used;
 EntryDiagnosticPublication(NativeEntryDiagnosticProtocol.Report report,boolean qualified,long deadline){this.report=report;this.qualified=qualified;this.deadline=deadline;}
 synchronized String publish(long now,boolean current,boolean hidden,boolean source){boolean ok=!used&&qualified&&report!=null&&report.qualified()&&current&&hidden&&source&&now<deadline&&now>=report.finished()&&NativeProgressPublication.frameFresh(now,report.started());used=true;if(!ok)return "entryDiagnostic=UNQUALIFIED nodes=SUPPRESSED counterAuthority=false actionAuthority=false publicationFence=REJECTED_AT_APPEND";return report.summary(true)+" publicationFence=CURRENT_AT_APPEND";}
}
