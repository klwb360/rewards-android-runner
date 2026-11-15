package com.aliothmoon.maafw.once;

import android.app.Activity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Visible confirmation for one check-in panel reveal and direct-region screenshot test. */
final class CardRevealProbeUi {
    static final String OPEN = "打开完整卡片滚动截图测试（尚不采集）";
    static final String RUN = "确认本次局部截图测试";
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
        LinearLayout box = page(activity, "完整卡片滚动截图测试\n"
                + "本次只定位一个完整的签到面板，记录从开始滑动到确认完整露出并停稳的耗时。已经完整可见时不额外滑动。\n"
                + "最多滚动8次、观察界面24次，最长120秒；仅直接采集面板区域1张图片，完成后停止。\n"
                + "不进行整屏截图，不做OCR，不点击任务。图片和结果只保存在本机，保留全部旧记录。\n"
                + (ready ? "现有服务可用，确认后重新核对页面来源。" : "现有服务不可用，不能开始。"));
        button(activity, box, RUN, ready, run); button(activity, box, "关闭", true, close);
    }
    static void running(Activity activity, Runnable close) {
        LinearLayout box = page(activity, "正在定位完整签到面板并记录露出耗时；仅采集该区域一次。");
        button(activity, box, "停止并关闭", true, close);
    }
    static void status(Activity activity, String summary, Runnable close) {
        LinearLayout box = page(activity, "局部截图测试已停止\n没有点击任务或验证积分到账。\n" + summary);
        button(activity, box, "关闭", true, close);
    }
    private CardRevealProbeUi() { }
}
