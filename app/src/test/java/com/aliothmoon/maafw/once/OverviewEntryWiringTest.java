package com.aliothmoon.maafw.once;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.*;

/** Independent overview authority contracts. No Activity, process, capture or input is run. */
public class OverviewEntryWiringTest {
    private String source(String name) throws Exception {
        Path path=Path.of(System.getProperty("staging.root",".."),
                "app/src/main/java/com/aliothmoon/maafw/once",name+".java");
        return Files.readString(path);
    }
    private String between(String text,String start,String end) {
        int a=text.indexOf(start);assertTrue("Missing start: "+start,a>=0);
        int b=text.indexOf(end,a+start.length());assertTrue("Missing end: "+end,b>a);
        return text.substring(a,b);
    }
    private void containsAll(String text,String... tokens) {
        for(String token:tokens)assertTrue(token,text.contains(token));
    }
    private void excludesAll(String text,String... tokens) {
        for(String token:tokens)assertFalse(token,text.contains(token));
    }
    @Test public void onlyTwoExplicitOverviewIssuersAreClassified() throws Exception {
        var m=Pattern.compile("static [\\w.<>]+ (issueOverview\\w*)\\([^{}]*\\)\\s*\\{")
                .matcher(source("VisibleCaptureAuthority"));
        int sessions=0,frames=0;
        while(m.find()){
            if(m.group(1).equals("issueOverviewIntent"))sessions++;
            else if(m.group(1).equals("issueOverviewFrame"))frames++;
            else fail("Unclassified overview issuer: "+m.group(1));
        }
        assertEquals(1,sessions);assertEquals(1,frames);
    }
    @Test public void explanationDoesNotMintAndConfirmationIsSpentBeforeStart() throws Exception {
        String activity=source("DormantCaptureConfirmationActivity");
        String open=between(activity,"private void openOverviewChoice()","private void showOverviewChoice()");
        containsAll(open,"if(consumed||overviewSpent||alignmentSpent||!isResumedForApproval())return",
                "overviewSelected=true;alignmentSelected=false;showOverviewChoice()");
        excludesAll(open,"new LiveClick","OverviewScanRuntime.start","burn(");
        String confirm=between(activity,"private void confirmOverview()","boolean overviewPublish(");
        int spent=confirm.indexOf("overviewSpent=true;consumed=true");
        int click=confirm.indexOf("click=new LiveClick(this)");
        int start=confirm.indexOf("OverviewScanRuntime.start(this,click,overviewOwner)");
        assertTrue(spent>=0&&click>spent&&start>click);
        containsAll(confirm,"if(consumed||overviewSpent||!overviewSelected||!isResumedForApproval())return",
                "!NativeCheckInSession.permissionReady()","if(click!=null)click.invalidate()");
        excludesAll(confirm,"requestPermission(","overviewSpent=false","consumed=false");
    }
    @Test public void sessionIssuerRequiresExactEpochModeOwnerAndFreshVisibleClick() throws Exception {
        String issuer=between(source("VisibleCaptureAuthority"),"static OverviewScanIntent issueOverviewIntent(",
                "static Proof issueOverviewFrame(");
        containsAll(issuer,"CaptureReleaseGate.requireConfigured()","OverviewScanScope.sessionDeadline(now)",
                "activity==null||click==null||epoch!=CaptureSessionRuntime.alignmentEpoch()||owner==null",
                "!NativeCheckInSession.permissionReady()","!activity.overviewApprovalMatches(owner,click)",
                "!click.consume(activity,activity.requestIdentity(),owner,epoch)",
                "throw new SecurityException(\"Fresh visible overview confirmation required\")",
                "new OverviewApproval(epoch,owner,activity,activity.requestIdentity(),now,deadline,true)");
        assertTrue(issuer.indexOf("throw new SecurityException")<issuer.indexOf("return new OverviewScanIntent"));
        excludesAll(issuer,"ReadOnlyCheckpoint","System.currentTimeMillis","fixture","getIntent(");
        String matching=between(source("DormantCaptureConfirmationActivity"),"boolean overviewApprovalMatches(",
                "private void openOverviewChoice()");
        containsAll(matching,"overviewSelected&&overviewSpent&&!overviewFinished", "owner==overviewOwner",
                "incoming==click&&isResumedForApproval()");
        excludesAll(matching,"owner==alignmentOwner","owner==rootOwner");
    }
    @Test public void frameIssuerOnlyAcceptsOneCurrentLiveFramePermit() throws Exception {
        String authority=source("VisibleCaptureAuthority");
        String issuer=between(authority,"static Proof issueOverviewFrame(","static OverviewScanIntent fixtureOverviewIntent(");
        containsAll(issuer,"CaptureReleaseGate.requireConfigured()",
                "if(permit==null||!permit.live||!permit.claimProof(now))",
                "throw new SecurityException(\"Current unspent overview frame required\")",
                "return new Proof(permit,permit.intent,now)");
        assertTrue(issuer.indexOf("permit.claimProof(now)")<issuer.indexOf("return new Proof"));
        String proof=between(authority,"private Proof(OverviewScanIntent.FramePermit permit",
                "long deadline(){return deadline;}");
        containsAll(proof,"epoch=permit.epoch;owner=permit.owner", "deadline=permit.deadline",
                "if(used)return false;used=true", "e==epoch && o==owner", "now>=issued && now<deadline",
                "overviewPermit==null||overviewPermit.live&&overviewPermit.current(e,o,now)");
        excludesAll(issuer,"new OverviewScanIntent","fixture","ReadOnlyCheckpoint");
    }
    @Test public void nonliveFixtureCannotMintARealFrameProof() {
        assertTrue(CaptureReleaseGate.configured());
        var epoch=new RealCaptureClaim.ProcessEpoch();
        var intent=VisibleCaptureAuthority.fixtureOverviewIntent(epoch,new Object(),100);
        var frame=intent.claimFrame(new Object(),101);
        assertFalse(intent.live());assertFalse(frame.live);
        assertTrue(frame.current(epoch,frame.owner,102));
        assertThrows(SecurityException.class,()->VisibleCaptureAuthority.issueOverviewFrame(frame,102));
        assertThrows(SecurityException.class,()->VisibleCaptureAuthority.issueOverviewFrame(null,102));
    }
    @Test public void runtimeRetainsExactFrameDeadlineAndFinalOuterFenceResult() throws Exception {
        String run=source("OverviewScanRuntime");
        containsAll(run,"framePermit==exact&&exact.current(epoch,exact.owner,SystemClock.elapsedRealtime())&&hiddenCurrent()",
                "Math.min(exact.deadline,SystemClock.elapsedRealtime()+9000)",
                "before[0],beforeAt[0],afterUi,imageBefore,lower,exact.deadline",
                "()->!frameCurrent(exact),preview", "long left=Math.min(10000,exact.deadline-SystemClock.elapsedRealtime())");
        excludesAll(run,"OverviewScanScope.frameDeadline(lower", "RealCaptureClaim.reset");
        String publish=between(run,"private void publishFrame(","private void scroll(");
        containsAll(publish,"boolean outer=activity.overviewPublish(this,()->",
                "nativeResourcesClosed()&&frameCurrent(exact)",
                "OverviewPublicationCommit.commit(savedResult[0]!=null&&savedArtifact[0]!=null,outer",
                "()->frameCurrent(exact)&&nativeResourcesClosed()", "capture.publicationCurrent(SystemClock.elapsedRealtime())",
                "metadata.alignmentPublicationAuthorityMatches(shape)", "if(!published.get())throw");
        assertTrue(publish.indexOf("OverviewPublicationCommit.commit(")<publish.indexOf("qualifiedFrames++"));
        String activity=between(source("DormantCaptureConfirmationActivity"),"boolean overviewPublish(","void overviewFinished(");
        containsAll(activity,"exact!=overviewRuntime","!rootHiddenScalar()||!hiddenWithSoleTask()",
                "return ReviewVisibilityProvider.FENCE.publish(visibilityLease,append)");
    }
    @Test public void scrollingNeedsSeparateSpentPermitFreshSourceAndReleasedFrame() throws Exception {
        String run=source("OverviewScanRuntime");
        String scroll=between(run,"private void scroll(","private void resetReleasedFrame()");
        containsAll(scroll,"newMetadata(this::hiddenCurrent)","intent.claimScroll(exact,SystemClock.elapsedRealtime())",
                "ReviewShizukuSwipeActor.forOverview(activity.getApplicationContext(),permit)",
                "!nativeResourcesClosed()||!preview.knownReleased()||!permit.consume(SystemClock.elapsedRealtime())",
                "OverviewScanScope.burnScroll(","UiImageAlignmentRuntime.MetadataStage.UI_AFTER",
                "before.sameSource(fresh)&&permit.current(SystemClock.elapsedRealtime())&&hiddenCurrent()",
                "if(!actor.knownClean())constructionUnknown=true", "intent.scrollFinished(permit,result,SystemClock.elapsedRealtime())");
        String factory=between(source("ReviewShizukuSwipeActor"),"static ReviewShizukuSwipeActor forOverview(",
                "static ReviewShizukuSwipeActor forProgressScan(");
        containsAll(factory,"permit==null||PROCESS.quarantine!=null||!permit.claimActor(","actor.overviewPermit=permit");
        excludesAll(factory,"PROCESS.quarantine=null","PROCESS.reserve(","CLAIMED.set(");
    }
    @Test public void overviewAddsNoTaskTapBackTextOrLegacyResetPath() throws Exception {
        for(String file:new String[]{"OverviewScanRuntime","OverviewFrameCapture","OverviewRegionRecognizer",
                "OverviewMockPlan","OverviewPngBinding","AndroidOverviewUiReader"})
            excludesAll(source(file),"ReviewShizukuTapActor","ReviewShizukuBackActor","NativeAutonomousCardRun",
                    "NativeRewardCardSession","RewardsCore.prepare","RewardsCore.verify",".tap(",".back(",
                    "input tap","input text","performAction(","dispatchGesture(","CLAIMED.set(false)");
        String scope=source("OverviewScanScope");
        containsAll(scope,"\"rewards-overview-review-v5\"","MAX_FRAMES = 12, MAX_SWIPES = 11",
                "StandardOpenOption.CREATE_NEW","LinkOption.NOFOLLOW_LINKS","maxTaskTaps=0 maxBack=0 maxText=0");
        excludesAll(scope,"TRUNCATE_EXISTING","delete(","deleteIfExists(","ReadOnlyCheckpoint.CURRENT.directory()");
        String runtime=source("OverviewScanRuntime");
        containsAll(runtime,"if(framePermit!=null&&!allResourcesClosed())throw", "AndroidOverviewUiReader.processKnownClean()&&AndroidUiStructureReader.processKnownClean()",
                "coverageIncomplete=true actionAuthority=false counterAuthority=false taskCompletion=NOT_EVALUATED");
    }
}
