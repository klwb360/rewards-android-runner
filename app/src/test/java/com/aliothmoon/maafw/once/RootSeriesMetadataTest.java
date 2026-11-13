package com.aliothmoon.maafw.once;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

/** Synthetic values in owner-confirmed client/+Layer syntax; no device text retained. */
public class RootSeriesMetadataTest {
    static final String C=BingNavigationSnapshot.TEMPLATE;
    static BingNavigationSnapshot.Ready ready(){return BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,1000).ready();}
    static String client(){return "TASK 10073:com.microsoft.bing id=15 userId=0 displayId=0(type=INTERNAL)\n"
        +"  ACTIVITY "+C+" ddd pid=3708 userId=0 uid=10073 displayId=0(type=INTERNAL)\n"
        +"      mResumed=true mStopped=false mFinished=false\n"
        +"    ViewRoot:\n      mAdded=true\n      mRemoved=false\n      mStopped=false\n      mPausedForTransition=false\n"
        +"      mTraversalScheduled=false\n      mReportNextDraw=false\n        mHasWindowFocus=true\n        mWindowVisibility=0\n"
        +"       mInputChannel: aaa "+C+" (client)\n"
        +"    Choreographer:\n      mFrameScheduled=true\n      mLastFrameTime=62548813 (6 ms ago)\n"
        +"    Fragment:\n      mStopped=true mHasWindowFocus=false mWindowVisibility=8\n";}
    static String layer(String name,int uid,String parent,int flags,int protect,long stack,boolean buffer){
        return "+ Layer ("+name+") uid="+uid+"\n"
            +"      layerStack=   "+stack+", z=        0, pos=(0,0), isProtected="+protect+", isTrustedOverlay=0, flags=0x"+String.format(Locale.ROOT,"%08x",flags)+", tr=[0.00, 0.00]\n"
            +"      parent="+parent+"\n"
            +(buffer?"      activeBuffer=[523x1000:2816,RGBA_8888], tr=[0.00, 0.00] queued-frames=0 metadata={ownerPID:3708, ownerUID:10073}, shadowRadius=0.000,\n":"");
    }
    static String sf(){return layer(C+"#4235",10073,"aaa "+C+"#654",258,0,0,true)
        +layer("aaa "+C+"#654",1000,"ActivityRecord{ddd u0 "+C+"#653",0,0,0,false)
        +layer("ActivityRecord{ddd u0 "+C+"#653",1000,"Task=15#636",0,0,0,false)
        +layer("Task=15#636",1000,"DefaultTaskDisplayArea#7",0,0,0,false)
        +"* Layer (unrelated secure section)\n      isSecure=true hasProtectedContent=true\n";}
    @Test public void exactClientAndFourLayerSourceChainAreBoundWithoutTokensInResult(){
        var r=RootSeriesMetadata.parse(client(),sf(),ready());assertEquals(RootSeriesMetadata.Association.MATCHED,r.client().association());
        assertEquals(3708,r.client().pid());assertEquals(RootSeriesMetadata.Check.YES,r.client().inputWindow());assertEquals(RootSeriesMetadata.Check.NO,r.client().rootStopped());
        assertEquals(RootSeriesMetadata.Check.YES,r.client().focus());assertEquals(RootSeriesMetadata.Visibility.VISIBLE,r.client().windowVisibility());
        assertEquals(RootSeriesMetadata.Association.MATCHED,r.surface().association());assertEquals(4,r.surface().chain().size());assertEquals(258,r.surface().chain().get(0).flags());
        assertEquals(RootSeriesMetadata.Check.NO,r.surface().chain().get(0).isProtected());assertEquals(RootSeriesMetadata.Check.YES,r.surface().dimensions());
        assertEquals(RootSeriesMetadata.Check.YES,r.surface().bufferOwnerPid());assertEquals(RootSeriesMetadata.Check.YES,r.surface().bufferOwnerUid());
        String text=r.summary();assertFalse(text.contains(C));assertFalse(text.contains("aaa"));assertFalse(text.contains("ddd"));assertFalse(text.contains("4235"));assertFalse(text.contains("2816"));
        assertTrue(text.contains("secure=UNOBSERVED"));assertTrue(text.contains("hasProtectedContent=UNOBSERVED"));assertTrue(text.contains("frameId=UNOBSERVED"));}
    @Test public void rootFieldsCannotBorrowFromFollowingChoreographerOrFragment(){
        var r=RootSeriesMetadata.parse(client().replace("      mStopped=false\n",""),sf(),ready());
        assertEquals(RootSeriesMetadata.Check.UNOBSERVED,r.client().rootStopped());assertEquals(RootSeriesMetadata.Check.NO,r.client().stopped());assertEquals(RootSeriesMetadata.Check.YES,r.client().focus());
        assertFalse(r.summary().contains("mLastFrameTime"));}
    @Test public void wrongWindowRecordTaskUidAndDisplayCannotBorrowSourceIdentity(){
        for(String c:List.of(client().replace("mInputChannel: aaa","mInputChannel: bad"),client().replace(" ddd pid="," bad pid="),
            client().replace("id=15 userId=0","id=16 userId=0"),client().replace("uid=10073","uid=10074"),client().replace("displayId=0","displayId=1"))){
            assertNotEquals(RootSeriesMetadata.Association.MATCHED,RootSeriesMetadata.parse(c,sf(),ready()).client().association());}
    }
    @Test public void duplicateExactActivityOrViewRootIsNotAUniqueBinding(){
        assertEquals(RootSeriesMetadata.Association.AMBIGUOUS,RootSeriesMetadata.parse(client()+client(),sf(),ready()).client().association());
        var r=RootSeriesMetadata.parse(client()+"    ViewRoot:\n      mStopped=true\n",sf(),ready());assertEquals(RootSeriesMetadata.Association.UNOBSERVED,r.client().association());}
    @Test public void missingOrConflictingRootFieldIsUnobservedNotFalse(){
        String c=client().replace("      mTraversalScheduled=false\n","      mTraversalScheduled=false\n      mTraversalScheduled=true\n")
            .replace("        mHasWindowFocus=true\n","");
        var r=RootSeriesMetadata.parse(c,sf(),ready());assertEquals(RootSeriesMetadata.Check.UNOBSERVED,r.client().traversalScheduled());assertEquals(RootSeriesMetadata.Check.UNOBSERVED,r.client().focus());}
    @Test public void malformedOrMissingInputChannelDoesNotBindViewRoot(){
        for(String c:List.of(client().replace("       mInputChannel:","       otherChannel:"),client().replace(" (client)"," (server)"),client().replace(" (client)"," (client)\n       mInputChannel: malformed")))
            assertNotEquals(RootSeriesMetadata.Association.MATCHED,RootSeriesMetadata.parse(c,sf(),ready()).client().association());}
    @Test public void positiveStateFlagsAreObservedButNeverProduceReadiness(){
        var r=RootSeriesMetadata.parse(client().replace("mTraversalScheduled=false","mTraversalScheduled=true").replace("mPausedForTransition=false","mPausedForTransition=true")
            .replace("mWindowVisibility=0","mWindowVisibility=8"),sf(),ready());
        assertEquals(RootSeriesMetadata.Check.YES,r.client().traversalScheduled());assertEquals(RootSeriesMetadata.Check.YES,r.client().pausedTransition());
        assertEquals(RootSeriesMetadata.Visibility.GONE,r.client().windowVisibility());assertTrue(r.summary().contains("pixelReady=UNPROVEN"));}
    @Test public void exactAncestorMismatchOrMissingLayerIsNotSilentlySkipped(){
        for(String s:List.of(sf().replace("aaa "+C+"#654","bbb "+C+"#654"),sf().replace("ActivityRecord{ddd u0","ActivityRecord{bad u0"),sf().replace("Task=15#636","Task=16#636"),
            sf().replace("+ Layer (Task=15#636)","+ OTHER (Task=15#636)")))
            assertNotEquals(RootSeriesMetadata.Association.MATCHED,RootSeriesMetadata.parse(client(),s,ready()).surface().association());}
    @Test public void duplicateTargetOrParentLayerIsNotUnique(){
        String extra=layer(C+"#5000",10073,"aaa "+C+"#654",0,0,0,true);
        assertEquals(RootSeriesMetadata.Association.AMBIGUOUS,RootSeriesMetadata.parse(client(),sf()+extra,ready()).surface().association());
        var parent=layer("aaa "+C+"#654",1000,"ActivityRecord{ddd u0 "+C+"#653",0,0,0,false);
        assertEquals(RootSeriesMetadata.Association.UNOBSERVED,RootSeriesMetadata.parse(client(),sf()+parent,ready()).surface().association());}
    @Test public void wrongBufferUidPidOrShapeIsExplicitNotOtherLayerBorrowed(){
        var r=RootSeriesMetadata.parse(client(),sf().replace("ownerPID:3708","ownerPID:3709").replace("ownerUID:10073","ownerUID:10074").replace("523x1000","1440x2560"),ready());
        assertEquals(RootSeriesMetadata.Check.NO,r.surface().bufferOwnerPid());assertEquals(RootSeriesMetadata.Check.NO,r.surface().bufferOwnerUid());assertEquals(RootSeriesMetadata.Check.NO,r.surface().dimensions());}
    @Test public void plusLayerFlagsAndProtectedRemainSeparateFromSecureCapabilities(){
        var r=RootSeriesMetadata.parse(client(),sf().replace("isProtected=0","isProtected=1"),ready());
        for(var f:r.surface().chain())assertEquals(RootSeriesMetadata.Check.YES,f.isProtected());
        assertTrue(r.summary().contains("secure=UNOBSERVED"));assertTrue(r.summary().contains("hasProtectedContent=UNOBSERVED"));}
    @Test public void absentOrConflictingLayerStateCannotBecomeZeroFalse(){
        String s=sf().replaceFirst("flags=0x00000102","flags=0x00000102 flags=bogus").replaceFirst("isProtected=0","isProtected=2");
        var f=RootSeriesMetadata.parse(client(),s,ready()).surface().chain().get(0);assertEquals(-1,f.flags());assertEquals(RootSeriesMetadata.Check.UNOBSERVED,f.isProtected());}
    @Test public void boundedDocumentsMissingSourceAndBrowserRemainUnobserved(){
        for(String c:Arrays.asList(null,"x".repeat(262145),"x\0")){var r=RootSeriesMetadata.parse(c,null,ready());assertEquals(RootSeriesMetadata.Association.UNOBSERVED,r.client().association());assertEquals(-1,r.client().pid());}
        assertEquals(RootSeriesMetadata.Association.UNOBSERVED,RootSeriesMetadata.parse(client(),sf(),null).surface().association());
        var browser=BingNavigationFixture.result(BingNavigationSnapshot.Kind.BROWSER,1000).ready();assertEquals(RootSeriesMetadata.Association.UNOBSERVED,RootSeriesMetadata.parse(client(),sf(),browser).client().association());}
    @Test public void crlfIsOnlyLineEndingNormalization(){
        var a=RootSeriesMetadata.parse(client(),sf(),ready());var b=RootSeriesMetadata.parse(client().replace("\n","\r\n"),sf().replace("\n","\r\n"),ready());assertEquals(a,b);}
    @Test public void safeResultsContainNoStringOrPointerAndChainIsImmutable(){
        var r=RootSeriesMetadata.parse(client(),sf(),ready());try{r.surface().chain().clear();fail();}catch(UnsupportedOperationException expected){}
        for(Class<?> t:List.of(RootSeriesMetadata.Summary.class,RootSeriesMetadata.Client.class,RootSeriesMetadata.Surface.class,RootSeriesMetadata.LayerFacts.class))
            for(var c:t.getRecordComponents()){assertNotEquals(String.class,c.getType());assertFalse(c.getType().isArray());assertFalse(c.getType().getName().contains("Pointer"));}}
    @Test public void worstSafeMetadataSummaryFits1100(){
        var c=new RootSeriesMetadata.Client(RootSeriesMetadata.Association.UNOBSERVED,Integer.MAX_VALUE,RootSeriesMetadata.Check.UNOBSERVED,RootSeriesMetadata.Check.UNOBSERVED,
            RootSeriesMetadata.Check.UNOBSERVED,RootSeriesMetadata.Check.UNOBSERVED,RootSeriesMetadata.Check.UNOBSERVED,RootSeriesMetadata.Check.UNOBSERVED,RootSeriesMetadata.Check.UNOBSERVED,
            RootSeriesMetadata.Check.UNOBSERVED,RootSeriesMetadata.Check.UNOBSERVED,RootSeriesMetadata.Check.UNOBSERVED,RootSeriesMetadata.Check.UNOBSERVED,RootSeriesMetadata.Check.UNOBSERVED,RootSeriesMetadata.Visibility.UNOBSERVED);
        var f=new RootSeriesMetadata.LayerFacts(0xffffffffL,RootSeriesMetadata.Check.UNOBSERVED,0xffffffffL);
        var sf=new RootSeriesMetadata.Surface(RootSeriesMetadata.Association.UNOBSERVED,512,RootSeriesMetadata.Check.UNOBSERVED,RootSeriesMetadata.Check.UNOBSERVED,RootSeriesMetadata.Check.UNOBSERVED,
            RootSeriesMetadata.Check.UNOBSERVED,List.of(f,f,f,f),Integer.MAX_VALUE,Integer.MAX_VALUE,RootSeriesMetadata.Check.UNOBSERVED,RootSeriesMetadata.Check.UNOBSERVED,RootSeriesMetadata.Check.UNOBSERVED);
        int n=new RootSeriesMetadata.Summary(c,sf).summary().length();assertTrue("metadata="+n,n<=1100);System.out.println("METADATA_MAX_SUMMARY_CHARS="+n);
    }
    @Test public void malformedNearestTaskCannotBorrowEarlierValidParent(){
        String earlier="TASK 10073:com.microsoft.bing id=15 userId=0 displayId=0(type=INTERNAL)\n";
        String broken=client().replace("TASK 10073:com.microsoft.bing id=15 userId=0 displayId=0(type=INTERNAL)","TASK unknown-format id=16");
        var r=RootSeriesMetadata.parse(earlier+broken,sf(),ready());assertEquals(RootSeriesMetadata.Association.UNOBSERVED,r.client().association());
    }}