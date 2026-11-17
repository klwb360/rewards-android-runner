package com.aliothmoon.maafw.once;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** Private same-process liveness service. Intents can cancel an existing tuple, never authorize work. */
public final class LiveRewardsForegroundService extends Service {
    private static final String PACKAGE="com.aliothmoon.maafw.productrewardsworking.capturereview";
    private static final String CANCEL="com.aliothmoon.maafw.once.LIVE_REWARDS_CANCEL";
    private static final String START="com.aliothmoon.maafw.once.LIVE_REWARDS_START";
    private static final String CHANNEL="rewards-live-bounded-run";
    private static final int NOTIFICATION=17439;
    private static LiveRewardsForegroundLease active;
    private LiveRewardsForegroundLease owned;
    private boolean stopRequested;
    private final Handler statusHandler=new Handler(Looper.getMainLooper());
    private LiveRewardsForegroundLease.Reason shownReason=LiveRewardsForegroundLease.Reason.NONE;
    private final Runnable deadlineCheck=new Runnable(){public void run(){
        var exact=owned;if(exact==null||exact.destroyed())return;
        exact.checkDeadline();
        if(exact.reason()!=shownReason){shownReason=exact.reason();
            try{updateStatus("输入已停止，正在等待清理完成");}catch(RuntimeException ignored){exact.cancel(LiveRewardsForegroundLease.Reason.SERVICE_LOST);}}
        statusHandler.postDelayed(this,1000);
    }};

    static synchronized LiveRewardsForegroundLease reserve(LiveRewardsRuntime owner,String runId,long issued,long deadline,String day,
            Runnable ready,Runnable revoke,Runnable destroyed){
        CaptureReleaseGate.requireConfigured();
        if(owner==null||active!=null)throw new SecurityException("Previous foreground tuple retained");
        active=new LiveRewardsForegroundLease(owner,runId,UUID.randomUUID().toString(),issued,deadline,day,
                new LiveRewardsForegroundLease.Clock(){
                    public long elapsedRealtime(){return SystemClock.elapsedRealtime();}
                    public String day(){return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();}
                },ready,revoke,destroyed);
        return active;
    }
    private static synchronized LiveRewardsForegroundLease pending(){return active;}
    private static synchronized void retire(LiveRewardsForegroundLease exact){if(active==exact&&exact.destroyed())active=null;}
    private static void process(Context context){
        if(context==null||android.os.Build.VERSION.SDK_INT<34||android.os.Process.myUid()<10000
                ||!PACKAGE.equals(context.getPackageName())||!(PACKAGE+":capture_once").equals(Application.getProcessName()))
            throw new SecurityException("Exact foreground process required");
    }
    static void requestStart(DormantCaptureConfirmationActivity activity,LiveRewardsForegroundLease lease){
        process(activity);
        if(Looper.myLooper()!=Looper.getMainLooper()||!activity.isResumedForApproval()||lease==null||pending()!=lease
                ||!lease.beginDispatch(lease.owner))throw new SecurityException("Visible foreground start required");
        try{
            activity.startForegroundService(new Intent(activity,LiveRewardsForegroundService.class).setAction(START));
            lease.dispatchReturned(lease.owner);
        }catch(RuntimeException failure){lease.cancel(LiveRewardsForegroundLease.Reason.START_FAILED);throw failure;}
    }
    static void requestClose(Context context,LiveRewardsForegroundLease lease){
        if(lease==null)return;
        lease.requestClose(lease.owner);
        if(lease.destroyed()){retire(lease);return;}
        if(!new Handler(Looper.getMainLooper()).post(()->{
            if(pending()!=lease)return;
            Object instance=lease.service();
            if(instance instanceof LiveRewardsForegroundService service)service.stopOwned(lease);
            else try{context.stopService(new Intent(context,LiveRewardsForegroundService.class));}
                catch(RuntimeException failed){lease.stopUnknown();}
            // The stop command is not an onDestroy acknowledgement.
        }))lease.stopUnknown();
    }
    static void cleanupUnknown(LiveRewardsForegroundLease lease){
        if(lease==null)return;
        new Handler(Looper.getMainLooper()).post(()->{
            Object instance=lease.service();
            if(instance instanceof LiveRewardsForegroundService service&&service.owned==lease)
                try{service.updateStatus("输入已停止，资源清理未确认");}catch(RuntimeException ignored){lease.cancel(LiveRewardsForegroundLease.Reason.SERVICE_LOST);}
        });
    }
    private Notification notification(String status){
        if(android.os.Build.VERSION.SDK_INT<34)throw new UnsupportedOperationException("Android 14 foreground run required");
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL,"Rewards 本轮运行",NotificationManager.IMPORTANCE_LOW));
        Intent stop=new Intent(this,LiveRewardsForegroundService.class).setAction(CANCEL)
                .setData(Uri.parse("rewards-live-fgs://cancel/"+owned.cancelToken));
        PendingIntent cancel=PendingIntent.getService(this,0,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification value=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Rewards 本轮自动流程").setContentText(status).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"停止本轮",cancel).build()).build();
        return value;
    }
    private void updateStatus(String status){
        if(owned!=null&&owned.everReady()&&!stopRequested)startForeground(NOTIFICATION,notification(status));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(android.os.Build.VERSION.SDK_INT<34){stopSelfResult(startId);return START_NOT_STICKY;}
        process(this);
        var exact=pending();String action=intent==null?null:intent.getAction();
        String data=intent==null?null:intent.getDataString();
        boolean cancelMatches=owned!=null&&("rewards-live-fgs://cancel/"+owned.cancelToken).equals(data)&&owned.matchesCancel(owned.cancelToken);
        var delivery=LiveRewardsForegroundLease.delivery(owned!=null,exact!=null&&exact.dispatchAttempted()&&!exact.destroyed(),
                START.equals(action),CANCEL.equals(action),cancelMatches);
        switch(delivery){
            case IGNORE->{return START_NOT_STICKY;}
            case REJECT_UNOWNED->{stopSelfResult(startId);return START_NOT_STICKY;}
            case CANCEL_OWNED->{
                owned.cancel(LiveRewardsForegroundLease.Reason.CANCELLED);
                try{updateStatus("正在停止输入并清理资源");}catch(RuntimeException ignored){owned.cancel(LiveRewardsForegroundLease.Reason.SERVICE_LOST);}
                return START_NOT_STICKY;
            }
            case START_PENDING->{/* Only the in-memory dispatched tuple may be delivered below. */}
        }
        owned=exact;
        if(!exact.deliver(this)){exact.cancel(LiveRewardsForegroundLease.Reason.START_FAILED);stopOwned(exact);return START_NOT_STICKY;}
        try{
            startForeground(NOTIFICATION,notification("正在执行本轮任务，可随时停止"),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            if(!exact.foregroundReturned(this))stopOwned(exact);
            else if(!statusHandler.postDelayed(deadlineCheck,1000))exact.cancel(LiveRewardsForegroundLease.Reason.SERVICE_LOST);
        }catch(RuntimeException failed){exact.cancel(LiveRewardsForegroundLease.Reason.START_FAILED);stopOwned(exact);}
        return START_NOT_STICKY;
    }
    private void stopOwned(LiveRewardsForegroundLease exact){
        if(Looper.myLooper()!=Looper.getMainLooper()||owned!=exact||pending()!=exact)throw new SecurityException("Exact service stop required");
        if(stopRequested)return;stopRequested=true;
        try{stopForeground(STOP_FOREGROUND_REMOVE);}catch(RuntimeException failed){exact.stopUnknown();}
        try{stopSelf();}catch(RuntimeException failed){exact.stopUnknown();}
    }
    @Override public IBinder onBind(Intent ignored){return null;}
    @Override public void onDestroy(){
        statusHandler.removeCallbacks(deadlineCheck);
        var exact=owned;owned=null;
        try{super.onDestroy();}
        finally{if(exact!=null){try{exact.serviceDestroyed(this);}finally{retire(exact);}}}
    }
}
