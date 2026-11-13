package com.aliothmoon.maafw.once;
import android.app.Activity;
import android.widget.*;
/** Separate visible v23 disclosure. Older business entry points remain disabled. */
final class AutonomousCaptureUi {
 private static LinearLayout body(Activity a){
  ScrollView scroll=new ScrollView(a);LinearLayout column=new LinearLayout(a);column.setOrientation(LinearLayout.VERTICAL);
  int pad=(int)(24*a.getResources().getDisplayMetrics().density);column.setPadding(pad,pad,pad,pad);scroll.addView(column);a.setContentView(scroll);return column;
 }
 private static void text(Activity a,LinearLayout column,String text){TextView v=new TextView(a);v.setText(text);v.setTextSize(16);column.addView(v);}
 private static void button(Activity a,LinearLayout column,String label,boolean enabled,Runnable action){Button b=new Button(a);b.setText(label);b.setEnabled(enabled);b.setOnClickListener(v->action.run());column.addView(b);}
 static void choice(Activity a,String permission,boolean ready,Runnable start,Runnable cancel){
  LinearLayout c=body(a);text(a,c,"原生单任务自主闭环 · 新授权 v23");
  text(a,c,"仅选择 More 中标题 Do you know the answer?、正文 Challenge yourself with these trivia questions、+5 的未完成卡。另一同名卡、Quote 和签到均不触碰。首帧必须原生识别唯一身份、归属、边界与未完成状态；未知则停止。");
  text(a,c,"本次开始同时批准两张独立整屏帧、至多一次任务卡触摸，以及仅在已确认的 Bing Browser 就绪后至多一次原生 Back。返回原任务页证据确认、第一帧清理完成且回执已封存后，应用将恢复自己的确认页并自动请求第二张新帧的系统许可。不回答问题、不搜索、购买、登录或执行其他账户操作。");
  text(a,c,"每张帧都有新的 Android 系统投屏许可；第二阶段仍须真实可见、前台焦点与本次未撤销意图。不能恢复自身页面、来源改变、未知清理、取消或超时均停止，不自动重试。只以第二原生帧的同一任务勾选判断当前完成，不核总积分或断言点击因果。");
  text(a,c,"独立 UTC 2026-11-11 20:15 至 21:15，末90秒不发新采集许可；会话180秒，每帧最多90秒且不超会话剩余。仅本机处理，不保存或外发截图与OCR原文。整屏可能含其他应用的敏感内容。旧范围和UNKNOWN不会复用或清除。");
  text(a,c,permission);button(a,c,"开始：一张任务、两帧与至多一次返回",ready,start);button(a,c,"取消",true,cancel);
 }
 static void status(Activity a,String message,boolean terminal,Runnable close){
  LinearLayout c=body(a);text(a,c,"原生单任务闭环 v23");text(a,c,message);
  text(a,c,"点击或返回不是完成证明。只有新鲜原生 AFTER 帧核对同一任务可靠勾选才报告当前完成。不会再次点击、人工替代返回或续期重试。");
  button(a,c,terminal?"关闭结果":"停止并关闭",true,close);
 }
 private AutonomousCaptureUi(){}
}
