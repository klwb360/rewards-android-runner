package com.aliothmoon.maafw.once;

import android.app.Activity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Visible confirmation for one check-in panel reveal and direct-region screenshot test. */
final class CardRevealProbeUi {
    static final String OPEN = "打开滚动截图原生OCR串联测试（尚不采集）";
    static final String RUN = "确认本次滚动截图模拟标点";
    private static LinearLayout page(Activity activity, String text) {
        ScrollView scroll = new ScrollView(activity); LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL); box.setPadding(24,24,24,24);
        TextView label = new TextView(activity); label.setText(text); label.setTextIsSelectable(true);
        box.addView(label); scroll.addView(box); activity.setContentView(scroll); return box;
    }
    private static void button(Activity activity, LinearLayout box, String text, boolean enabled, Runnable action) {
        Button button = new Button(activity); button.setText(text); button.setEnabled(enabled);
        button.setOnClickListener(view -> { if (button.isEnabled()) { button.setEnabled(false); action.run(); } });
        box.addView(button);
    }
    static void choice(Activity activity, boolean ready, Runnable run, Runnable close) {
        if(activity instanceof DormantCaptureConfirmationActivity d&&d.remainingCardSelection()!=null){RemainingCardsUi.confirm(activity,d.remainingCardSelection(),ready,run,close);return;}
        LinearLayout box = page(activity, "滚动、局部截图和原生OCR串联测试\n"
                + "本次从签到面板未完整露出的状态开始，程序自行滚动并记录到确认完整露出并停稳的耗时。起点已完整或没有实际滚动时停止，不采集。\n"
                + "本轮仅验证已核对的1440×2560竖屏布局。最多滚动8次、观察界面24次，最长120秒；仅直接采集完整面板区域1张图片，随后在安卓中对这同一次采集的局部图进行九个小区域OCR。\n"
                + "不进行整屏截图，不点击任务。来源和完整边框核对通过后，仅标记一个拟点击点；识别不确定时停止。图片及不含原文的诊断只保存在本机，保留全部旧记录。\n"
                + (ready ? "现有服务可用，确认后重新核对页面来源。" : "现有服务不可用，不能开始。"));
        button(activity, box, RUN, ready, run); button(activity, box, "关闭", true, close);
    }
    static void running(Activity activity, Runnable close) {
        if(activity instanceof DormantCaptureConfirmationActivity d&&d.remainingCardSelection()!=null){RemainingCardsUi.running(activity,d.remainingCardSelection(),close);return;}
        LinearLayout box = page(activity, "正在自主滚动到完整签到面板并计时；仅采集该区域一次，然后由APK内的原生OCR识别并模拟标点。");
        button(activity, box, "停止并关闭", true, close);
    }
    static void status(Activity activity, String summary, Runnable close) {
        if(activity instanceof DormantCaptureConfirmationActivity d&&d.remainingCardSelection()!=null){RemainingCardsUi.status(activity,d.remainingCardSelection(),summary,close);return;}
        LinearLayout box = page(activity, "滚动截图原生OCR测试已停止\n没有点击任务或验证积分到账。\n" + summary);
        button(activity, box, "关闭", true, close);
    }
    private CardRevealProbeUi() { }
}
