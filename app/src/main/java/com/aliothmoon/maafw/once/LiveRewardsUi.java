package com.aliothmoon.maafw.once;

import android.app.Activity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Explicit live entry. Selecting the page never issues capture or input authority. */
final class LiveRewardsUi {
    static final String OPEN="打开签入与活动真实流程（尚不执行）";
    static final String RUN="确认开始本轮真实流程";
    static final String OPEN_READ_ONLY="打开独立只读预检（不点击任务）";
    static final String RUN_READ_ONLY="确认开始只读预检（不点击任务）";
    static final String OPEN_MORE_RECHECK="打开更多活动二次复查（只读）";
    static final String RUN_MORE_RECHECK="确认只读验证更多活动复查";
    static final String OPEN_SINGLE_TASK="打开一次任务试点（尚不执行）";
    static final String RUN_SINGLE_TASK="确认只点击一个任务";
    static final String OPEN_MORE_PAIR="限定更多活动的两张卡片";
    static final String RUN_MORE_PAIR="确认仅补测这两张更多活动";
    private static final Object PAIR_FIELD = new Object();
    private static final Object RECHECK_PAGE = new Object();
    private static final LiveRewardsTaskSelection.VisibleChoice visibleChoice = new LiveRewardsTaskSelection.VisibleChoice();
    private static final LiveMoreUiRecheck.Choice recheckChoice = new LiveMoreUiRecheck.Choice();

    static LiveRewardsTaskSelection takeSelection(Activity activity) { return visibleChoice.take(activity); }
    static boolean takeMoreUiRecheck(Activity activity) { return recheckChoice.take(activity); }
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
        var tagged=a.getWindow().getDecorView().findViewWithTag(PAIR_FIELD);
        if(tagged instanceof EditText editing){
            morePairChoice(a,ready,run,close,editing.getText().toString());
            return;
        }
        LinearLayout box=page(a,"本轮真实流程：签入 → 每日活动 → 更多活动\n"
                +"按区域识别并执行。签入后在原处确认，随后依次核对每日活动、更多活动，识别完整区域后才处理其中的任务。\n"
                +"活动点击命令成功返回后静置至少5秒，不做返回前页面复核，再返回一次。签入不返回。\n"
                +"新页面不点击、答题、解谜或滚动。返回命令成功后，再读取Rewards任务状态。\n"
                +"返回后仍未确认完成则记录并继续，本轮不重试同一任务。\n"
                +"最多一次签入、每区八张活动卡、总计17次点击与16次返回，最长30分钟。\n"
                +"保留全部历史记录；不执行搜索、阅读或七日进度卡点击，不采集整屏。截图和诊断仅保存在本机。\n"
                +"点击和进度变化都不证明账户积分已到账。来源、输入或清理不明时停止。");
        button(a,box,RUN,ready,()->visibleChoice.confirm(a,LiveRewardsTaskSelection.ALL,run));
        button(a,box,OPEN_MORE_PAIR,ready,()->morePairChoice(a,ready,run,close,""));
        button(a,box,"关闭",true,close);
    }

    private static void morePairChoice(Activity a,boolean ready,Runnable run,Runnable close,String draft){
        LinearLayout box=page(a,"补测指定的两张更多活动");
        TextView instructions=new TextView(a);
        instructions.setText("输入当天日期与两个不同的活动摘要，用英文冒号分隔。只尝试其中未完成、当天也未尝试的卡片。");
        box.addView(instructions);
        EditText field=new EditText(a);
        field.setSingleLine(true);
        field.setSaveEnabled(false);
        field.setShowSoftInputOnFocus(false);
        field.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setTag(PAIR_FIELD);
        field.setHint("YYYY-MM-DD:任务摘要1:任务摘要2");
        field.setText(draft);
        box.addView(field);
        Button confirm=new Button(a);
        confirm.setText(RUN_MORE_PAIR);
        confirm.setEnabled(ready);
        confirm.setOnClickListener(view->{
            LiveRewardsTaskSelection selection;
            try{
                String today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
                selection=LiveRewardsTaskSelection.morePair(field.getText().toString(),today);
            }catch(IllegalArgumentException invalid){
                field.setError("请填写当天日期和两个不同的 64 位小写十六进制任务摘要。");
                return;
            }
            confirm.setEnabled(false);
            visibleChoice.confirm(a,selection,run);
        });
        box.addView(confirm);
        button(a,box,"关闭",true,close);
    }
    static void singleTaskChoice(Activity a,boolean ready,Runnable run,Runnable close){
        LinearLayout box=page(a,"一次任务试点\n按签入、每日活动、更多活动的顺序，识别并点击第一个符合条件的未完成任务。今天已经尝试过的任务会跳过。\n"
                +"最多点击一次。签入后原地查看结果。活动卡点击成功后静置至少 5 秒，返回一次并查看结果。");
        button(a,box,RUN_SINGLE_TASK,ready,run);button(a,box,"关闭",true,close);
    }
    static void running(Activity a,boolean singleTask,Runnable close){
        LinearLayout box=page(a,singleTask?"一次任务试点正在运行。":"本轮真实流程正在运行。可以停止并关闭；停止不会重置已消费记录。");
        button(a,box,"停止并关闭",true,close);
    }
    static void readOnlyChoice(Activity a,boolean ready,Runnable run,Runnable close){
        if(a.getWindow().getDecorView().findViewWithTag(RECHECK_PAGE)!=null){
            moreRecheckChoice(a,ready,run,close);
            return;
        }
        LinearLayout box=page(a,"独立只读预检：签入 → 每日活动 → 更多活动\n"
                +"按区域滚动至完整卡片，核对界面结构、局部截图和安卓原生OCR结果。\n"
                +"本入口不能生成任务点击或返回资格，不启动真实任务流程。\n"
                +"来源不明确时停止；截图和无原文诊断只保存在本机。保留全部旧记录，不采集整屏。\n"
                +"最长三十分钟；每区最多八张活动卡。预检结果不代表任务完成或积分到账。");
        button(a,box,RUN_READ_ONLY,ready,()->recheckChoice.confirm(a,false,run));
        button(a,box,OPEN_MORE_RECHECK,ready,()->moreRecheckChoice(a,ready,run,close));
        button(a,box,"关闭",true,close);
    }
    private static void moreRecheckChoice(Activity a,boolean ready,Runnable run,Runnable close){
        LinearLayout box=page(a,"更多活动二次复查\n在当前 Rewards 页面只读取更多活动的界面结构。两次复查共用五秒，间隔至少一秒。"
                +"如果第二次读取剩余时间不足且资源已清理，将单独验证一次有完整时间的第二次读取。\n"
                +"全程不点击任务、不返回、不滑动，不采集截图。诊断仅保存在本机。");
        box.setTag(RECHECK_PAGE);
        button(a,box,RUN_MORE_RECHECK,ready,()->recheckChoice.confirm(a,true,run));
        button(a,box,"关闭",true,close);
    }
    static void readOnlyRunning(Activity a,Runnable close){
        if(recheckChoice.pending(a)){
            var box=page(a,"正在只读检查更多活动复查时的界面结构和资源释放。");
            button(a,box,"停止并关闭",true,close);
            return;
        }
        LinearLayout box=page(a,"正在进行独立只读预检。可能滚动并截取完整局部卡片，不点击任务、不按返回。\n可以停止并关闭；旧记录保持不变。");
        button(a,box,"停止并关闭",true,close);
    }
    static void status(Activity a,boolean singleTask,String summary,Runnable close){
        LinearLayout box=page(a,(singleTask?"一次任务试点已停止\n":"本轮真实流程已停止\n")+summary);
        button(a,box,"关闭",true,close);
    }
    static void readOnlyStatus(Activity a,String summary,Runnable close){
        if(summary!=null&&summary.contains(" uiRecheckStep=")){
            var box=page(a,"更多活动界面结构只读复查已停止\n"+summary);
            button(a,box,"关闭",true,close);
            return;
        }
        LinearLayout box=page(a,"独立只读预检已停止\n未执行真实任务点击或返回。\n"+summary);
        button(a,box,"关闭",true,close);
    }
    private LiveRewardsUi(){}
}
