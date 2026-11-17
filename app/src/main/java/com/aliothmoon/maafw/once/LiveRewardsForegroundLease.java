package com.aliothmoon.maafw.once;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;

/** One in-memory service tuple. It grants process liveness only, never task input. */
final class LiveRewardsForegroundLease {
    static final long STARTUP_MILLIS=5_000L;
    interface Clock { long elapsedRealtime();String day(); }
    enum State { RESERVED, START_REQUESTED, DELIVERED, READY, CLOSING, DESTROYED, UNKNOWN }
    enum Reason { NONE, CANCELLED, DATE_CHANGED, DEADLINE, START_TIMEOUT, CLOCK_UNKNOWN,
        START_FAILED, SERVICE_LOST, STOP_UNKNOWN }
    enum Delivery { START_PENDING, CANCEL_OWNED, REJECT_UNOWNED, IGNORE }
    static Delivery delivery(boolean owned,boolean dispatchedPending,boolean start,boolean cancel,boolean tokenMatches){
        if(cancel)return owned?(tokenMatches?Delivery.CANCEL_OWNED:Delivery.IGNORE):Delivery.REJECT_UNOWNED;
        if(!start)return owned?Delivery.IGNORE:Delivery.REJECT_UNOWNED;
        return owned?Delivery.IGNORE:dispatchedPending?Delivery.START_PENDING:Delivery.REJECT_UNOWNED;
    }
    final Object owner;
    final String runId,cancelToken,day;
    final long issued,deadline,startupDeadline;
    private final Clock clock;
    private final Runnable onReady,onRevoke,onDestroyed;
    private final AtomicBoolean revokeSent=new AtomicBoolean(),destroySent=new AtomicBoolean();
    private State state=State.RESERVED;
    private Reason reason=Reason.NONE;
    private Object service;
    private boolean dispatchAttempted,dispatchReturned,foregroundReturned,destroyed,destroyObserved,closeRequested;
    private long readyElapsed=-1,destroyedElapsed=-1;

    LiveRewardsForegroundLease(Object owner,String runId,String token,long issued,long deadline,String day,
            Clock clock,Runnable onReady,Runnable onRevoke,Runnable onDestroyed){
        if(owner==null||runId==null||!runId.matches("[a-f0-9-]{36}")||token==null||!token.matches("[a-f0-9-]{36}")
                ||issued<0||deadline<=issued||deadline-issued>LiveRunAuthority.MAX_RUN_MILLIS||clock==null
                ||onReady==null||onRevoke==null||onDestroyed==null)throw new IllegalArgumentException("Exact foreground tuple required");
        LocalDate.parse(day);this.owner=owner;this.runId=runId;cancelToken=token;this.issued=issued;this.deadline=deadline;this.day=day;
        startupDeadline=Math.min(deadline,issued>Long.MAX_VALUE-STARTUP_MILLIS?Long.MAX_VALUE:issued+STARTUP_MILLIS);
        this.clock=clock;this.onReady=onReady;this.onRevoke=onRevoke;this.onDestroyed=onDestroyed;
    }
    private Reason invalidTime(boolean startup){
        try{
            return invalidTimeAt(clock.elapsedRealtime(),clock.day(),startup);
        }catch(RuntimeException unknown){return Reason.CLOCK_UNKNOWN;}
    }
    private Reason invalidTimeAt(long now,String currentDay,boolean startup){
        if(now<issued)return Reason.CLOCK_UNKNOWN;
        if(!day.equals(currentDay))return Reason.DATE_CHANGED;
        if(now>=deadline)return Reason.DEADLINE;
        return startup&&now>=startupDeadline?Reason.START_TIMEOUT:Reason.NONE;
    }
    private long timestamp(){try{return clock.elapsedRealtime();}catch(RuntimeException unknown){return -1;}}
    boolean beginDispatch(Object exactOwner){
        synchronized(this){if(exactOwner!=owner||state!=State.RESERVED||reason!=Reason.NONE)return false;}
        Reason invalid=invalidTime(true);boolean accepted;
        synchronized(this){accepted=exactOwner==owner&&state==State.RESERVED&&reason==Reason.NONE&&invalid==Reason.NONE;
            if(accepted){dispatchAttempted=true;state=State.START_REQUESTED;}}
        if(invalid!=Reason.NONE)cancel(invalid);return accepted;
    }
    synchronized void dispatchReturned(Object exactOwner){if(exactOwner==owner&&dispatchAttempted)dispatchReturned=true;}
    boolean deliver(Object exactService){
        if(exactService==null)return false;
        Reason invalid=invalidTime(true);boolean accepted;
        synchronized(this){
            if(!dispatchAttempted||destroyed||service!=null)return false;
            service=exactService;accepted=!closeRequested&&reason==Reason.NONE&&invalid==Reason.NONE;
            state=accepted?State.DELIVERED:State.CLOSING;
        }
        if(invalid!=Reason.NONE)cancel(invalid);return accepted;
    }
    boolean foregroundReturned(Object exactService){
        synchronized(this){if(service!=exactService||state!=State.DELIVERED||closeRequested||reason!=Reason.NONE)return false;}
        long acknowledged;String currentDay;
        try{acknowledged=clock.elapsedRealtime();currentDay=clock.day();}
        catch(RuntimeException failed){cancel(Reason.CLOCK_UNKNOWN);return false;}
        Reason invalid=invalidTimeAt(acknowledged,currentDay,true);boolean accepted;
        synchronized(this){accepted=service==exactService&&state==State.DELIVERED&&!closeRequested&&reason==Reason.NONE&&invalid==Reason.NONE;
            if(accepted){foregroundReturned=true;readyElapsed=acknowledged;state=State.READY;}}
        if(invalid!=Reason.NONE)cancel(invalid);
        if(accepted)onReady.run();return accepted;
    }
    boolean ready(){
        Reason invalid=invalidTime(false);boolean accepted;
        synchronized(this){accepted=state==State.READY&&foregroundReturned&&!closeRequested&&!destroyed&&reason==Reason.NONE&&invalid==Reason.NONE;}
        if(invalid!=Reason.NONE)cancel(invalid);return accepted;
    }
    void checkDeadline(){Reason invalid=invalidTime(!everReady());if(invalid!=Reason.NONE)cancel(invalid);}
    void cancel(Reason why){
        synchronized(this){if(reason==Reason.NONE)reason=why==null?Reason.CANCELLED:why;
            if(!destroyed&&state!=State.UNKNOWN)state=State.CLOSING;}
        if(revokeSent.compareAndSet(false,true))onRevoke.run();
    }
    void requestClose(Object exactOwner){
        synchronized(this){if(exactOwner!=owner)throw new SecurityException("Exact foreground owner required");
            closeRequested=true;if(!destroyed)state=State.CLOSING;
            if(!dispatchAttempted){destroyed=true;state=State.DESTROYED;}}
        if(destroyed()&&destroySent.compareAndSet(false,true))onDestroyed.run();
    }
    void serviceDestroyed(Object exactService){
        boolean expected;
        synchronized(this){if(service!=exactService||destroyed)return;
            expected=closeRequested;destroyed=true;destroyObserved=true;destroyedElapsed=timestamp();state=State.DESTROYED;}
        if(!expected)cancel(Reason.SERVICE_LOST);
        if(destroySent.compareAndSet(false,true))onDestroyed.run();
    }
    void stopUnknown(){synchronized(this){if(destroyed)return;}cancel(Reason.STOP_UNKNOWN);synchronized(this){if(!destroyed)state=State.UNKNOWN;}}
    synchronized boolean matchesCancel(String token){return token!=null&&cancelToken.equals(token)&&!destroyed;}
    synchronized Object service(){return service;}
    synchronized boolean destroyed(){return destroyed;}
    synchronized boolean destroyObserved(){return destroyObserved;}
    synchronized boolean closeRequested(){return closeRequested;}
    synchronized boolean everReady(){return foregroundReturned;}
    synchronized boolean dispatchAttempted(){return dispatchAttempted;}
    synchronized boolean startReturned(){return dispatchReturned;}
    synchronized long readyElapsed(){return readyElapsed;}
    synchronized long destroyedElapsed(){return destroyedElapsed;}
    synchronized State state(){return state;}
    synchronized Reason reason(){return reason;}
}
