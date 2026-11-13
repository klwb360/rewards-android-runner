package com.aliothmoon.maafw.once;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Connected consent/acquisition facade behind the default-off release policy.
 * Gate and private ticket checks apply to every entry, including wrapped calls.
 * No Intent/token/projection is persisted, logged, put in service extras or saved
 * instance state. Recreation must discard this instance and request fresh approval. */
final class AndroidCaptureAdmissionPorts implements CaptureAdmissionCoordinator.Ports<MediaProjection> {
    private static final AtomicInteger CODES = new AtomicInteger(17000);
    private final Activity activity;
    private final Handler ownerLoop; private final Runnable onAcquired, closeOnOwner;
    private volatile CaptureAdmissionCoordinator<MediaProjection> coordinator;
    private volatile CaptureAdmissionCoordinator.Ticket ticket;
    private volatile CaptureAdmissionCoordinator.Request pending;
    private int requestCode;
    private DormantProjectionForegroundService service;
    private boolean foregroundReady, acquired, stopped, projectionReturned;
    ForegroundPhaseSlots.Snapshot foregroundSnapshot(){return DormantProjectionForegroundService.snapshot(foregroundStart);}
    boolean projectionReturned(){return projectionReturned;}
    int consentRequestCode(){return requestCode;}
    private final ForegroundPhaseSlots.Phase phase;
    private DormantProjectionForegroundService.Start foregroundStart;
    AndroidCaptureAdmissionPorts(Activity activity) { this(activity,null,()->{}); }
    AndroidCaptureAdmissionPorts(Activity activity,Handler ownerLoop,Runnable onAcquired) { this(activity,ownerLoop,onAcquired,null); }
    AndroidCaptureAdmissionPorts(Activity activity,Handler ownerLoop,Runnable onAcquired,Runnable closeOnOwner) { this(activity,ownerLoop,onAcquired,closeOnOwner,ForegroundPhaseSlots.Phase.BEFORE); }
    AndroidCaptureAdmissionPorts(Activity activity,Handler ownerLoop,Runnable onAcquired,Runnable closeOnOwner,ForegroundPhaseSlots.Phase phase) { this.activity=Objects.requireNonNull(activity);this.ownerLoop=ownerLoop;this.onAcquired=Objects.requireNonNull(onAcquired);this.phase=Objects.requireNonNull(phase);this.closeOnOwner=closeOnOwner==null?()->{if(coordinator!=null)coordinator.close();}:closeOnOwner; }
    private void dispatch(Runnable job) { if(ownerLoop==null || !ownerLoop.post(()->{try{job.run();}catch(RuntimeException failure){if(coordinator!=null)coordinator.cancel();try{closeOnOwner.run();}catch(RuntimeException unknown){ /* Strong registry retained. */ }}})){if(coordinator!=null)coordinator.cancel();} }
    void bind(CaptureAdmissionCoordinator<MediaProjection> coordinator) {
        CaptureReleaseGate.requireConfigured();
        if(this.coordinator!=null)throw new IllegalStateException();this.coordinator=Objects.requireNonNull(coordinator);
    }
    private void live(CaptureAdmissionCoordinator.Ticket incoming) {
        CaptureReleaseGate.requireConfigured();Objects.requireNonNull(incoming).requireLive();
        if(ticket!=incoming)throw new SecurityException("Wrong Android admission ticket");
    }
    public void launchConsent(CaptureAdmissionCoordinator.Ticket incoming,CaptureAdmissionCoordinator.Request request) {
        CaptureReleaseGate.requireConfigured();Objects.requireNonNull(incoming).requireLive();incoming.requireCurrent();
        if(ticket!=null || coordinator==null || !activity.hasWindowFocus() || activity.isFinishing() || activity.isDestroyed())throw new SecurityException("Fresh visible user start required");
        if(Build.VERSION.SDK_INT<34)throw new UnsupportedOperationException("Natural geometry policy requires API34");
        ticket=incoming;pending=Objects.requireNonNull(request);requestCode=CODES.getAndIncrement();
        if(requestCode>65000)throw new IllegalStateException("Request-code space exhausted; no reuse");
        activity.runOnUiThread(()->{
            try {
                incoming.requireLive();incoming.requireActiveScalar();
                if(activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus())throw new SecurityException("Lost visible Activity");
                MediaProjectionManager manager=activity.getSystemService(MediaProjectionManager.class);
                Intent intent=manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
                activity.startActivityForResult(intent,requestCode);
            } catch(RuntimeException failure) {coordinator.cancel();dispatch(closeOnOwner);}
        });
    }    /** Forward ONLY the current Activity's real onActivityResult callback. Never
     * invoke from launch extras or replay saved state. It consumes pending first. */
    void onActivityResult(int code,int resultCode,Intent data) {
        CaptureReleaseGate.requireConfigured();
        if(pending==null || code!=requestCode)return;
        CaptureAdmissionCoordinator.Request request=pending;pending=null;
        dispatch(()->{if(!coordinator.consentResult(request,resultCode==Activity.RESULT_OK,data))closeOnOwner.run();});
    }
    public void startForeground(CaptureAdmissionCoordinator.Ticket incoming) {
        live(incoming);incoming.requireCurrent();
        if(foregroundStart!=null)throw new ForegroundPhaseSlots.KnownNotDispatched();
        foregroundStart=DormantProjectionForegroundService.reserve(this,incoming,phase);
        DormantProjectionForegroundService.requestStart(activity,foregroundStart);
    }
    void foregroundReturned(DormantProjectionForegroundService current,CaptureAdmissionCoordinator.Ticket incoming) {
        live(incoming);incoming.requireActiveScalar(); dispatch(()->{if(service!=null)throw new IllegalStateException();service=current;foregroundReady=true;coordinator.foregroundReady(current);
            if(coordinator.state()!=CaptureAdmissionCoordinator.State.ACQUIRED){closeOnOwner.run();return;}
            activity.runOnUiThread(()->{
                try {
                    incoming.requireLive();incoming.requireActiveScalar();
                    if(!(activity instanceof DormantCaptureConfirmationActivity confirmation))
                        throw new SecurityException("Exact confirmation Activity required");
                    confirmation.requestHandoff(()->{incoming.requireLive();incoming.requireActiveScalar();},()->dispatch(()->{
                        incoming.requireActiveScalar();
                        if(!confirmation.hiddenWithSoleTask())throw new SecurityException("Own UI reappeared");
                        onAcquired.run();
                    }),()->{coordinator.cancel();dispatch(closeOnOwner);});
                } catch(RuntimeException failure){coordinator.cancel();dispatch(closeOnOwner);}
            });});
    }
    public MediaProjection acquire(CaptureAdmissionCoordinator.Ticket incoming,Object result) {
        live(incoming);incoming.requireCurrent();
        if(!foregroundReady || service==null || acquired || !(result instanceof Intent))throw new SecurityException("FGS receipt and one-use result required");
        acquired=true; // Burn before getMediaProjection, never retry an exception/null.
        MediaProjection value=activity.getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,(Intent)result);
        projectionReturned=value!=null;return value;
    }
    public void stop(CaptureAdmissionCoordinator.Ticket incoming,MediaProjection projection) {
        live(incoming);if(stopped)throw new IllegalStateException();stopped=true;projection.stop();
        // Android may swallow remote stop failure. Return is app-local, never system ACK.
    }
    void activityDestroyed() {
        pending=null;
        if(coordinator!=null) {coordinator.cancel();dispatch(closeOnOwner);}
    }
    public void finishForeground(CaptureAdmissionCoordinator.Ticket incoming) {
        live(incoming);
        if(foregroundStart!=null)DormantProjectionForegroundService.finishAndAwait(activity,foregroundStart);
        // No reservation means a known local rejection before any OS dispatch.
    }
    void foregroundFailed(DormantProjectionForegroundService current,CaptureAdmissionCoordinator.Ticket incoming) {
        live(incoming);if(coordinator!=null){coordinator.cancel();dispatch(closeOnOwner);}
    }
    void serviceDestroyed(DormantProjectionForegroundService current) {
        if(coordinator!=null) {coordinator.cancel();dispatch(()->{coordinator.serviceDestroyed(current);closeOnOwner.run();foregroundReady=false;service=null;});}
    }
}
