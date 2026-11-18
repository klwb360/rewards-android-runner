package com.aliothmoon.maafw.once;

/** Existing UI/source brackets applied to a direct cropped capture; no image or input authority. */
final class CardRevealProbeSource {
    static boolean ui(OverviewUiProtocol.Report report, BingNavigationSnapshot.Ready before,
            long beforeAt, BingNavigationSnapshot.Ready after, BingNavigationSnapshot.Shape shape, long now) {
        if(report==null||!report.qualified()||before==null||after==null||shape==null||now<0
                ||before.kind()!=BingNavigationSnapshot.Kind.TEMPLATE||!before.sameSource(after)
                ||!shape.equals(before.profile().shape())||shape.rotation()!=0)return false;
        var source=report.source();
        return before.observed()<=beforeAt&&beforeAt<=source.started()&&before.fresh(source.started())
                &&source.finished()<=after.lower()&&after.fresh(now)
                &&NativeProgressPublication.frameFresh(now,source.started())&&source.displayId()==0
                &&source.left()>=0&&source.top()>=0&&source.right()<=shape.width()&&source.bottom()<=shape.height()
                &&source.right()>source.left()&&source.bottom()>source.top()
                &&(source.rotationBefore()==-1||source.rotationBefore()==shape.rotation())
                &&(source.rotationAfter()==-1||source.rotationAfter()==shape.rotation());
    }
    static boolean beforeCrop(OverviewUiProtocol.Report report,BingNavigationSnapshot.Ready before,
            long beforeAt,BingNavigationSnapshot.Ready afterUi,BingNavigationSnapshot.Ready imageBefore,
            BingNavigationSnapshot.Shape shape,long lower,long deadline,long now) {
        return now<deadline&&boundSource(report,before,beforeAt,afterUi,imageBefore,shape,lower,now);
    }
    /** An expired, otherwise unchanged source can explain a discarded observation only. */
    static boolean expiredBeforeCrop(OverviewUiProtocol.Report report,BingNavigationSnapshot.Ready before,
            long beforeAt,BingNavigationSnapshot.Ready afterUi,BingNavigationSnapshot.Ready imageBefore,
            BingNavigationSnapshot.Shape shape,long lower,long deadline,long now) {
        return now>=deadline&&boundSource(report,before,beforeAt,afterUi,imageBefore,shape,lower,now);
    }
    private static boolean boundSource(OverviewUiProtocol.Report report,BingNavigationSnapshot.Ready before,
            long beforeAt,BingNavigationSnapshot.Ready afterUi,BingNavigationSnapshot.Ready imageBefore,
            BingNavigationSnapshot.Shape shape,long lower,long now) {
        return imageBefore!=null&&ui(report,before,beforeAt,afterUi,shape,now)
                &&before.sameSource(imageBefore)&&imageBefore.fresh(now)
                &&afterUi.observed()<=imageBefore.lower()&&imageBefore.observed()<=lower
                &&lower<=now&&NativeProgressPublication.frameFresh(now,lower);
    }
    static boolean afterCrop(OverviewUiProtocol.Report report,BingNavigationSnapshot.Ready before,
            long beforeAt,BingNavigationSnapshot.Ready afterUi,BingNavigationSnapshot.Ready imageBefore,
            BingNavigationSnapshot.Ready imageAfter,BingNavigationSnapshot.Shape shape,long lower,
            long helperStarted,long helperFinished,long deadline,long now) {
        return beforeCrop(report,before,beforeAt,afterUi,imageBefore,shape,lower,deadline,now)
                &&imageAfter!=null&&imageBefore.sameSource(imageAfter)&&imageAfter.fresh(now)
                &&helperStarted>=lower&&helperFinished>=helperStarted
                &&imageAfter.lower()>=helperFinished;
    }
    private CardRevealProbeSource(){}
}
