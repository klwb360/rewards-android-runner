package com.aliothmoon.maafw.once;
import android.app.Activity;
import android.widget.*;
/** Local choices only. Opening the separate alignment explanation grants no capture authority. */
final class OwnStateSummaryUi {
 static final boolean ACTIVE=true;
 static final String BUTTON="V54：只读本应用状态，不访问Bing";
 static final String ALIGNMENT_OPEN_BUTTON="打开截图对齐测试（尚不采集）";
 static final String ALIGNMENT_RUN_BUTTON="确认只读：1次结构 + 1张截图";
 static final String DIAGNOSTIC_OPEN_BUTTON="打开对齐诊断测试（尚不采集）";
 static final String DIAGNOSTIC_RUN_BUTTON="确认诊断：1次结构 + 1张截图";
 static final String CALIBRATION_FIRST_BUTTON="打开校准测试 1（尚不采集）";
 static final String CALIBRATION_CONFIRM_BUTTON="打开校准测试 2（尚不采集）";
 static final String CALIBRATION_RUN_BUTTON="确认校准：1次结构 + 1张截图";
 static final String TITLE_TIMING_OPEN_BUTTON="打开标题与耗时诊断（尚不采集）";
 static final String TITLE_TIMING_RUN_BUTTON="确认标题与耗时诊断（仅模拟点）";
 private static LinearLayout page(Activity a,String text){ScrollView scroll=new ScrollView(a);LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,24,24,24);TextView label=new TextView(a);label.setText(text);label.setTextIsSelectable(true);box.addView(label);scroll.addView(box);a.setContentView(scroll);return box;}
 private static void button(Activity a,LinearLayout box,String label,Runnable action){Button b=new Button(a);b.setText(label);b.setOnClickListener(v->{b.setEnabled(false);action.run();});box.addView(b);}
 static void choice(Activity a,Runnable run,Runnable close){choice(a,run,null,close);}
 static void choice(Activity a,Runnable run,Runnable openAlignment,Runnable close){choice(a,run,openAlignment,close,null);}
 static void choice(Activity a,Runnable run,Runnable openAlignment,Runnable close,Runnable diagnostic){choice(a,run,openAlignment,close,diagnostic,null,null);}
 static void choice(Activity a,Runnable run,Runnable openAlignment,Runnable close,Runnable diagnostic,Runnable first,Runnable confirmation){choice(a,run,openAlignment,close,diagnostic,first,confirmation,null);}
 static void choice(Activity a,Runnable run,Runnable openAlignment,Runnable close,Runnable diagnostic,Runnable first,Runnable confirmation,Runnable titleTiming){choice(a,run,openAlignment,close,diagnostic,first,confirmation,titleTiming,null);}
 static void choice(Activity a,Runnable run,Runnable openAlignment,Runnable close,Runnable diagnostic,Runnable first,Runnable confirmation,Runnable titleTiming,Runnable overview){LinearLayout box=page(a,"V54 本应用持久化状态摘要\n只读本UID白名单文件；不创建、修复、初始化或清空账本。未知、损坏、未决保留HOLD。只报告安全枚举和数量；本地缺失不代表没有历史。\n本应用状态摘要不运行Bing、UIA、截图或OCR，不执行任何积分或导航动作。V53计数读取预算保持已消费，不重新开放。摘要不授予动作资格。");button(a,box,BUTTON,run);if(openAlignment!=null)button(a,box,ALIGNMENT_OPEN_BUTTON,openAlignment);if(diagnostic!=null)button(a,box,DIAGNOSTIC_OPEN_BUTTON,diagnostic);if(first!=null)button(a,box,CALIBRATION_FIRST_BUTTON,first);if(confirmation!=null)button(a,box,CALIBRATION_CONFIRM_BUTTON,confirmation);if(titleTiming!=null)button(a,box,TITLE_TIMING_OPEN_BUTTON,titleTiming);if(overview!=null)button(a,box,OverviewReviewUi.OPEN,overview);button(a,box,"关闭",close);}
 static void alignmentChoice(Activity a,String status,boolean enabled,Runnable run,Runnable close){
  alignmentChoice(a,status,enabled,run,close,false);
 }
 static void alignmentChoice(Activity a,String status,boolean enabled,Runnable run,Runnable close,boolean diagnostic){
  LinearLayout box=page(a,(diagnostic?"独立对齐诊断测试 v2\n本次新增独立记录，只报告检查阶段与耗时；旧对齐记录保持不变。\n":"独立截图对齐测试\n")+"确认后隐藏本页，检查当前Bing页面：读取1次界面结构、1张截图并识别文字。\n不点击任务、不返回、不翻页；不保存或发送原图。旧记录保留。本次只检查定位，不代表任务完成或赚分。\n"+status);
  Button start=new Button(a);start.setText(diagnostic?DIAGNOSTIC_RUN_BUTTON:ALIGNMENT_RUN_BUTTON);start.setEnabled(enabled);start.setOnClickListener(v->{if(enabled){start.setEnabled(false);run.run();}});box.addView(start);button(a,box,"关闭",close);
 }
 static void alignmentRunning(Activity a,Runnable close){LinearLayout box=page(a,"正在准备一次独立只读测试；当前页不符合条件时停止，不自动导航。");button(a,box,"停止并关闭",close);}
 static void calibrationChoice(Activity a,String status,boolean enabled,Runnable run,Runnable close,UiImageAlignmentScope.Calibration attempt){
  LinearLayout box=page(a,(attempt==UiImageAlignmentScope.Calibration.TITLE_TIMING?"标题分类与分段耗时诊断 v4":"边框与耗时校准测试 "+(attempt==UiImageAlignmentScope.Calibration.FIRST?"1":"2"))
   +(attempt==UiImageAlignmentScope.Calibration.TITLE_TIMING?"\n只报告不含原文的标题分类和耗时；通过定位的卡片仅给出未执行的模拟点，不判断任务完成状态。本应用不保存原图；复核截图由配套工具另存本机，不上传。":"")+"\n先准备识别模型，再读取1次当前Bing界面结构、1张截图并识别文字。\n本次使用独立记录，保留所有旧记录；不点击任务、不返回、不翻页，不保存或发送原图。只检查定位和耗时，不代表赚分。\n"+status);
  Button start=new Button(a);start.setText(attempt==UiImageAlignmentScope.Calibration.TITLE_TIMING?TITLE_TIMING_RUN_BUTTON:CALIBRATION_RUN_BUTTON);start.setEnabled(enabled);start.setOnClickListener(v->{if(enabled){start.setEnabled(false);run.run();}});box.addView(start);button(a,box,"关闭",close);
 }
 static void alignmentStatus(Activity a,String safeSummary,Runnable close){LinearLayout box=page(a,"独立只读测试记录\n仅供检查定位，不代表任务完成；不会继续执行任务。\n"+safeSummary);button(a,box,"关闭",close);}
 static void running(Activity a,Runnable close){LinearLayout box=page(a,"正在有界只读本应用状态；不写入业务文件。");button(a,box,"停止并关闭",close);}
 static void rejected(Activity a,Runnable close){LinearLayout box=page(a,"OWN_STATE publication=REJECTED actionAuthority=false HOLD");button(a,box,"关闭",close);}
 private OwnStateSummaryUi(){}
}
