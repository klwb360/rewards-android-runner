package com.aliothmoon.maafw.once;

/** Synthetic documents in the independently verified OEM format. No saved device metadata. */
final class BingNavigationFixture {
    static final BingNavigationSnapshot.Shape SHAPE=new BingNavigationSnapshot.Shape(523,1000,0);
    static final BingNavigationSnapshot.Profile PROFILE=new BingNavigationSnapshot.Profile(10073,10049,SHAPE);
    static String window(String component,String token,boolean visible){
        return "  Window #1 Window{"+token+" u0 "+component+"}:\n"
            +"    mDisplayId=0 rootTaskId=15 mSession=Session{123 574:u0a10073}\n"
            +"    mOwnerUid=10073 showForAllUsers=true package=com.microsoft.bing appop=NONE\n"
            +"    mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION fmt=TRANSLUCENT\n"
            +"    Requested w=523 h=1000\n    mHasSurface="+visible+" isReadyForDisplay()="+visible+"\n"
            +"      Surface: shown="+visible+" layer=0 alpha=1.0 extra\n    isOnScreen="+visible+"\n    isVisible="+visible+"\n";
    }
    static String statusBar(){return "  Window #3 Window{ccc u0 StatusBar}:\n"
        +"    mDisplayId=0 rootTaskId=1 mSession=Session{123 574:u0a10049}\n"
        +"    mOwnerUid=10049 showForAllUsers=true package=com.android.systemui appop=NONE\n"
        +"    mAttrs={(0,0)(fillx96) ty=STATUS_BAR fmt=TRANSLUCENT\n    paramsForRotation ty=STATUS_BAR\n"
        +"    Requested w=523 h=96\n    mHasSurface=true isReadyForDisplay()=true\n"
        +"      Surface: shown=true layer=0 alpha=1.0 extra\n    isOnScreen=true\n    isVisible=true\n";}
    static String windows(String component,String window,String activity,String task){return "mTopFocusedDisplayId=0\n"
        +"mCurrentFocus=Window{"+window+" u0 "+component+"}\n"
        +"mFocusedApp=ActivityRecord{"+activity+" u0 "+component+" t"+task+"}\n"
        +window(component,window,true).replace("rootTaskId=15","rootTaskId="+task)+statusBar();}
    static String activityBlock(String component,String activity,String task,boolean ready){return "    * Hist  #1: ActivityRecord{"+activity+" u0 "+component+" t"+task+"}\n"
        +"      rootOfTask=false task=Task{abc #"+task+" type=standard A=10073:com.microsoft.bing}\n"
        +"      state="+(ready?"RESUMED":"STOPPED")+" delayedResume=false finishing=false\n"
        +"      keysPaused=false inHistory=true idle=true\n"
        +"      mVisibleRequested="+ready+" mVisible="+ready+" mClientVisible="+ready+" reportedDrawn="+ready+" reportedVisible="+ready+"\n";}
    static String activities(String component,String activity,String task,boolean ready){return activityBlock(component,activity,task,ready)
        +"    topResumedActivity=ActivityRecord{"+activity+" u0 "+component+" t"+task+"}\n";}
    static BingNavigationSnapshot.Result result(BingNavigationSnapshot.Kind kind,long now){
        return result(kind,now,kind==BingNavigationSnapshot.Kind.TEMPLATE?"aaa":"bbb",kind==BingNavigationSnapshot.Kind.TEMPLATE?"ddd":"eee","15");
    }
    static BingNavigationSnapshot.Result result(BingNavigationSnapshot.Kind kind,long now,String window,String activity,String task){
        String c=kind==BingNavigationSnapshot.Kind.TEMPLATE?BingNavigationSnapshot.TEMPLATE:BingNavigationSnapshot.BROWSER;
        return BingNavigationSnapshot.parse(windows(c,window,activity,task),activities(c,activity,task,true),PROFILE,now,now);
    }
    private BingNavigationFixture(){}
}
