package com.aliothmoon.maafw.rewards;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline region-counter gate and hypothetical flow. No pixels, OCR engine, Android, input,
 * device, network, capture authority, live account state or persistence. A caller must separately
 * establish that exactly one reading belongs to the observed region's right-hand counter.
 * Labels below describe supplied evidence; they do not authenticate its producer.
 * Static screenshots never establish clickability, task completion, account credit or causality.
 */
public final class RewardRegionGate {
    private RewardRegionGate() {}
    public enum Source { USER_STATIC_MATERIAL, SYNTHETIC_FIXTURE, REAL_DISPLAY_NATIVE }
    public enum CounterRole { REGION_PROGRESS, AGGREGATE_PROGRESS, COUNTDOWN, STREAK, UNKNOWN }
    public enum TextBasis { CONTEXTUAL_OCR, INDEPENDENTLY_CROSSCHECKED, NUMERIC_ONLY_UNCONFIRMED }
    public enum Gate { COMPLETE_SKIP, INCOMPLETE_CANDIDATE_FLOW, UNKNOWN_STOP }
    public enum Reason {
        EQUAL_POSITIVE_DENOMINATOR, VALID_INCOMPLETE, NO_UNIQUE_COUNTER, SOURCE_SCOPE_MISMATCH,
        INCOMPLETE_REGION, AMBIGUOUS_COUNTER_OWNERSHIP, NON_PROGRESS_ROLE, UNCONFIRMED_NUMERIC_OCR, LOW_OR_INVALID_CONFIDENCE,
        MALFORMED_FRACTION, ZERO_DENOMINATOR, NUMERATOR_EXCEEDS_DENOMINATOR
    }
    public enum Step { WOULD_IDENTIFY_REGION_POINTS, WOULD_PROPOSE_UNIQUE_TARGET,
        REQUIRE_NEW_SAME_REGION_COUNTER, STOP_WITHOUT_RETRY }
    public enum PreviewState { PREVIEW_ONLY, BLOCKED, CANCELED, ALREADY_PREVIEWED, STALE }
    public enum Comparison { UNKNOWN_STOP, SAME_COUNTER_STOP, COUNTER_INCREASE_UNATTRIBUTED,
        COUNTER_COMPLETE_UNATTRIBUTED, CONFLICT_STOP }
    public record Key(Source source, String materialId, long observation, String regionId) {
        public Key {
            Objects.requireNonNull(source);
            if (!id(materialId) || !id(regionId) || observation < 1)
                throw new IllegalArgumentException("bounded material, observation and region required");
        }
    }
    /** No locator assumptions: region completeness/ownership must come from a separate observer. */
    public record Reading(Key key, String text, double confidence,
                          boolean regionComplete, boolean ownershipUnambiguous,
                          CounterRole role, TextBasis textBasis) {}
    public record Fraction(int numerator, int denominator) {}
    public static final class Assessment {
        private final Key key; private final Gate gate; private final Reason reason; private final Fraction fraction;
        private final CounterRole role;
        private Assessment(Key key,Gate gate,Reason reason,Fraction fraction,CounterRole role) {
            this.key=key; this.gate=gate; this.reason=reason; this.fraction=fraction; this.role=role;
        }
        public Key key() { return key; }
        public Gate gate() { return gate; }
        public Reason reason() { return reason; }
        public Fraction fraction() { return fraction; }
        public CounterRole role() { return role; }
    }
    public record Difference(Comparison comparison, int observedDelta,
                             boolean accountCreditKnown, boolean causedByProposedAction) {}

    // Deliberately limited compatibility, not NFKC: circled/superscript numbers, signs, units,
    // time strings, ratios embedded in a sentence, commas and zero-width characters are rejected.
    private static final Pattern FRACTION = Pattern.compile(
        "^([0-9]{1,4})[ \\u00a0\\u2009\\u202f]*/[ \\u00a0\\u2009\\u202f]*([0-9]{1,4})$");
    private static boolean id(String value) {
        return value != null && visibleCodePoints(value)>0 && value.length() <= 160;
    }
    static int visibleCodePoints(String value) {
        if(value==null)return 0;
        int count=0;
        for(int i=0;i<value.length();){
            int cp=value.codePointAt(i);i+=Character.charCount(cp);
            int type=Character.getType(cp);
            if(!Character.isWhitespace(cp)&&!Character.isSpaceChar(cp)&&type!=Character.CONTROL&&type!=Character.FORMAT)
                count++;
        }
        return count;
    }
    static String normalizeNumberText(String value) {
        if (value == null || value.length() > 48) return null;
        StringBuilder s = new StringBuilder();
        for (int i=0; i<value.length(); i++) {
            char ch=value.charAt(i);
            if (ch >= '\uff10' && ch <= '\uff19') ch=(char)('0'+ch-'\uff10');
            else if (ch=='\uff0f') ch='/';
            else if (ch=='\uff0b' || ch=='\ufe62') ch='+';
            s.append(ch);
        }
        return s.toString().replaceAll("^[ \\u00a0\\u2009\\u202f]+|[ \\u00a0\\u2009\\u202f]+$", "");
    }
    public static Assessment assess(Key expected, List<Reading> readings) {
        Objects.requireNonNull(expected);
        if (readings==null || readings.size()!=1 || readings.get(0)==null)
            return unknown(expected,Reason.NO_UNIQUE_COUNTER);
        Reading r=readings.get(0);
        if (!expected.equals(r.key())) return unknown(expected,Reason.SOURCE_SCOPE_MISMATCH);
        if (!r.regionComplete()) return unknown(expected,Reason.INCOMPLETE_REGION);
        if (!r.ownershipUnambiguous()) return unknown(expected,Reason.AMBIGUOUS_COUNTER_OWNERSHIP);
        if (!Double.isFinite(r.confidence()) || r.confidence()<0.8 || r.confidence()>1)
            return unknown(expected,Reason.LOW_OR_INVALID_CONFIDENCE);
        if (r.role()!=CounterRole.REGION_PROGRESS && r.role()!=CounterRole.AGGREGATE_PROGRESS)
            return unknown(expected,Reason.NON_PROGRESS_ROLE);
        if (r.textBasis()!=TextBasis.CONTEXTUAL_OCR && r.textBasis()!=TextBasis.INDEPENDENTLY_CROSSCHECKED)
            return unknown(expected,Reason.UNCONFIRMED_NUMERIC_OCR);
        String normalized=normalizeNumberText(r.text());
        Matcher m=normalized==null?null:FRACTION.matcher(normalized);
        if (m==null || !m.matches()) return unknown(expected,Reason.MALFORMED_FRACTION);
        int current=Integer.parseInt(m.group(1)), total=Integer.parseInt(m.group(2));
        if (total==0) return unknown(expected,Reason.ZERO_DENOMINATOR);
        if (current>total) return unknown(expected,Reason.NUMERATOR_EXCEEDS_DENOMINATOR);
        return new Assessment(expected,current==total?Gate.COMPLETE_SKIP:Gate.INCOMPLETE_CANDIDATE_FLOW,
            current==total?Reason.EQUAL_POSITIVE_DENOMINATOR:Reason.VALID_INCOMPLETE,
            new Fraction(current,total),r.role());
    }
    private static Assessment unknown(Key key, Reason reason) {
        return new Assessment(key,Gate.UNKNOWN_STOP,reason,null,CounterRole.UNKNOWN);
    }
    /** Returned only as a data comparison. Never marks credited, claimed, or action-caused.
     * Sequence numbers are caller-supplied ordering within one material set, not runtime proof.
     */
    public static Difference compare(Assessment before, Assessment after) {
        if (before==null || after==null || before.fraction()==null || after.fraction()==null
                || before.gate()!=Gate.INCOMPLETE_CANDIDATE_FLOW || after.gate()==Gate.UNKNOWN_STOP)
            return difference(Comparison.UNKNOWN_STOP,0);
        Key a=before.key(), b=after.key();
        if (a==null || b==null || a.source()!=b.source() || !a.materialId().equals(b.materialId())
                || !a.regionId().equals(b.regionId()) || before.role()!=after.role() || b.observation()<=a.observation()
                || after.fraction().denominator()!=before.fraction().denominator()
                || after.fraction().numerator()<before.fraction().numerator())
            return difference(Comparison.CONFLICT_STOP,0);
        int delta=after.fraction().numerator()-before.fraction().numerator();
        return difference(delta==0?Comparison.SAME_COUNTER_STOP:
            after.gate()==Gate.COMPLETE_SKIP?Comparison.COUNTER_COMPLETE_UNATTRIBUTED:
            Comparison.COUNTER_INCREASE_UNATTRIBUTED,delta);
    }
    private static Difference difference(Comparison c,int delta) {
        return new Difference(c,delta,false,false);
    }
    private static List<Reading> snapshot(List<Reading> values) {
        return values==null?null:Collections.unmodifiableList(new ArrayList<>(values));
    }
    /** Private construction and equality replay bind a proposal to its exact offline input. */
    public static final class Plan {
        private final Key key;
        private final List<Reading> readings;
        private final Assessment assessment;
        private Plan(Key key,List<Reading> readings,Assessment assessment) {
            this.key=key; this.readings=snapshot(readings); this.assessment=assessment;
        }
        public Key key() { return key; }
        public Assessment assessment() { return assessment; }
        public List<Step> steps() {
            return List.of(Step.WOULD_IDENTIFY_REGION_POINTS,Step.WOULD_PROPOSE_UNIQUE_TARGET,
                Step.REQUIRE_NEW_SAME_REGION_COUNTER,Step.STOP_WITHOUT_RETRY);
        }
    }
    public static Plan prepare(Key key,List<Reading> readings) {
        Assessment assessment=assess(key,readings);
        return assessment.gate()==Gate.INCOMPLETE_CANDIDATE_FLOW?
            new Plan(key,readings,assessment):null;
    }
    public record Preview(PreviewState state,List<Step> wouldDo,
                          String actor, int executedActions, boolean claimed,
                          boolean credited, boolean accountProgressKnown) {
        public Preview { wouldDo=List.copyOf(wouldDo); }
    }
    private static Preview preview(PreviewState state,List<Step> actions) {
        return new Preview(state,actions,"UNBOUND",0,false,false,false);
    }
    /** A single-use in-memory demonstration. No real click, no timer, no capture and no retry. */
    public static final class Session {
        private Plan plan;
        private boolean canceled, consumed, verified;
        public Session(Plan plan) { this.plan=plan; }
        public void cancel() { canceled=true; plan=null; }
        public Preview previewExplicitly(Key current,List<Reading> readings) {
            if (canceled) return preview(PreviewState.CANCELED,List.of());
            if (consumed) return preview(PreviewState.ALREADY_PREVIEWED,List.of());
            if (plan==null) return preview(PreviewState.BLOCKED,List.of());
            if (!plan.key.equals(current) || !Objects.equals(plan.readings,readings)) {
                plan=null;
                return preview(PreviewState.STALE,List.of());
            }
            consumed=true;
            return preview(PreviewState.PREVIEW_ONLY,plan.steps());
        }
        public Difference compareNewObservation(Key next,List<Reading> readings) {
            if (canceled || !consumed || verified || plan==null)
                return difference(Comparison.UNKNOWN_STOP,0);
            verified=true;
            if (next==null) return difference(Comparison.UNKNOWN_STOP,0);
            return compare(plan.assessment,assess(next,readings));
        }
    }
}
