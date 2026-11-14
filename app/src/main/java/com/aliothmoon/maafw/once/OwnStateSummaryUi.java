package com.aliothmoon.maafw.once;
import android.app.Activity;
import android.widget.*;
/** V54 local UI only; no capture or action scope. */
final class OwnStateSummaryUi {
 static final boolean ACTIVE=true;
 static final String BUTTON="V54：只读本应用状态，不访问Bing";
 private static LinearLayout page(Activity a,String text){ScrollView scroll=new ScrollView(a);LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,24,24,24);TextView label=new TextView(a);label.setText(text);label.setTextIsSelectable(true);box.addView(label);scroll.addView(box);a.setContentView(scroll);return box;}
 private static void button(Activity a,LinearLayout box,String label,Runnable action){Button b=new Button(a);b.setText(label);b.setOnClickListener(v->{b.setEnabled(false);action.run();});box.addView(b);}
 static void choice(Activity a,Runnable run,Runnable close){LinearLayout box=page(a,"V54 本应用持久化状态摘要\n只读本UID白名单文件；不创建、修复、初始化或清空账本。未知、损坏、未决保留HOLD。只报告安全枚举和数量；本地缺失不代表没有历史。\n不运行Bing、UIA、截图或OCR，不执行任何积分或导航动作。V53计数读取预算保持已消费，不重新开放。摘要不授予动作资格。");button(a,box,BUTTON,run);button(a,box,"关闭",close);}
 static void running(Activity a,Runnable close){LinearLayout box=page(a,"正在有界只读本应用状态；不写入业务文件。");button(a,box,"停止并关闭",close);}
 static void rejected(Activity a,Runnable close){LinearLayout box=page(a,"OWN_STATE publication=REJECTED actionAuthority=false HOLD");button(a,box,"关闭",close);}
 private OwnStateSummaryUi(){}
}
