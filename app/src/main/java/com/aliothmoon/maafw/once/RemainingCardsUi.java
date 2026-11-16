package com.aliothmoon.maafw.once;

import android.app.Activity;
import android.widget.*;
import java.util.function.Consumer;

/** Visible per-card confirmations. Choosing never captures or grants task input. */
final class RemainingCardsUi {
    static final String OPEN="打开其余卡片模拟测试（尚不采集）";
    static final String RUN="确认本张卡片模拟标记";
    static final String OPEN_REPAIR="打开识别修复模拟 V2（尚不采集）";
    static final String RUN_REPAIR="确认本张修复模拟标记";
    private static LinearLayout page(Activity a,String text){
        ScrollView scroll=new ScrollView(a);LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,24,24,24);
        TextView label=new TextView(a);label.setText(text);label.setTextIsSelectable(true);box.addView(label);scroll.addView(box);a.setContentView(scroll);return box;
    }
    private static void button(Activity a,LinearLayout box,String text,boolean enabled,Runnable action){
        Button b=new Button(a);b.setText(text);b.setEnabled(enabled);b.setOnClickListener(v->{if(b.isEnabled()){b.setEnabled(false);action.run();}});box.addView(b);
    }
    static void choose(Activity a,Consumer<RemainingCardChoice> select,Runnable close){
        LinearLayout box=page(a,"其余卡片独立模拟测试\n每次仅测试一张完整卡片。本页不采集，不点击任务。序号按当前区域结构顺序，不代表任务完成状态。");
        button(a,box,OPEN_REPAIR,true,()->chooseRepair(a,select,close));
        var weekly=new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,0);
        button(a,box,weekly.label(),true,()->select.accept(weekly));
        for(var role:new OverviewUiRegions.Role[]{OverviewUiRegions.Role.DAILY_ACTIVITIES,OverviewUiRegions.Role.MORE_ACTIVITIES})for(int i=1;i<=8;i++){
            var choice=new RemainingCardChoice(role,i);button(a,box,choice.label(),true,()->select.accept(choice));
        }
        button(a,box,"关闭",true,close);
    }
    private static void chooseRepair(Activity a,Consumer<RemainingCardChoice> select,Runnable close){
        LinearLayout box=page(a,"识别修复模拟 V2\n每次仅测试一张完整局部卡片。保留旧测试记录，不采集整屏，不点击真实任务。");
        for(var choice:new RemainingCardChoice[]{
                new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,0,true),
                new RemainingCardChoice(OverviewUiRegions.Role.DAILY_ACTIVITIES,1,true),
                new RemainingCardChoice(OverviewUiRegions.Role.DAILY_ACTIVITIES,3,true),
                new RemainingCardChoice(OverviewUiRegions.Role.MORE_ACTIVITIES,1,true)}){
            button(a,box,choice.label(),true,()->select.accept(choice));
        }
        button(a,box,"关闭",true,close);
    }
    static void confirm(Activity a,RemainingCardChoice choice,boolean ready,Runnable run,Runnable close){
        LinearLayout box=page(a,choice.label()+"\n确认后由程序自主定位并滚动，完整停稳后仅直接截取该卡片一次，再由APK内原生OCR分别核对小区域。\n"
            +"区域已满分时跳过；数字旁有勾时只标记已完成，不生成拟点击点。七日进度卡只画视觉边界和当前环，不作为任务按钮。\n"
            +"没有任务点击、整屏截图、搜索或阅读操作。原图、标记图和无原文诊断只保存在本机，全部旧记录保留。\n"
            +"每张卡片使用独立一次性记录；来源、边界或识别不确定时停止。");
        button(a,box,choice.repair()?RUN_REPAIR:RUN,ready,run);button(a,box,"关闭",true,close);
    }
    static void running(Activity a,RemainingCardChoice choice,Runnable close){LinearLayout box=page(a,choice.label()+"\n正在定位完整局部卡片并运行原生OCR模拟。没有点击真实任务。");button(a,box,"停止并关闭",true,close);}
    static void status(Activity a,RemainingCardChoice choice,String summary,Runnable close){LinearLayout box=page(a,choice.label()+"\n本次独立测试已停止；只供检查识别与标点，不代表实际赚分。\n"+summary);button(a,box,"关闭",true,close);}
    private RemainingCardsUi(){}
}
