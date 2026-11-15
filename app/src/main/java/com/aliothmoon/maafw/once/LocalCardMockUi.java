package com.aliothmoon.maafw.once;

import android.app.Activity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Visible choice for local Android OCR of the previously qualified card image only. */
final class LocalCardMockUi {
    static final String OPEN = "打开安卓原生局部OCR模拟（不点击任务）";
    static final String RUN = "确认本次原生OCR模拟标点";

    private static LinearLayout page(Activity activity, String text) {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL); box.setPadding(24,24,24,24);
        TextView label = new TextView(activity); label.setText(text); label.setTextIsSelectable(true);
        box.addView(label); scroll.addView(box); activity.setContentView(scroll); return box;
    }
    private static void button(Activity activity, LinearLayout box, String text,
            boolean enabled, Runnable action) {
        Button button = new Button(activity); button.setText(text); button.setEnabled(enabled);
        button.setOnClickListener(view -> {
            if (button.isEnabled()) { button.setEnabled(false); action.run(); }
        });
        box.addView(button);
    }
    static void choice(Activity activity, boolean ready, Runnable run, Runnable close) {
        LinearLayout box = page(activity, "安卓原生局部OCR模拟\n"
                + "读取上一轮已经保存并核对过的签到卡片局部图，由APK内的MaaFramework模型在安卓中识别。"
                + "这是历史图片的局部回放，不代表当前页面状态。\n"
                + "分别确认标题、圈内数字和七个日期小区域；核对通过后仅标出一个拟点击点。"
                + "识别不确定时停止，不猜测。\n"
                + "本次使用独立的一次性记录，保留全部旧记录。图片和结果只保存在本机。"
                + "不重新截图，不访问Bing，不点击真实任务，不调用Windows OCR。\n"
                + "测试期间请保持本页可见；离开本页会请求停止，仍需等待原生资源清理结果。");
        button(activity, box, RUN, ready, run);
        button(activity, box, "关闭", true, close);
    }
    static void running(Activity activity, Runnable close) {
        LinearLayout box = page(activity, "正在安卓中运行局部OCR并准备一次模拟标点。\n"
                + "只处理已保存的卡片图；请保持本页可见。没有点击任务。");
        button(activity, box, "停止并关闭", true, close);
    }
    static void status(Activity activity, String summary, Runnable close) {
        LinearLayout box = page(activity, "原生OCR模拟已停止\n"
                + "历史图片回放，不代表当前任务状态；没有真实点击或积分到账验证。\n" + summary);
        button(activity, box, "关闭", true, close);
    }
    private LocalCardMockUi() { }
}
