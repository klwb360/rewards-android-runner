package com.aliothmoon.maafw.once;

import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Consume one already-dispatched helper. Cleanup evidence cannot authorize a late image. */
final class LiveCropStream {
    static final long CLEANUP_MS = 1000;
    static final long MIN_AFTER_START_MS = 600;

    record Window(long captureDeadline, long drainDeadline, boolean after) {
        boolean mayDispatch(long now) {
            return now >= 0 && now < captureDeadline && (!after || captureDeadline - now >= MIN_AFTER_START_MS);
        }

        AndroidCardRegionCapture.FixedStatus preDispatch(long now, boolean authorityReady, boolean owned,
                boolean permitCurrent, boolean leaseElapsed, boolean interrupted) {
            if (!authorityReady) return AndroidCardRegionCapture.FixedStatus.AUTHORITY_REJECTED;
            if (!owned || interrupted || !permitCurrent && !leaseElapsed) return AndroidCardRegionCapture.FixedStatus.CANCELLED;
            if (!mayDispatch(now)) return after ? AndroidCardRegionCapture.FixedStatus.INSUFFICIENT_BUDGET
                    : AndroidCardRegionCapture.FixedStatus.READ_TIMEOUT;
            return permitCurrent ? null : AndroidCardRegionCapture.FixedStatus.CANCELLED;
        }
    }

    static Window window(long started, long absoluteDeadline, boolean after) {
        long lastCapture = after ? Math.max(0, absoluteDeadline - CLEANUP_MS) : absoluteDeadline;
        long capture = Math.min(lastCapture, cappedAdd(started, CardRegionCaptureProtocol.MAX_HELPER_MS));
        return new Window(capture, Math.min(absoluteDeadline, cappedAdd(capture, CLEANUP_MS)), after);
    }

    private static long cappedAdd(long now, long delta) {
        return now > Long.MAX_VALUE - delta ? Long.MAX_VALUE : now + delta;
    }

    interface Input { int read(byte[] bytes,int offset,int length) throws Exception; }
    interface ProcessExit { boolean quiet() throws Exception; int exitValue() throws Exception; }
    interface Pause { void sleep(long millis) throws Exception; }

    enum Stage { PRE_DISPATCH, OPEN_STREAMS, FIRST_BYTE, WIRE_TAIL, PROCESS_EXIT, PARSE, DONE }
    Stage stage = Stage.PRE_DISPATCH, expiredAt;
    AndroidCardRegionCapture.FixedStatus status = AndroidCardRegionCapture.FixedStatus.IO_UNKNOWN;
    CardRegionCaptureProtocol.Status helperStatus;
    CardRegionCaptureProtocol.Cleanup helperCleanup;
    boolean eof, quiet, exitObserved, nativeInvoked;
    int count, exitCode, pngWidth, pngHeight;
    long helperStarted = -1, helperFinished = -1;
    byte[] png;

    boolean released(boolean descriptorsClosed) {
        return descriptorsClosed && eof && quiet && exitObserved && exitCode == 0
                && helperCleanup == CardRegionCaptureProtocol.Cleanup.CONFIRMED;
    }

    String diagnostic(boolean descriptorsClosed) {
        return "AT_" + (expiredAt == null ? stage : expiredAt) + "_END_" + stage + "_BYTES_" + count
                + "_EOF_" + (eof?1:0) + "_QUIET_" + (quiet?1:0) + "_EXIT_" + (exitObserved?1:0)
                + "_CODE_" + (!exitObserved?"NONE":exitCode==0?"ZERO":"NONZERO")
                + "_FD_" + (descriptorsClosed?1:0) + "_HELPER_" + (helperCleanup==null?"NONE":helperCleanup.name())
                + "_NATIVE_" + (helperStatus==null?"UNKNOWN":nativeInvoked?"YES":"NO");
    }

    void read(byte[] target, CardRegionCaptureProtocol.Request request, long dispatched, Window window,
              LongSupplier clock, BooleanSupplier current, Input input, ProcessExit process, Pause pause) throws Exception {
        if (window.captureDeadline() != request.deadline()) throw new IllegalArgumentException("Exact crop deadline required");
        AndroidCardRegionCapture.FixedStatus unusable = null;
        while (!eof || !quiet) {
            stage = eof ? Stage.PROCESS_EXIT : count == 0 ? Stage.FIRST_BYTE : Stage.WIRE_TAIL;
            long now = clock.getAsLong();
            if (!current.getAsBoolean()) {
                if (expiredAt == null) expiredAt = stage;
                unusable = AndroidCardRegionCapture.FixedStatus.CANCELLED;
            } else if (unusable == null && now >= window.captureDeadline()) {
                expiredAt = stage;
                unusable = AndroidCardRegionCapture.FixedStatus.READ_TIMEOUT;
            }
            if (now >= window.drainDeadline()) {
                status = unusable == null ? AndroidCardRegionCapture.FixedStatus.READ_TIMEOUT : unusable;
                return;
            }
            if (!eof) {
                int room = Math.min(65536, target.length-count);
                if (room <= 0) {status=AndroidCardRegionCapture.FixedStatus.BUFFER_LIMIT;return;}
                int n = input.read(target,count,room);
                if(n < -1 || n > room) throw new java.io.IOException("Invalid crop stream length");
                if(n == -1) eof = true;
                else count += n;
            }
            if (!quiet) quiet = process.quiet();
            if (eof && !quiet) pause.sleep(5);
        }
        exitCode = process.exitValue();
        exitObserved = true;
        if (exitCode != 0) {status = AndroidCardRegionCapture.FixedStatus.EXIT_NONZERO;return;}
        stage = Stage.PARSE;
        if (clock.getAsLong() >= window.drainDeadline()) {
            if (expiredAt == null) expiredAt = stage;
            status = unusable == null ? AndroidCardRegionCapture.FixedStatus.READ_TIMEOUT : unusable;
            return;
        }
        CardRegionCaptureProtocol.Report report;
        try { report = CardRegionCaptureProtocol.parse(target,count,request,dispatched,clock.getAsLong()); }
        catch (IllegalArgumentException | ArithmeticException invalid) {status=AndroidCardRegionCapture.FixedStatus.PROTOCOL_REJECTED;return;}
        helperStatus=report.status();helperCleanup=report.cleanup();nativeInvoked=report.nativeInvoked();
        helperStarted=report.started();helperFinished=report.finished();
        if (!current.getAsBoolean()) {
            if (expiredAt == null) expiredAt = stage;
            unusable = AndroidCardRegionCapture.FixedStatus.CANCELLED;
        } else if (unusable == null && clock.getAsLong() >= window.captureDeadline()) {
            expiredAt = stage;
            unusable = AndroidCardRegionCapture.FixedStatus.READ_TIMEOUT;
        }
        status = switch (helperStatus) {
            case CAPTURED -> unusable==null ? AndroidCardRegionCapture.FixedStatus.CAPTURED : unusable;
            case DEADLINE_EXCEEDED -> unusable==AndroidCardRegionCapture.FixedStatus.CANCELLED
                    ? unusable : AndroidCardRegionCapture.FixedStatus.CAPTURE_REJECTED;
            case PERMISSION_DENIED -> AndroidCardRegionCapture.FixedStatus.PERMISSION_DENIED;
            case API_UNAVAILABLE -> AndroidCardRegionCapture.FixedStatus.API_UNAVAILABLE;
            default -> AndroidCardRegionCapture.FixedStatus.CAPTURE_REJECTED;
        };
        if (status == AndroidCardRegionCapture.FixedStatus.CAPTURED) {
            png = report.png();pngWidth=report.pngWidth();pngHeight=report.pngHeight();
        } else if (report.png() != null) {
            Arrays.fill(report.png(), (byte)0);
        }
        stage = Stage.DONE;
    }
}
