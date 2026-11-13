package com.aliothmoon.maafw.once;
/** v28 live-only one-frame image-consistency diagnostic. No business authority is issued. */
final class RootFamilyDiagnosticProfile {
 static final boolean ACTIVE=true;
 static final long STARTS_UTC=1794438000000L,EXPIRES_UTC=1794443400000L;
 static final String V25_DIRECTORY="rewards-root-family-diagnostic-20261111-v25";
 static final String V26_DIRECTORY="rewards-root-stable-family-diagnostic-20261111-v26";
 static final String DIRECTORY="rewards-root-image-audit-20261111-v28";
 static final long CAPTURE_TTL=90_000L;
 static boolean current(long utc){return utc>=STARTS_UTC&&utc<EXPIRES_UTC;}
 static boolean admissionCurrent(long utc){return current(utc)&&utc<EXPIRES_UTC-CAPTURE_TTL;}
 static long sessionDeadline(long now,long utc){
  if(now<0||now>Long.MAX_VALUE-CAPTURE_TTL||!admissionCurrent(utc))throw new SecurityException("Image audit intent unavailable");
  return now+Math.min(CAPTURE_TTL,EXPIRES_UTC-utc);
 }
 static long deadline(long now,long utc,long sessionEnd){
  if(now<0||now>Long.MAX_VALUE-CAPTURE_TTL||sessionEnd<=now||!admissionCurrent(utc))throw new SecurityException("Image audit phase unavailable");
  return Math.min(now+CAPTURE_TTL,sessionEnd);
 }
 static boolean modeAllowed(RootCaptureScope.Mode mode){return mode!=null&&(!ACTIVE||mode==RootCaptureScope.Mode.PREFLIGHT);}
 static boolean phaseAllowed(RootCaptureScope.Mode mode,RootCaptureScope.Phase phase){return RootCaptureScope.allowed(mode,phase)&&(!ACTIVE||mode==RootCaptureScope.Mode.PREFLIGHT&&phase==RootCaptureScope.Phase.FIRST);}
 static long visibleDeadline(long original,long now){return ACTIVE?Math.min(original,now+RootCaptureScope.CAPTURE_TTL):original;}
 private RootFamilyDiagnosticProfile(){}
}
