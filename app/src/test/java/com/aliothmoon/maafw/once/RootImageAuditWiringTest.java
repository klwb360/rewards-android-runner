package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
/** v28 routing source contracts. Does not create a live grant or read native pointers. */
public class RootImageAuditWiringTest {
    private static String source(String name)throws Exception {return Files.readString(Path.of(System.getProperty("staging.root",".."),"app/src/main/java/com/aliothmoon/maafw/once",name)).replace("\r\n","\n");}
    @Test public void diagnosticStillRejectsBusinessBeforeActorConstruction()throws Exception {
        assertTrue(RootFamilyDiagnosticProfile.ACTIVE);assertFalse(RootFamilyDiagnosticProfile.modeAllowed(RootCaptureScope.Mode.BUSINESS));
        assertFalse(RootFamilyDiagnosticProfile.phaseAllowed(RootCaptureScope.Mode.PREFLIGHT,RootCaptureScope.Phase.SECOND));
        String a=source("DormantCaptureConfirmationActivity.java");int start=a.indexOf("private void confirmRoot(RootCaptureScope.Mode mode)");
        assertTrue(a.indexOf("RootFamilyDiagnosticProfile.modeAllowed(mode)",start)<a.indexOf("NativeAutonomousCardRun.confirmed(this)",start));
    }
    @Test public void onlyDiagnosticSelectsBothAuditFactoriesAndStillOneStart()throws Exception {
        String r=source("CaptureSessionRuntime.java");String block=r.substring(r.indexOf("private void acquiredRootOnOwner()"),r.indexOf("private boolean rootResourcesClosed("));
        assertTrue(block.contains(":seriesIntent!=null?MaaRecognizer.rootImageBufferSeriesAudit("));assertTrue(block.contains("?MaaRecognizer.rootImageBufferAudit("));assertTrue(block.contains(":MaaRecognizer.rootPreflight("));
        assertTrue(block.contains("rootBinding=returnReadIntent!=null||boundedIntent!=null||seriesIntent!=null||RootFamilyDiagnosticProfile.ACTIVE"));assertTrue(block.contains("?ReviewShizukuPngBinding.bindImageAudit("));assertTrue(block.contains(":ReviewShizukuPngBinding.bind("));
        assertEquals(1,block.split("backend.start\\(\\)",-1).length-1);assertFalse(block.contains("while("));
    }
    @Test public void activeDiagnosticCannotMintPassOrContinuation()throws Exception {
        String r=source("CaptureSessionRuntime.java");String b=r.substring(r.indexOf("RootPublication rootCommitted()"),r.indexOf("static String boundedRootScalars"));
        assertTrue(b.indexOf("if(returnReadIntent!=null||boundedIntent!=null||seriesIntent!=null||RootFamilyDiagnosticProfile.ACTIVE)return null")<b.indexOf("new RootPublication(this)"));
        assertTrue(r.contains("rootCandidate=seriesIntent==null&&!RootFamilyDiagnosticProfile.ACTIVE&&candidate!=null"));assertTrue(r.contains("rootPreflightValid=seriesIntent==null&&!RootFamilyDiagnosticProfile.ACTIVE&&"));
    }
    @Test public void auditSummariesDoNotDependOnKnownPageOrRestoreCandidate()throws Exception {
        String r=source("CaptureSessionRuntime.java");assertTrue(r.contains("if(seriesIntent!=null||RootFamilyDiagnosticProfile.ACTIVE)details+=\"\\n\"+rootImageAuditSummary(result)"));
        String b=r.substring(r.indexOf("private String rootImageAuditSummary("),r.indexOf("boolean rootPublicationCurrent()"));
        assertTrue(b.contains("imageAudit()"));assertTrue(b.contains("imageBufferAudit(result.owner())"));assertTrue(b.contains("NOT_OBSERVED"));assertTrue(b.contains("UNKNOWN"));assertFalse(b.contains("rootPreflightValid"));assertFalse(b.contains("new RootPublication"));assertFalse(b.contains("backend.start"));
    }
    @Test public void auditCleanupIsAddedToExistingResourceAndQuarantineGate()throws Exception {
        String r=source("CaptureSessionRuntime.java");String b=r.substring(r.indexOf("private boolean rootResourcesClosed("),r.indexOf("static boolean rootPreflightPage"));
        assertTrue(b.contains("!ReviewBingNavigation.knownClean()"));assertTrue(b.contains("!audit.isCleanupConfirmed()"));assertTrue(b.contains("life.sourceReleased()&&life.sourceQuiet()"));assertTrue(b.contains("life.clientPfdAllClosed()"));
    }
    @Test public void v28LiveWindowDoesNotChangeNonliveIntentPath()throws Exception {
        String i=source("RootCaptureIntent.java"),v=source("VisibleCaptureAuthority.java");
        assertTrue(i.contains("live&&RootFamilyDiagnosticProfile.ACTIVE?RootFamilyDiagnosticProfile.current("));assertTrue(i.contains(":RootCaptureScope.current(utcClock.getAsLong())"));
        assertTrue(i.contains("live&&RootFamilyDiagnosticProfile.ACTIVE?RootFamilyDiagnosticProfile.admissionCurrent("));
        int start=v.indexOf("static RootCaptureIntent fixtureRootIntent(");String f=v.substring(start);
        assertTrue(f.contains("RootCaptureScope.sessionDeadline(now,utcClock.getAsLong())"));assertFalse(f.contains("RootFamilyDiagnosticProfile.sessionDeadline"));
    }
    @Test public void liveIssuerRuntimeAndChoicesAllUseDiagnosticWindow()throws Exception {
        String v=source("VisibleCaptureAuthority.java"),r=source("CaptureSessionRuntime.java"),a=source("DormantCaptureConfirmationActivity.java");
        assertTrue(v.contains("RootFamilyDiagnosticProfile.ACTIVE?RootFamilyDiagnosticProfile.sessionDeadline("));
        assertTrue(r.contains("?RootFamilyDiagnosticProfile.deadline(now,System.currentTimeMillis(),intent.sessionEnd())"));
        assertTrue(r.contains("RootFamilyDiagnosticProfile.ACTIVE?RootFamilyDiagnosticProfile.current(System.currentTimeMillis()):RootCaptureScope.current("));
        assertTrue(a.contains("RootFamilyDiagnosticProfile.ACTIVE?RootFamilyDiagnosticProfile.admissionCurrent("));
    }
    @Test public void newWindowCrossesOldBoundaryWithoutExtendingOldConstant(){long afterOld=RootCaptureScope.EXPIRES_UTC+1000;assertFalse(RootCaptureScope.current(afterOld));assertTrue(RootFamilyDiagnosticProfile.current(afterOld));assertEquals(91000,RootFamilyDiagnosticProfile.sessionDeadline(1000,afterOld));}
    @Test public void uiDisclosesSingleSameFrameNativeAuditAndUnknownCleanup()throws Exception {String u=source("RootCaptureUi.java");assertTrue(u.contains("1张新的Root整屏PNG"));assertTrue(u.contains("1次Maa原生OCR"));assertTrue(u.contains("0任务输入"));assertTrue(u.contains("业务pass或第二阶段"));assertTrue(u.contains("00:30"));assertTrue(u.contains("未知清理"));}
    @Test public void completeDiagnosticDisplayKeepsMaxGeometryAndBothAudits()throws Exception {
        var rows=new java.util.ArrayList<MaaImageBufferAudit.OcrRowBox>();
        var boxes=new java.util.ArrayList<com.aliothmoon.maafw.rewards.RewardRegionLocator.Box>();
        for(int i=0;i<32;i++)rows.add(new MaaImageBufferAudit.OcrRowBox(4095,4095,4096,4096,.9999999999999999));
        for(int i=0;i<8;i++)boxes.add(new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(1000,1000,4096,4096));
        var sample=new MaaImageBufferAudit.Sample(MaaImageBufferAudit.Status.PIXEL_MISMATCH,4096,4096,3,16,4194304,4194304,12582912,255,false);
        var result=new MaaImageBufferAudit.Result(MaaImageBufferAudit.Status.PIXEL_MISMATCH,sample,sample,
            new MaaImageBufferAudit.CvSample(true,com.aliothmoon.maafw.rewards.RewardRegionLocator.Status.OBSERVED,16,boxes,true),
            new MaaImageBufferAudit.OcrGeometry(true,128,32,true,rows),1,true,true,true,true,true,true,true);
        // Reserve 2000 chars for real terminal/source cleanup scalars and 1500 for PNG audit.
        String displayed="没有可发布页面结果。\n"+"e".repeat(2000)+"\npngImageAudit="+"s".repeat(1500)+"\n"+result.summary()+"\nfamilyStatus=HOLD semanticObservationOnly=true";
        assertTrue(displayed.length()<8000);assertEquals(displayed,CaptureSessionRuntime.boundedRootScalars(displayed,8000));
        String activity=source("DormantCaptureConfirmationActivity.java");
        assertTrue(activity.contains("candidate!=null&&exact.rootPublicationCurrent()"));
        assertTrue(activity.contains("没有可发布页面结果。\\n\"+details"));
        assertTrue(activity.contains("rootHistory=CaptureSessionRuntime.boundedRootScalars(text,8000)"));
        System.out.println("FULL_DIAGNOSTIC_DISPLAY_BUDGET_CHARS="+displayed.length());
    }
}