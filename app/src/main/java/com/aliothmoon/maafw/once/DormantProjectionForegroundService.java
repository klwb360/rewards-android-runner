package com.aliothmoon.maafw.once;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Private normal-UID components in :capture_once. BEFORE/current-status and
 * AFTER each have one permanent start slot. No extras/tokens or restart authority. */
public class DormantProjectionForegroundService extends Service {
    public static final class After extends DormantProjectionForegroundService { }
    private static final ForegroundPhaseSlots<Pending> SLOTS=new ForegroundPhaseSlots<>();
    private record Pending(AndroidCaptureAdmissionPorts ports,CaptureAdmissionCoordinator.Ticket ticket) { }
    private ForegroundPhaseSlots.Lease<Pending> owned;
    private boolean stopRequested;
    static final class Start {
        private final ForegroundPhaseSlots.Lease<Pending> lease;
        private Start(ForegroundPhaseSlots.Lease<Pending> lease){this.lease=lease;}
    }
    static ForegroundPhaseSlots.Snapshot snapshot(Start start){return start==null?null:SLOTS.snapshot(start.lease);}
    static Start reserve(AndroidCaptureAdmissionPorts ports,CaptureAdmissionCoordinator.Ticket ticket,ForegroundPhaseSlots.Phase phase){
        CaptureReleaseGate.requireConfigured();ticket.requireLive();ticket.requireCurrent();
        return new Start(SLOTS.reserve(phase,new Pending(ports,ticket)));
    }
    private static Class<? extends DormantProjectionForegroundService> component(ForegroundPhaseSlots.Phase phase){
        return phase==ForegroundPhaseSlots.Phase.AFTER?After.class:DormantProjectionForegroundService.class;
    }
    private ForegroundPhaseSlots.Phase exactPhase(){
        if(getClass()==DormantProjectionForegroundService.class)return ForegroundPhaseSlots.Phase.BEFORE;
        if(getClass()==After.class)return ForegroundPhaseSlots.Phase.AFTER;
        throw new SecurityException("Exact private foreground component required");
    }
    static void requestStart(Context context,Start start){
        CaptureReleaseGate.requireConfigured();
        var lease=start.lease;Pending pending=SLOTS.payload(lease);
        pending.ticket.requireLive();pending.ticket.requireCurrent();
        // Construction and local rejection occur before the may-have-dispatched boundary.
        Intent intent=new Intent(context,component(lease.phase));
        SLOTS.beginDispatch(lease);
        try{context.startForegroundService(intent);SLOTS.dispatchReturned(lease);}
        catch(RuntimeException failure){SLOTS.unknown(lease);throw failure;}
    }
    static void finishAndAwait(Context context,Start start){
        CaptureReleaseGate.requireConfigured();
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Never wait for service destruction on main");
        var lease=start.lease;SLOTS.requestClose(lease);
        if(SLOTS.isClosed(lease))return; // Known not dispatched, not a stop request receipt.
        if(!new Handler(Looper.getMainLooper()).post(()->{
            Object service=SLOTS.serviceToStop(lease);
            if(service instanceof DormantProjectionForegroundService capture)capture.stopOwned(lease);
            else if(!SLOTS.isDestroyed(lease)&&!SLOTS.isClosed(lease)){
                try{context.stopService(new Intent(context,component(lease.phase)));}
                catch(RuntimeException failure){SLOTS.unknown(lease);}
                // stopService return is not a delivery or destruction acknowledgement.
            }
        })){SLOTS.unknown(lease);throw new IllegalStateException("Main stop queue unavailable");}
        try{if(!SLOTS.awaitClosed(lease,5000))throw new IllegalStateException("Foreground destruction unknown");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Foreground destruction interrupted");}
    }
    private void stopOwned(ForegroundPhaseSlots.Lease<Pending> expected){
        if(Looper.myLooper()!=Looper.getMainLooper()||owned!=expected)throw new SecurityException("Wrong service stop owner");
        if(stopRequested)return;stopRequested=true;
        try{stopForeground(STOP_FOREGROUND_REMOVE);}catch(RuntimeException failure){SLOTS.unknown(expected);}
        try{stopSelf();}catch(RuntimeException failure){SLOTS.unknown(expected);}
        // Only the exact onDestroy below acknowledges completion.
    }
    @Override public int onStartCommand(Intent ignored,int flags,int startId){
        CaptureReleaseGate.requireConfigured();
        if(owned!=null)return START_NOT_STICKY; // Duplicate callback cannot replace or stop this tuple.
        owned=SLOTS.deliver(exactPhase(),this);
        if(owned==null){stopSelf(startId);return START_NOT_STICKY;}
        Pending pending=SLOTS.payload(owned);
        try{
            pending.ticket.requireLive();pending.ticket.requireActiveScalar();
            if(!SLOTS.mayStart(owned,this))throw new SecurityException("Foreground tuple closing");
            if(Build.VERSION.SDK_INT<34)throw new UnsupportedOperationException();
        }catch(RuntimeException cancelled){pending.ports.foregroundFailed(this,pending.ticket);stopOwned(owned);return START_NOT_STICKY;}
        try{
            String channelId="rewards-explicit-one-shot-projection";
            getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(channelId,"One-shot read-only capture",NotificationManager.IMPORTANCE_LOW));
            Notification notification=new Notification.Builder(this,channelId)
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("One-shot read-only capture")
                .setContentText("User-authorized display capture in progress").setOngoing(true).build();
            startForeground(17431,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            pending.ports.foregroundReturned(this,pending.ticket);
        }catch(RuntimeException failure){SLOTS.unknown(owned);pending.ports.foregroundFailed(this,pending.ticket);stopOwned(owned);}
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        var finished=owned;Pending pending=finished==null?null:SLOTS.payload(finished);owned=null;
        try{
            super.onDestroy();
            if(pending!=null)pending.ports.serviceDestroyed(this);
            if(finished!=null)SLOTS.destroyed(finished,this); // Final direct scalar ACK, never an owner-loop job.
        }catch(RuntimeException failure){if(finished!=null)SLOTS.unknown(finished);throw failure;}
    }
}
