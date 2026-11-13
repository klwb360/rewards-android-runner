package com.aliothmoon.maafw.once;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Surface;

/** Compiled app-generated fixtures only. No file, display, Intent text or account data. */
final class SyntheticRewardsProvider implements ScreenProducerProvider.Provider {
    enum Fixture { HOME, SIGN_IN, UNKNOWN, BLANK, CANCEL_WINDOW, CHECKIN, DAILY_CARDS, MORE_CARDS }
    private final Fixture fixture;
    private final int selectedAfter;
    SyntheticRewardsProvider(Fixture fixture) { this(fixture, 0); }
    SyntheticRewardsProvider(Fixture fixture, int selectedAfter) {
        this.fixture = java.util.Objects.requireNonNull(fixture);
        if (selectedAfter < 0 || selectedAfter > 7 || selectedAfter != 0 && fixture.ordinal() < 5)
            throw new IllegalArgumentException();
        this.selectedAfter = selectedAfter;
    }
    public ScreenProducerProvider.Origin origin() { return ScreenProducerProvider.Origin.SYNTHETIC; }
    public int width() { return fixture.ordinal() >= 5 ? MaterialSyntheticCanvas.WIDTH : 1024; }
    public int height() { return fixture.ordinal() >= 5 ? MaterialSyntheticCanvas.height(fixture) : 512; }
    public ControlledSourceSession.Driver open(ControlledSourceSession.Lease lease, Surface target, BackendPorts.StopSignal stop) {
        lease.requireOwnerThread();
        return new Producer(lease, target, fixture, selectedAfter);
    }
    private static final class Producer implements ControlledSourceSession.Driver {
        private Surface target;
        private final ControlledSourceSession.Lease ownerLease;
        private final Fixture fixture;
        private final int selectedAfter;
        private boolean produced, uncertain;
        Producer(ControlledSourceSession.Lease lease, Surface target, Fixture fixture, int selectedAfter) {
            this.ownerLease = lease; this.target = target; this.fixture = fixture; this.selectedAfter = selectedAfter;
        }
        public void produce(ControlledSourceSession.Lease lease, BackendPorts.StopSignal stop) throws Exception {
            lease.requireOwnerThread();
            if (lease != ownerLease) throw new IllegalStateException();
            if (produced || target == null) throw new IllegalStateException();
            produced = true;
            if (fixture == Fixture.CANCEL_WINDOW) {
                long end = System.nanoTime() + 3_500_000_000L;
                while (System.nanoTime() < end && !stop.requested()) Thread.sleep(20);
            }
            if (stop.requested()) throw new BackendPorts.Cancelled();
            Canvas canvas = null;
            try {
                uncertain = true;
                canvas = target.lockCanvas(null);
                canvas.drawColor(Color.WHITE);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(Color.BLACK); paint.setTextSize(60);
                switch (fixture) {
                    case HOME, CANCEL_WINDOW -> {
                        canvas.drawText("Microsoft Rewards", 48, 120, paint);
                        canvas.drawText("Daily set 1/3", 48, 260, paint);
                        canvas.drawText("Progress", 48, 400, paint);
                    }
                    case SIGN_IN -> {
                        canvas.drawText("Microsoft Rewards", 48, 140, paint);
                        canvas.drawText("Sign in", 48, 300, paint);
                    }
                    case UNKNOWN -> canvas.drawText("WEATHER FORECAST", 48, 240, paint);
                    case BLANK -> { }
                    case CHECKIN, DAILY_CARDS, MORE_CARDS -> MaterialSyntheticCanvas.draw(canvas, fixture, selectedAfter);
                }
            } finally {
                if (canvas != null) { target.unlockCanvasAndPost(canvas); uncertain = false; }
            }
        }
        public ControlledSourceSession.CloseReceipt close(ControlledSourceSession.Lease lease) {
            lease.requireOwnerThread();
            if (lease != ownerLease || uncertain) throw new IllegalStateException();
            target = null;
            return lease.synchronousAppCleanupReturned();
        }
    }
}
