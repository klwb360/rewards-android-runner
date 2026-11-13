package com.aliothmoon.maafw.once;
/** Fixed two-stage intent bound to one live run. No Android, disk, Intent or restored state. */
final class AutonomousStageRoute {
 private enum State { APPROVED, RESTORE_REQUESTED, REENTERED, SECOND_CLAIMED, SECOND_CONSUMED, STOPPED }
 static final class Continuation {
  private final AutonomousStageRoute route;
  private Continuation(AutonomousStageRoute route){this.route=route;}
 }
 private final Object run;
 private State state=State.APPROVED;
 private Continuation continuation;
 AutonomousStageRoute(Object run){this.run=java.util.Objects.requireNonNull(run);}
 /** Called only after the BEFORE receipt actually committed while hidden, source return
  * was observed, and all first-phase resources were closed. Spent before OS navigation. */
 synchronized boolean requestRestore(Object exact,boolean eligible){
  if(exact!=run||!eligible||state!=State.APPROVED)return false;
  state=State.RESTORE_REQUESTED;return true;
 }
 /** Actual same-instance onStart, not the return from AppTask.moveToFront(). */
 synchronized boolean onStart(Object exact,boolean eligible){
  if(exact!=run||!eligible||state!=State.RESTORE_REQUESTED)return false;
  state=State.REENTERED;return true;
 }
 synchronized Continuation claimSecond(Object exact,boolean visibleFocused,boolean eligible){
  if(exact!=run||!visibleFocused||!eligible||state!=State.REENTERED)return null;
  state=State.SECOND_CLAIMED;return continuation=new Continuation(this);
 }
 synchronized boolean consume(Continuation token,Object exact,boolean current){
  if(token==null||token.route!=this||token!=continuation||exact!=run||state!=State.SECOND_CLAIMED)return false;
  state=current?State.SECOND_CONSUMED:State.STOPPED;return current;
 }
 synchronized boolean pending(){return state==State.RESTORE_REQUESTED||state==State.REENTERED;}
 synchronized void cancel(){state=State.STOPPED;continuation=null;}
}
