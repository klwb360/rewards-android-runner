package com.aliothmoon.maafw.once;

import android.app.Activity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Visible entry for one bounded homepage review, with no business task input. */
final class OverviewReviewUi {
    static final String OPEN="打开主页审查修复验证（尚不采集）";
    static final String RUN="确认本次修复验证与模拟标记";
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
        LinearLayout box=page(a,"Rewards 主页审查修复验证\n"
                +"确认后隐藏本页，在当前 Rewards 主页受控滚动，识别签到、每日活动、更多活动和进度。"
                +"搜索、阅读与活动详情页暂不处理。\n最多采集12帧、滚动11次，最长180秒；到达页面底部或触及上限即停止。\n"
                +"仅标出未执行的模拟点，不点击任务、不输入搜索、不领取奖励。完成项跳过，无法确认的区域标记待核对。\n"
                +"区域截图与标记图只保存本机；保留上次待确认结果和所有旧记录，本次使用新的独立入口，结束后停止。\n"
                +(ready?"现有服务可用，确认后重新核对页面来源。":"现有服务或权限不可用，不能开始。"));
        button(a,box,RUN,ready,run);button(a,box,"关闭",true,close);
    }
    static void running(Activity a,Runnable close){
        LinearLayout box=page(a,"正在准备主页全区域审查。程序只滚动页面并生成本地标记图；不点击任务。");
        button(a,box,"停止并关闭",true,close);
    }
    static void status(Activity a,String summary,Runnable close){
        LinearLayout box=page(a,"主页全区域审查已停止\n模拟点未执行；未确认区域不视为通过，也不代表获得积分。\n"+summary);
        button(a,box,"关闭",true,close);
    }
    private OverviewReviewUi(){}
}
