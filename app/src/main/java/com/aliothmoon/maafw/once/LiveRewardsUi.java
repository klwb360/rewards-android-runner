package com.aliothmoon.maafw.once;

import android.app.Activity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Explicit live entry. Selecting the page never issues capture or input authority. */
final class LiveRewardsUi {
    static final String OPEN="打开签入与活动真实流程（尚不执行）";
    static final String RUN="确认开始本轮真实流程";
    private static LinearLayout page(Activity a,String text){
        ScrollView scroll=new ScrollView(a);LinearLayout box=new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,24,24,24);
        TextView label=new TextView(a);label.setText(text);label.setTextIsSelectable(true);
        box.addView(label);scroll.addView(box);a.setContentView(scroll);return box;
    }
    private static void button(Activity a,LinearLayout box,String text,boolean enabled,Runnable action){
        Button b=new Button(a);b.setText(text);b.setEnabled(enabled);
        b.setOnClickListener(v->{if(b.isEnabled()){b.setEnabled(false);action.run();}});box.addView(b);
    }
    static void choice(Activity a,boolean ready,Runnable run,Runnable close){
        LinearLayout box=page(a,"本轮真实流程：签入 → 每日活动 → 更多活动\n"
                +"先进行完整只读预检，再仅处理现场未完成且来源、边框和原生OCR均通过的任务。\n"
                +"活动点击命令成功返回后静置至少3秒；新页面不点击、答题、解谜或滚动，最多返回一次。签入不返回。\n"
                +"返回后仍未确认完成则记录并继续，本轮不重试同一任务。\n"
                +"最多一次签入、每区八张活动卡、总计17次点击与16次返回，最长30分钟。\n"
                +"保留全部历史记录；不执行搜索、阅读或七日进度卡点击，不采集整屏。截图和诊断仅保存在本机。\n"
                +"点击和进度变化都不证明账户积分已到账。来源、输入或清理不明时停止。");
        button(a,box,RUN,ready,run);button(a,box,"关闭",true,close);
    }
    static void running(Activity a,Runnable close){
        LinearLayout box=page(a,"本轮真实流程正在运行。可以停止并关闭；停止不会重置已消费记录。");
        button(a,box,"停止并关闭",true,close);
    }
    static void status(Activity a,String summary,Runnable close){
        LinearLayout box=page(a,"本轮真实流程已停止\n"+summary);
        button(a,box,"关闭",true,close);
    }
    private LiveRewardsUi(){}
}
