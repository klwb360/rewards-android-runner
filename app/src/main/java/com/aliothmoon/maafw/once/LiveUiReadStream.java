package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** One live UI pipe. A child exit and a real end-of-stream are independent observations. */
final class LiveUiReadStream {
    static final long CLEANUP_MILLIS = 500;
    static final long IDLE_MILLIS = 3000;
    enum Stage { PRE_DISPATCH, NEW_PROCESS, OPEN_STREAMS, FIRST_BYTE, WIRE_TAIL, PROCESS_EXIT, PROTOCOL, DONE }
    enum Resource { NOT_OPENED, RELEASED, UNKNOWN }
    enum Mode { UI, AFTER, VISIT }

    static long workDeadline(long deadline) { return Math.max(0, deadline - CLEANUP_MILLIS); }

    static boolean enoughTime(long now, long deadline) {
        return now >= 0 && workDeadline(deadline) - now >= IDLE_MILLIS;
    }

    static NativeUiStructureProtocol.Reason cleanupReason(NativeUiStructureProtocol.Reason original,
            boolean clean, boolean blocked, boolean structureRejected) {
        if (original != NativeUiStructureProtocol.Reason.OK && original != NativeUiStructureProtocol.Reason.TIMEOUT)
            return original;
        if (blocked) return NativeUiStructureProtocol.Reason.UI_BLOCKER;
        if (!clean) return NativeUiStructureProtocol.Reason.CLEANUP_UNKNOWN;
        if (structureRejected) return NativeUiStructureProtocol.Reason.TREE_FAILED;
        return NativeUiStructureProtocol.Reason.TIMEOUT;
    }

    /** Fixed cleanup-only wire format. No tree, positions, titles, or actionable result. */
    record CleanupReceipt(String nonce, Mode mode, long deadline, long started, long finished,
            NativeUiStructureProtocol.Reason reason, Resource tree, Resource connection, Resource thread) {
        CleanupReceipt {
            if (!NativeUiStructureProtocol.validNonce(nonce) || mode==null || started < 0 || finished < started
                    || finished >= deadline || deadline - started > 10000 || reason == null
                    || reason == NativeUiStructureProtocol.Reason.OK || tree == null || connection == null || thread == null
                    || tree != Resource.NOT_OPENED && connection == Resource.NOT_OPENED
                    || connection != Resource.NOT_OPENED && thread == Resource.NOT_OPENED)
                throw new IllegalArgumentException("Invalid live cleanup receipt");
        }
        boolean released() {
            return reason != NativeUiStructureProtocol.Reason.CLEANUP_UNKNOWN
                    && tree != Resource.UNKNOWN && connection != Resource.UNKNOWN && thread != Resource.UNKNOWN;
        }
        String encode() {
            return String.join("|", "LUC1", nonce, mode.name(), Long.toString(deadline),
                    Long.toString(started), Long.toString(finished), reason.name(), tree.name(), connection.name(), thread.name());
        }
        static CleanupReceipt parse(String line, String nonce, Mode mode, long dispatched, long deadline, long now) {
            if (line == null || line.length() > 256) throw new IllegalArgumentException("Invalid cleanup frame");
            String[] fields = line.split("\\|", -1);
            if (fields.length != 10 || !"LUC1".equals(fields[0]) || !nonce.equals(fields[1])
                    || mode==null || !mode.name().equals(fields[2]) || dispatched < 0 || now < dispatched
                    || deadline<=dispatched || deadline-dispatched>10000 || now >= deadline)
                throw new IllegalArgumentException("Cleanup binding rejected");
            var receipt = new CleanupReceipt(fields[1], mode, NativeUiStructureProtocol.number(fields[3]),
                    NativeUiStructureProtocol.number(fields[4]), NativeUiStructureProtocol.number(fields[5]),
                    NativeUiStructureProtocol.Reason.valueOf(fields[6]), Resource.valueOf(fields[7]),
                    Resource.valueOf(fields[8]), Resource.valueOf(fields[9]));
            if (deadline != receipt.deadline || receipt.started < dispatched || receipt.finished > now
                    || !receipt.encode().equals(line)) throw new IllegalArgumentException("Stale cleanup frame");
            return receipt;
        }
    }

    Stage stage = Stage.PRE_DISPATCH;
    boolean eof, quiet, exitObserved, currentLost, completeReceipt;
    int count, exitCode = -1;
    CleanupReceipt cleanup;
    NativeUiStructureProtocol.Cleanup frameCleanup;
    NativeUiStructureProtocol.Reason helperReason;

    AndroidLiveUiReader.FixedStatus drain(byte[] bytes, long deadline, LongSupplier clock, BooleanSupplier current,
            LiveCropStream.Input input, LiveCropStream.ProcessExit process, LiveCropStream.Pause pause) throws Exception {
        while (!eof || !quiet) {
            stage = eof ? Stage.PROCESS_EXIT : count == 0 ? Stage.FIRST_BYTE : Stage.WIRE_TAIL;
            if (!current.getAsBoolean()) currentLost = true;
            if (clock.getAsLong() >= deadline) return AndroidLiveUiReader.FixedStatus.READ_TIMEOUT;
            if (!eof) {
                int room = bytes.length - count;
                if (room == 0) return AndroidLiveUiReader.FixedStatus.BUFFER_LIMIT;
                int n = input.read(bytes, count, room);
                if (n < -1 || n > room) throw new IOException("Invalid UI pipe read");
                if (n == -1) eof = true;
                else count += n;
            }
            if (!quiet) quiet = process.quiet();
            if (eof && !quiet) pause.sleep(5);
        }
        exitCode = process.exitValue();
        exitObserved = true;
        if (exitCode != 0) return AndroidLiveUiReader.FixedStatus.EXIT_NONZERO;
        stage = Stage.PROTOCOL;
        return clock.getAsLong() < deadline ? null : AndroidLiveUiReader.FixedStatus.READ_TIMEOUT;
    }

    boolean released(boolean descriptorsClosed) {
        boolean nativeReleased = cleanup != null ? cleanup.released()
                : completeReceipt && frameCleanup != null && frameCleanup != NativeUiStructureProtocol.Cleanup.UNKNOWN;
        return descriptorsClosed && eof && quiet && exitObserved && exitCode == 0 && nativeReleased;
    }

    String diagnostic(AndroidLiveUiReader.FixedStatus status, String out, String in, String err) {
        return "AT_" + stage + "_STATUS_" + status + "_BYTES_" + count + "_EOF_" + (eof ? 1 : 0)
                + "_QUIET_" + (quiet ? 1 : 0) + "_EXIT_" + (!exitObserved ? "NONE" : exitCode == 0 ? "ZERO" : "NONZERO")
                + "_OUT_" + out + "_IN_" + in + "_ERR_" + err
                + "_HELPER_" + (helperReason == null ? "NONE" : helperReason.name())
                + (cleanup == null ? "_FRAME_" + (frameCleanup == null ? "NONE" : frameCleanup.name())
                    : "_TREE_" + cleanup.tree() + "_CONNECTION_" + cleanup.connection() + "_THREAD_" + cleanup.thread());
    }
}
