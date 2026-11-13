package com.aliothmoon.maafw.once;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

/** Generated section crops, not user screenshots or a full-screen region detector.
 * Drawing coordinates are never supplied to OCR or the region association bridge.
 * The only state mutation is redrawing this compiled fixture. No input APIs exist here. */
final class MaterialSyntheticCanvas {
    private MaterialSyntheticCanvas() { }
    static final int WIDTH = 1104;
    static int height(SyntheticRewardsProvider.Fixture f) {
        return f == SyntheticRewardsProvider.Fixture.CHECKIN ? 402 :
                f == SyntheticRewardsProvider.Fixture.DAILY_CARDS ? 1180 : 1400;
    }
    private static final String[] CHECK = {"00000000000111", "00000000001110", "00000000011100",
            "10000000111000", "11100001110000", "01110011100000", "00111111000000",
            "00011110000000", "00001100000000"};
    private static final String[] CARD_CHECK = {"00000000011", "00000000111", "00000001110", "10000011100",
            "11000111000", "11101110000", "01111100000", "00111000000"};
    private static void color(Paint p, int c) { p.setColor(c); p.setStyle(Paint.Style.FILL); }
    private static void text(Canvas c, Paint p, String s, float x, float y, float size, int rgb) {
        color(p, rgb); p.setTextSize(size); c.drawText(s, x, y, p);
    }
    private static void disk(Canvas c, Paint p, float x, float y, float radius, int rgb) {
        color(p, rgb); c.drawCircle(x, y, radius, p);
    }
    private static void tick(Canvas c, Paint p, int x, int y, int radius, int diskColor, boolean card) {
        disk(c, p, x, y, radius, diskColor);
        // Literal synthetic pixel glyph. Recognition still measures the produced raster.
        p.setAntiAlias(false); color(p, Color.rgb(250, 250, 250));
        String[] shape = card ? CARD_CHECK : CHECK;
        int width = shape[0].length();
        for (int yy = 0; yy < shape.length; yy++) for (int xx = 0; xx < width; xx++)
            if (shape[yy].charAt(xx) == '1') c.drawRect(x - width + xx * 2, y - 8 + yy * 2,
                    x - width + 2 + xx * 2, y - 6 + yy * 2, p);
        p.setAntiAlias(true);
    }
    static void draw(Canvas c, SyntheticRewardsProvider.Fixture fixture, int selectedAfter) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        c.drawColor(Color.rgb(235, 235, 235));
        if (fixture == SyntheticRewardsProvider.Fixture.CHECKIN) {
            color(p, Color.rgb(250, 250, 250)); c.drawRect(20, 34, 1072, 386, p);
            text(c, p, "签入", 60, 126, 46, Color.BLACK);
            text(c, p, "5小时59分", 780, 126, 34, Color.BLACK);
            for (int i = 0; i < 7; i++) {
                int x = 124 + 140 * i;
                if (i < 3 || i == 3 && selectedAfter == 4) tick(c, p, x, 230, 32, Color.rgb(200, 200, 200), false);
                else if (i == 3) {
                    disk(c, p, x, 230, 54, Color.rgb(240, 176, 104));
                    disk(c, p, x, 230, 50, Color.rgb(250, 250, 250));
                    disk(c, p, x, 230, 34, Color.rgb(255, 232, 155));
                    text(c, p, "10", x - 22, 244, 38, Color.rgb(70, 70, 70));
                } else {
                    disk(c, p, x, 230, 50, Color.rgb(246, 246, 246));
                    disk(c, p, x, 230, 47, Color.rgb(250, 250, 250));
                }
                text(c, p, "Day " + (i + 1), x - 43, 342, 32, Color.BLACK);
            }
        } else {
            boolean daily = fixture == SyntheticRewardsProvider.Fixture.DAILY_CARDS;
            int count = daily ? 3 : 4, value = daily ? 10 : 5;
            int current = (daily ? 45 : 0) + (selectedAfter > 0 ? value : 0);
            text(c, p, daily ? "今日积分" : "更多活动", 40, 88, 44, Color.BLACK);
            text(c, p, current + "/" + (daily ? 120 : count * value), 850, 88, 42, Color.BLACK);
            if (daily) text(c, p, "每日活动", 40, 178, 44, Color.BLACK);
            for (int i = 0; i < count; i++) {
                int top = (daily ? 250 : 150) + 300 * i;
                color(p, Color.WHITE); c.drawRoundRect(24, top, 1080, top + 250, 24, 24, p);
                text(c, p, "Activity " + (i + 1), 65, top + 80, 40, Color.BLACK);
                text(c, p, "Synthetic task", 65, top + 165, 32, Color.rgb(80, 80, 80));
                if (daily && i == 0 || selectedAfter == i + 1) {
                    text(c, p, Integer.toString(value), 860, top + 144, 40, Color.rgb(65, 65, 65));
                    tick(c, p, 995, top + 125, 30, Color.rgb(15, 115, 170), true);
                } else {
                    color(p, Color.rgb(15, 115, 170));
                    c.drawRoundRect(850, top + 75, 1050, top + 175, 50, 50, p);
                    text(c, p, "+" + value, 891, top + 143, 49, Color.WHITE);
                }
            }
        }
    }
}
