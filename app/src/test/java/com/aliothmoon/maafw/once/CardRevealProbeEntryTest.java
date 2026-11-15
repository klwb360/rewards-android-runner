package com.aliothmoon.maafw.once;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.*;

/** Entry/ownership contracts only. Never starts Android, captures pixels or dispatches input. */
public class CardRevealProbeEntryTest {
    private String source(String name)throws Exception{
        return Files.readString(Path.of(System.getProperty("staging.root",".."),
                "app/src/main/java/com/aliothmoon/maafw/once",name+".java"));
    }
    private String between(String text,String start,String end){
        int a=text.indexOf(start),b=a<0?-1:text.indexOf(end,a+start.length());
        assertTrue("Missing contract boundary: "+start,a>=0&&b>a);return text.substring(a,b);
    }
    private void ordered(String text,String... tokens){
        int previous=-1;for(String token:tokens){int next=text.indexOf(token,previous+1);
            assertTrue("Missing or out of order: "+token,next>previous);previous=next;}
    }
    private void excludes(String text,String... tokens){for(String token:tokens)assertFalse(token,text.contains(token));}

    @Test public void openingExplanationCannotStartAndConfirmationSpendsBeforeDispatch()throws Exception{
        String activity=source("DormantCaptureConfirmationActivity");
        String open=between(activity,"private void openCardRevealChoice()","private void showCardRevealChoice()");
        excludes(open,"new LiveClick","CardRevealProbeRuntime.start","issueCardRevealIntent","burn(");
        String confirm=between(activity,"private void confirmCardReveal()","boolean cardRevealPublish(");
        ordered(confirm,"!isResumedForApproval()","cardRevealSpent=true;consumed=true","click=new LiveClick(this)",
                "CardRevealProbeRuntime.start(this,click,cardRevealOwner)");
        excludes(confirm,"requestPermission(","cardRevealSpent=false","consumed=false");
        String matching=between(activity,"boolean cardRevealApprovalMatches(","private void openCardRevealChoice()");
        assertTrue(matching.contains("owner==cardRevealOwner"));assertTrue(matching.contains("incoming==click&&isResumedForApproval()"));
        excludes(matching,"owner==overviewOwner","owner==alignmentOwner","owner==rootOwner");
    }
    @Test public void oneLiveIssuerRequiresCurrentEpochAndConsumesTheExactVisibleClick()throws Exception{
        String authority=source("VisibleCaptureAuthority");
        var issuers=Pattern.compile("static\\s+CardRevealProbeIntent\\s+(issue\\w*)\\s*\\(").matcher(authority);
        assertTrue(issuers.find());assertEquals("issueCardRevealIntent",issuers.group(1));assertFalse(issuers.find());
        String issuer=between(authority,"static CardRevealProbeIntent issueCardRevealIntent(","static CardRevealProbeIntent fixtureCardRevealIntent(");
        ordered(issuer,"CaptureReleaseGate.requireConfigured()","epoch!=CaptureSessionRuntime.alignmentEpoch()",
                "!activity.cardRevealApprovalMatches(owner,click)","!click.consume(activity,activity.requestIdentity(),owner,epoch)",
                "throw new SecurityException","return new CardRevealProbeIntent");
        excludes(issuer,"fixture","getIntent(","ReadOnlyCheckpoint","System.currentTimeMillis");
        String start=between(source("CardRevealProbeRuntime"),"static CardRevealProbeRuntime start(","private boolean postOwner(");
        ordered(start,"!activity.cardRevealSelected()","VisibleCaptureAuthority.issueCardRevealIntent(","new CardRevealProbeRuntime(");
    }
    @Test public void fixtureCannotReachRealCropDispatchAndCropSlotCannotBeReissued()throws Exception{
        var intent=VisibleCaptureAuthority.fixtureCardRevealIntent(new RealCaptureClaim.ProcessEpoch(),new Object(),100);
        var ui=intent.claimUi(new Object(),101);assertTrue(ui.claimReader(102));intent.finishUi(ui,true,true,103);
        assertFalse(intent.live());assertTrue(intent.claimCrop(ui,104));assertFalse(intent.claimCropDispatch(ui,105));
        assertFalse(intent.claimCrop(ui,106));assertFalse(intent.claimCropDispatch(ui,107));
        assertThrows(SecurityException.class,()->intent.claimUi(new Object(),108));
        assertEquals(1,CardRevealProbeScope.MAX_CROPS);
        String transport=source("AndroidCardRegionCapture");
        ordered(transport,"!permit.intent.live()","permit.intent.cropSpent()","gate.consume()",
                "permit.intent.claimCropDispatch(","process=service.newProcess(");
        assertEquals(1,transport.split("service\\.newProcess\\(",-1).length-1);
        String run=between(source("CardRevealProbeRuntime"),"private void run()","private void settle()");
        assertTrue(Pattern.compile("capture\\(exact,[^;]+;\\s*break;").matcher(run).find());
    }
    @Test public void cropRouteCannotCallOldWholeDisplayBackendOrTaskActions()throws Exception{
        String[] route={"CardRevealProbeRuntime","AndroidCardRegionCapture","NativeCardRegionCaptureHelper"};
        for(String file:route)excludes(source(file),"OverviewPngBinding","ReviewShizukuPngBackend",
                "AndroidReviewShizukuPngTransport","/system/bin/screencap","takeScreenshot(","captureDisplayBitmap(",
                "ReviewShizukuTapActor","ReviewShizukuBackActor","NativeAutonomousCardRun",".tap(",".back(",
                "input tap","input text","performAction(","dispatchGesture(","CLAIMED.set(false)");
        String helper=source("NativeCardRegionCaptureHelper");
        ordered(helper,"CardRegionCaptureProtocol.parseArguments(","setCrop.invoke(builder,crop)",
                "setSecure.invoke(builder,false)","setProtected.invoke(builder,false)","captureDisplay.invoke(manager,0,");
        assertEquals(1,helper.split("captureDisplay\\.invoke\\(",-1).length-1);
    }
    @Test public void sourceAfterMustQualifyBeforeAnyPixelDecodeBoundaryOrPublication()throws Exception{
        String runtime=source("CardRevealProbeRuntime");
        String capture=between(runtime,"private void capture(","private boolean checkBoundary(");
        ordered(capture,"readSource(UiImageAlignmentRuntime.MetadataStage.IMAGE_BEFORE)","CardRevealProbeSource.beforeCrop(",
                "AndroidCardRegionCapture.capture(","!cropRead.knownClean()",
                "readSource(UiImageAlignmentRuntime.MetadataStage.IMAGE_AFTER)","CardRevealProbeSource.afterCrop(",
                "if(!qualified.getAsBoolean())throw","checkBoundary(png,selected,","publish(png,selected,qualified)");
        excludes(capture,"BitmapFactory.","DailyCheckInRegion.observeGray8(");
        String boundary=between(runtime,"private boolean checkBoundary(","private void publish(");
        ordered(boundary,"bounds.inJustDecodeBounds=true","bounds.outWidth!=roi.width()",
                "BitmapFactory.decodeByteArray(png,0,png.length,options)","DailyCheckInRegion.observeGray8(");
    }
    @Test public void followUpRequiresClippedStartAndConfirmedMovementBeforeRegionCapture()throws Exception{
        String runtime=source("CardRevealProbeRuntime");
        String start=between(runtime,"static CardRevealProbeRuntime start(","private boolean postOwner(");
        ordered(start,"bounds.width()!=1440||bounds.height()!=2560","new CardRevealProbeRuntime(");
        String run=between(runtime,"private void run()","private void settle()");
        ordered(run,"if(exact.index==1)","CardRevealProbeTarget.initialClipped(",
                "if(!initialClipped){stage=Stage.START_NOT_CLIPPED;break;}","scroll(exact,selected.direction())",
                "if(!selected.sameCandidate(previous))", "if(!initialClipped||swipes<1||dispatches<1||firstScrollRequest<0||selected.region().coarse().equals(initialCoarse))",
                "stage=Stage.NO_SCROLL_MOVEMENT;break;", "capture(exact,");
        String scroll=between(runtime,"private void scroll(","private void capture(");
        ordered(scroll,"if(result.invocationAttempted()){dispatches++", "if(result.commandExitZero())swipes++",
                "intent.finishScroll(permit,result,actor.knownClean(),SystemClock.elapsedRealtime())");
        String capture=between(runtime,"private void capture(","private boolean checkBoundary(");
        ordered(capture,"if(!initialClipped||swipes<1||dispatches<1||firstScrollRequest<0||revealMs<=0)throw",
                "AndroidCardRegionCapture.capture(");
        assertEquals("rewards-card-native-chain-v1",CardRevealProbeScope.DIRECTORY);
    }
    @Test public void publicationOwnsPixelsUntilFinallyWipeAndCannotResetOrOverwriteRecords()throws Exception{
        String runtime=source("CardRevealProbeRuntime");
        String capture=between(runtime,"private void capture(","private boolean checkBoundary(");
        ordered(capture,"transferred=true;publish(png,selected,qualified)","finally{if(!transferred&&png!=null)Arrays.fill(png,(byte)0)");
        String publish=between(runtime,"private void publish(","private String manifest(");
        ordered(publish,"appendActive.set(true)","activity.cardRevealPublish(this,",
                "metadata.alignmentPublicationAuthorityMatches(shape)","writeFile(\"CHECK_IN.png\",png)",
                "if(outer&&written.get()&&qualified.getAsBoolean()&&nativeResourcesClosed()",
                "published.set(true)","finally{Arrays.fill(png,(byte)0);appendActive.set(false);done.countDown()");
        assertTrue(publish.contains("appendActive.set(false);Arrays.fill(png,(byte)0);throw"));
        assertTrue(runtime.contains("nativeResourcesClosed()&&!appendActive.get()"));
        String scope=source("CardRevealProbeScope");
        assertTrue(scope.contains("StandardOpenOption.CREATE_NEW"));assertTrue(scope.contains("LinkOption.NOFOLLOW_LINKS"));
        excludes(scope,"TRUNCATE_EXISTING","delete(","deleteIfExists(","ReadOnlyCheckpoint.CURRENT");
        String publishGate=between(source("DormantCaptureConfirmationActivity"),"boolean cardRevealPublish(","void cardRevealFinished(");
        assertTrue(publishGate.contains("exact!=cardRevealRuntime"));
        assertTrue(publishGate.contains("ReviewVisibilityProvider.FENCE.publish(visibilityLease,append)"));
    }
}
