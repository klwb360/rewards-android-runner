package com.aliothmoon.maafw.once;

/** Fixed scalar-only wire format. No arbitrary keys, strings, or tree data are accepted. */
final class NativeUiStructureProtocol {
    static final int MAX_LINE = 4096;
    static final int MAX_LINE_FIELD = 16384;
    enum Reason { OK, NOT_ROOT, INVALID_ARGUMENT, REFLECTION_UNAVAILABLE, REFLECTION_DENIED,
        CONNECT_FAILED, NO_ROOT, FOREIGN_ROOT, FOREIGN_NODE, TREE_OVERFLOW, TIMEOUT,
        WINDOW_UNAVAILABLE, WINDOW_CONFIG_UNAVAILABLE, TREE_FAILED, CLEANUP_UNKNOWN, IDLE_TIMEOUT, IDLE_FAILED, UI_BLOCKER }
    enum Cleanup { CONFIRMED, UNKNOWN, NOT_STARTED }
    enum Status { UNKNOWN, KNOWN, AMBIGUOUS, INVALID }
    enum Unit { EXPLICIT_TEXT_POINTS, UNESTABLISHED }
    record Task(Status status, int current, int target, int matchCount, Unit unit) {
        Task {
            if (status == null || unit == null || matchCount < 0 || matchCount > 1024
                    || (status == Status.KNOWN ? current < 0 || target <= 0 || current > target
                    || target > 10000 || matchCount != 1 || unit != Unit.EXPLICIT_TEXT_POINTS : current != -1 || target != -1)
                    || (status != Status.KNOWN && unit != Unit.UNESTABLISHED)) throw invalid();
        }
        static Task unknown() { return new Task(Status.UNKNOWN, -1, -1, 0, Unit.UNESTABLISHED); }
    }
    record Report(String nonce, long started, long acquired, long finished, Reason reason,
                  Cleanup cleanup, int nodeCount, int charCount, int windowId, int displayId,
                  int left, int top, int right, int bottom, int rotationBefore, int rotationAfter,
                  Task search, Task reading, NativeUiReadyDiagnostics.Result readiness,
                  NativeUiFieldDiagnostics.Result fieldDiagnostics, boolean structureBound,
                  NativeUiRegions.Pair regions) {
        Report(String nonce,long started,long acquired,long finished,Reason reason,Cleanup cleanup,
               int nodeCount,int charCount,int windowId,int displayId,int left,int top,int right,int bottom,
               int rotationBefore,int rotationAfter,Task search,Task reading,NativeUiReadyDiagnostics.Result readiness,
               NativeUiFieldDiagnostics.Result fieldDiagnostics,boolean structureBound) {
            this(nonce,started,acquired,finished,reason,cleanup,nodeCount,charCount,windowId,displayId,
                 left,top,right,bottom,rotationBefore,rotationAfter,search,reading,readiness,fieldDiagnostics,structureBound,null);
        }
        Report(String nonce,long started,long acquired,long finished,Reason reason,Cleanup cleanup,
               int nodeCount,int charCount,int windowId,int displayId,int left,int top,int right,int bottom,
               int rotationBefore,int rotationAfter,Task search,Task reading,NativeUiReadyDiagnostics.Result readiness,
               NativeUiFieldDiagnostics.Result fieldDiagnostics) {
            this(nonce,started,acquired,finished,reason,cleanup,nodeCount,charCount,windowId,displayId,
                 left,top,right,bottom,rotationBefore,rotationAfter,search,reading,readiness,fieldDiagnostics,false);
        }
        Report(String nonce,long started,long acquired,long finished,Reason reason,Cleanup cleanup,
               int nodeCount,int charCount,int windowId,int displayId,int left,int top,int right,int bottom,
               int rotationBefore,int rotationAfter,Task search,Task reading,NativeUiReadyDiagnostics.Result readiness) {
            this(nonce,started,acquired,finished,reason,cleanup,nodeCount,charCount,windowId,displayId,
                 left,top,right,bottom,rotationBefore,rotationAfter,search,reading,readiness,null);
        }
        Report(String nonce,long started,long acquired,long finished,Reason reason,Cleanup cleanup,
               int nodeCount,int charCount,int windowId,int displayId,int left,int top,int right,int bottom,
               int rotationBefore,int rotationAfter,Task search,Task reading) {
            this(nonce,started,acquired,finished,reason,cleanup,nodeCount,charCount,windowId,displayId,
                 left,top,right,bottom,rotationBefore,rotationAfter,search,reading,null);
        }
        Report {
            if (!validNonce(nonce) || started < 0 || acquired < started || finished < acquired
                    || finished - started > 10000 || reason == null || cleanup == null
                    || nodeCount < 0 || nodeCount > 512 || charCount < 0 || charCount > 32768
                    || windowId < -1 || displayId < -1 || displayId > 64
                    || left < -4096 || top < -4096 || right > 8192 || bottom > 8192
                    || right < left || bottom < top || rotationBefore < -1 || rotationBefore > 3
                    || rotationAfter < -1 || rotationAfter > 3 || search == null || reading == null)
                throw invalid();
            validateBoundProjection(structureBound, readiness, fieldDiagnostics);
            if (fieldDiagnostics != null && (readiness == null || !search.equals(Task.unknown()) || !reading.equals(Task.unknown()))) throw invalid();
            if (reason == Reason.OK && fieldDiagnostics != null && fieldDiagnostics.totalNodes() != nodeCount) throw invalid();
            if (reason == Reason.OK && (cleanup != Cleanup.CONFIRMED || nodeCount == 0
                    || windowId < 0 || displayId != 0 || right <= left || bottom <= top
                    || acquired - started > (readiness == null ? 5000 : 8000))) throw invalid();
            if (reason == Reason.OK && readiness != null
                    && readiness.idle() != NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED) throw invalid();
            if (readiness != null && (readiness.signIn() > 0 || readiness.error() > 0)
                    && (search.status() != Status.UNKNOWN || reading.status() != Status.UNKNOWN)) throw invalid();
            if ((reason != Reason.OK || cleanup != Cleanup.CONFIRMED)
                    && (search.status() != Status.UNKNOWN || reading.status() != Status.UNKNOWN))
                throw invalid();
            validateRegionsProjection(regions, structureBound, readiness, fieldDiagnostics,
                    reason, cleanup, left, top, right, bottom, search, reading);
        }
        boolean qualified() { return reason == Reason.OK && cleanup == Cleanup.CONFIRMED; }
    }
    // Explicit method parameters avoid lint resolving a compact-record component as a synthetic accessor.
    private static void validateBoundProjection(boolean structureBound,
            NativeUiReadyDiagnostics.Result readiness, NativeUiFieldDiagnostics.Result fieldDiagnostics) {
        if (structureBound && (readiness == null || fieldDiagnostics != null)) throw invalid();
    }
    private static void validateRegionsProjection(NativeUiRegions.Pair regions, boolean structureBound,
            NativeUiReadyDiagnostics.Result readiness, NativeUiFieldDiagnostics.Result fieldDiagnostics,
            Reason reason, Cleanup cleanup, int left, int top, int right, int bottom,
            Task search, Task reading) {
        if (regions == null) return;
        if (!structureBound || readiness == null || fieldDiagnostics != null) throw invalid();
        if ((reason != Reason.OK || cleanup != Cleanup.CONFIRMED || readiness.blocked())
                && (regions.search() != null || regions.reading() != null)) throw invalid();
        validateRegion(regions.search(), NativeUiRegions.Role.SEARCH, search, left, top, right, bottom);
        validateRegion(regions.reading(), NativeUiRegions.Role.READING, reading, left, top, right, bottom);
    }
    private static void validateRegion(NativeUiRegions.Region region, NativeUiRegions.Role role,
            Task task, int left, int top, int right, int bottom) {
        if (region == null) {
            if (task.status() == Status.KNOWN) throw invalid();
            return;
        }
        var card = region.card();
        if (region.role() != role || card == null || card.left() < left || card.top() < top
                || card.right() > right || card.bottom() > bottom
                || (region.summary() != null) != (task.status() == Status.KNOWN)
                || region.summary() != null && !card.contains(region.summary())) throw invalid();
    }
    static boolean validNonce(String s) { return s != null && s.matches("[0-9a-f]{32}"); }
    static String encode(Report r) {
        return (r.regions != null ? "UIS5|" : r.structureBound ? "UIS4|" : r.fieldDiagnostics != null ? "UIS3|" : r.readiness == null ? "UIS1|" : "UIS2|") + r.nonce + "|" + r.started + "|" + r.acquired + "|" + r.finished
                + "|" + r.reason + "|" + r.cleanup + "|" + r.nodeCount + "|" + r.charCount
                + "|" + r.windowId + "|" + r.displayId + "|" + r.left + "|" + r.top + "|"
                + r.right + "|" + r.bottom + "|" + r.rotationBefore + "|" + r.rotationAfter
                + task(r.search) + task(r.reading) + ready(r.readiness) + (r.fieldDiagnostics == null ? "" : "|"+NativeUiFieldDiagnostics.encode(r.fieldDiagnostics))
                + regions(r.regions);
    }
    private static String regions(NativeUiRegions.Pair regions) {
        return regions == null ? "" : region(NativeUiRegions.Role.SEARCH, regions.search())
                + region(NativeUiRegions.Role.READING, regions.reading());
    }
    private static String region(NativeUiRegions.Role role, NativeUiRegions.Region region) {
        return "|" + role + "|" + (region == null ? "UNKNOWN" : region.summary() == null ? "CARD" : "SUMMARY")
                + rect(region == null ? null : region.card()) + rect(region == null ? null : region.summary());
    }
    private static String rect(NativeUiRegions.Rect rect) {
        return rect == null ? "|-1|-1|-1|-1" : "|" + rect.left() + "|" + rect.top() + "|" + rect.right() + "|" + rect.bottom();
    }
    private static String ready(NativeUiReadyDiagnostics.Result r) {
        return r == null ? "" : "|"+r.idle()+"|"+r.webViews()+"|"+r.loading()+"|"+r.signIn()+"|"+r.error()+"|"+r.labelMask()+"|"+r.searchTitles()+"|"+r.readingTitles()+"|"+r.fractionShapes();
    }
    private static String task(Task t) {
        return "|" + t.status + "|" + t.current + "|" + t.target + "|" + t.matchCount + "|" + t.unit;
    }
    /** Input is one line WITHOUT its newline. Caller owns byte cap and EOF/exit checks. */
    static Report parse(String line, String nonce, long dispatched, long deadline, long now) {
        return parse(line,nonce,dispatched,deadline,now,false);
    }
    static Report parse(String line,String nonce,long dispatched,long deadline,long now,boolean expectedReady) {
        return parse(line,nonce,dispatched,deadline,now,expectedReady,false);
    }
    static Report parse(String line,String nonce,long dispatched,long deadline,long now,boolean expectedReady,boolean expectedField) {
        return parse(line,nonce,dispatched,deadline,now,expectedReady,expectedField,false);
    }
    static Report parse(String line,String nonce,long dispatched,long deadline,long now,boolean expectedReady,boolean expectedField,boolean expectedBound) {
        return parse(line,nonce,dispatched,deadline,now,expectedReady,expectedField,expectedBound,false);
    }
    static Report parse(String line,String nonce,long dispatched,long deadline,long now,boolean expectedReady,boolean expectedField,boolean expectedBound,boolean expectedRegions) {
        if (expectedRegions && (!expectedBound || !expectedReady || expectedField)) throw invalid();
        if (expectedBound && (!expectedReady || expectedField)) throw invalid();
        if (expectedField && !expectedReady) throw invalid();
        if (line == null || line.length() > (expectedField ? MAX_LINE_FIELD : MAX_LINE) || !validNonce(nonce) || dispatched < 0
                || deadline <= dispatched || deadline - dispatched > 10000 || now < dispatched
                || now >= deadline || !line.matches(expectedField ? "[A-Z0-9a-f_|,;-]+" : "[A-Z0-9a-f_|-]+")) throw invalid();
        String[] p = line.split("\\|", -1);
        if (p.length != (expectedRegions ? 56 : expectedField ? 37 : expectedReady ? 36 : 27)
                || !p[0].equals(expectedRegions ? "UIS5" : expectedBound ? "UIS4" : expectedField ? "UIS3" : expectedReady ? "UIS2" : "UIS1")
                || !p[1].equals(nonce)) throw invalid();
        try {
            Report r = new Report(p[1], number(p[2]), number(p[3]), number(p[4]),
                    Reason.valueOf(p[5]), Cleanup.valueOf(p[6]), integer(p[7]), integer(p[8]),
                    integer(p[9]), integer(p[10]), integer(p[11]), integer(p[12]), integer(p[13]),
                    integer(p[14]), integer(p[15]), integer(p[16]), task(p,17), task(p,22), expectedReady ? new NativeUiReadyDiagnostics.Result(
                            NativeUiReadyDiagnostics.Idle.valueOf(p[27]),integer(p[28]),integer(p[29]),
                            integer(p[30]),integer(p[31]),integer(p[32]),integer(p[33]),integer(p[34]),integer(p[35])) : null,
                    expectedField ? NativeUiFieldDiagnostics.parse(p[36]) : null, expectedBound,
                    expectedRegions ? new NativeUiRegions.Pair(region(p,36,NativeUiRegions.Role.SEARCH),region(p,46,NativeUiRegions.Role.READING)) : null);
            if (r.started < dispatched || r.finished > now || r.finished >= deadline) throw invalid();
            return r;
        } catch (IllegalArgumentException | ArithmeticException e) { throw invalid(); }
    }
    private static Task task(String[] p, int i) {
        return new Task(Status.valueOf(p[i]), integer(p[i+1]), integer(p[i+2]), integer(p[i+3]), Unit.valueOf(p[i+4]));
    }
    private static NativeUiRegions.Region region(String[] fields, int at, NativeUiRegions.Role role) {
        if (!fields[at].equals(role.name())) throw invalid();
        String state = fields[at+1];
        if (state.equals("UNKNOWN")) {
            for (int i=at+2;i<at+10;i++) if (!fields[i].equals("-1")) throw invalid();
            return null;
        }
        if (!state.equals("CARD") && !state.equals("SUMMARY")) throw invalid();
        var card = rect(fields,at+2);
        if (state.equals("CARD")) {
            for (int i=at+6;i<at+10;i++) if (!fields[i].equals("-1")) throw invalid();
            return new NativeUiRegions.Region(role,card,null);
        }
        return new NativeUiRegions.Region(role,card,rect(fields,at+6));
    }
    private static NativeUiRegions.Rect rect(String[] fields, int at) {
        return new NativeUiRegions.Rect(integer(fields[at]),integer(fields[at+1]),integer(fields[at+2]),integer(fields[at+3]));
    }
    static long number(String s) {
        if (s == null || !s.matches("0|[1-9][0-9]{0,18}|-[1-9][0-9]{0,9}")) throw invalid();
        return Long.parseLong(s);
    }
    private static int integer(String s) { return Math.toIntExact(number(s)); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid scalar protocol"); }
    private NativeUiStructureProtocol() { }
}
