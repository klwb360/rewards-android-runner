package com.aliothmoon.maafw.rewards;

import io.github.klwb360.rewards.core.RewardsCore;
import io.github.klwb360.rewards.core.BingDailySetPage;
import java.util.*;

/** Semantic evidence only. No capture, coordinates, network, Android input or execution authority.
 * Provenance is a local consistency boundary, not authentication of an external producer.
 * Only explicit synthetic semantic nodes are admitted; real sources are deliberately unbound.
 */
public final class RewardsSemanticEvidence {
    private RewardsSemanticEvidence() { }
    public enum Source { SYNTHETIC_SEMANTIC, OCR_PAGE_ONLY, REAL_SCREEN_UNBOUND }
    public enum Confidence { SYNTHETIC_VALIDATED, MISSING, AMBIGUOUS, CONFLICT, REJECTED }
    public enum Attribution { NOT_AVAILABLE, SYNTHETIC_SELECTED_CHANGE, SYNTHETIC_NO_CHANGE, CONFLICT }
    public record Provenance(Source source, int ownerUid, long epoch, int attempt, int requestId, long frame) { }
    public record Input(Provenance provenance, List<RewardsCore.Node> nodes) {
        public Input { nodes = nodes == null ? null : Collections.unmodifiableList(new ArrayList<>(nodes)); }
    }
    /** Constructor is private: callers cannot promote a public legacy Result into accepted evidence. */
    public static final class Parsed {
        private final Provenance provenance;
        private final RewardsPlanning.Task task;
        private final Confidence confidence;
        private final String reason;
        private final RewardsCore.Observation counter;
        private final BingDailySetPage.Result cards;
        private Parsed(Provenance p, RewardsPlanning.Task t, Confidence c, String r,
                       RewardsCore.Observation o, BingDailySetPage.Result cards) {
            provenance=p; task=t; confidence=c; reason=r; counter=o; this.cards=cards;
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Parsed p)) return false;
            return Objects.equals(provenance,p.provenance) && task==p.task && confidence==p.confidence
                && reason.equals(p.reason) && Objects.equals(counter,p.counter) && Objects.equals(cards,p.cards);
        }
        @Override public int hashCode() { return Objects.hash(provenance,task,confidence,reason,counter,cards); }
        public Provenance provenance() { return provenance; }
        public RewardsPlanning.Task task() { return task; }
        public Confidence confidence() { return confidence; }
        public String reason() { return reason; }
        public RewardsCore.Observation counter() { return counter; }
        public String scope() { return cards == null ? "NONE" : cards.scopeId(); }
        public int cardCount() { return cards == null ? 0 : cards.cards().size(); }
        public boolean usableFor(RewardsPlanning.Evidence e, RewardsPlanning.Task t) {
            return confidence == Confidence.SYNTHETIC_VALIDATED && task == t && bound(provenance,e);
        }
    }
    public record Assessment(Parsed before, Parsed after, Attribution attribution, String explanation) { }
    static RewardsCore.Task coreTask(RewardsPlanning.Task t) {
        return switch(t) { case ACTIVITIES -> RewardsCore.Task.WEB; case SEARCH -> RewardsCore.Task.SEARCH;
            case READING -> RewardsCore.Task.READING; };
    }
    public static Provenance provenance(RewardsPlanning.Evidence e, long frame) {
        Objects.requireNonNull(e);
        return new Provenance(Source.SYNTHETIC_SEMANTIC,e.ownerUid(),e.epoch(),e.attempt(),e.requestId(),frame);
    }
    private static boolean bound(Provenance p, RewardsPlanning.Evidence e) {
        return p != null && e != null && e.page() == RewardsPlanning.Page.REWARDS_HOME
            && p.source() == Source.SYNTHETIC_SEMANTIC && p.ownerUid() == e.ownerUid() && p.ownerUid() >= 10000
            && p.epoch() == e.epoch() && p.attempt() == e.attempt() && p.requestId() == e.requestId() && p.frame() > 0;
    }
    public static Parsed parse(RewardsPlanning.Evidence e, RewardsPlanning.Task task, Input input) {
        Objects.requireNonNull(task);
        Provenance p = input == null ? null : input.provenance();
        if (!bound(p,e)) return fail(p,task,Confidence.REJECTED,"SOURCE_OWNER_GENERATION_OR_PAGE_REJECTED");
        String invalid = validateTree(input.nodes());
        if (invalid != null) return fail(p,task,Confidence.CONFLICT,invalid);
        // Invisible anchors must not establish card structure. Visible ancestor consistency was
        // checked above, so removing all hidden subtrees leaves a valid visible parent chain.
        List<RewardsCore.Node> visible=new ArrayList<>();
        for(RewardsCore.Node node:input.nodes()) if(node.visible()) visible.add(node);
        RewardsCore.Observation count = parseCounter(visible,task);
        BingDailySetPage.Result cards = null;
        if (task == RewardsPlanning.Task.ACTIVITIES) {
            cards = BingDailySetPage.parse(visible);
            if (cards.status() == RewardsCore.ParseStatus.INVALID || cards.status() == RewardsCore.ParseStatus.AMBIGUOUS)
                return fail(p,task,Confidence.CONFLICT,"CARDS: " + cards.observation().detail());
            if (cards.status() == RewardsCore.ParseStatus.OK) {
                // Chinese has two same-named sibling headings. The strict card parser resolves
                // the unique offer scope; only that scope can supply a corroborating counter.
                count = parseCounter(cardScope(visible,cards.scopeId()),task);
                if (count.status() == RewardsCore.ParseStatus.INVALID || count.status() == RewardsCore.ParseStatus.AMBIGUOUS)
                    return fail(p,task,Confidence.CONFLICT,"COUNTER_AND_CARDS_UNRESOLVED");
                if (count.status() == RewardsCore.ParseStatus.OK &&
                    (count.counter().current() != cards.observation().counter().current()
                     || count.counter().target() != cards.observation().counter().target()))
                    return fail(p,task,Confidence.CONFLICT,"COUNTER_CARD_CONFLICT");
                count = cards.observation();
            } else cards = null;
        }
        Confidence confidence = switch(count.status()) {
            case OK -> Confidence.SYNTHETIC_VALIDATED; case MISSING -> Confidence.MISSING;
            case AMBIGUOUS -> Confidence.AMBIGUOUS; case INVALID -> Confidence.CONFLICT;
        };
        return new Parsed(p,task,confidence,count.detail(),count,cards);
    }
    /** Legacy scope isolation alone does not distinguish a balance fraction within a task group.
     * Keep only exact counter-shaped text for its fraction recognizer; labels remain unchanged
     * for the independent card parser. Unfamiliar text supplies no task counter.
     */
    private static String counterText(String text) {
        String normalized=RewardsCore.normalize(text);
        if (!normalized.matches(".*[+-]?\\d+\\s*/\\s*[+-]?\\d+.*")) return text;
        String fraction="[+-]?\\d+\\s*/\\s*[+-]?\\d+";
        String heading="(?:网页每日活动|每日活动|daily set|搜索以赚取|search to earn|搜索积分|search points|阅读以赚取|read to earn) ";
        return normalized.matches("(?:"+heading+")?(?:(?:今日|今天|today) )?"+fraction) ? text : "";
    }
    private static boolean taskHeading(RewardsCore.Node n, RewardsPlanning.Task task) {
        String[] headings=switch(task) {
            case ACTIVITIES -> new String[]{"网页每日活动","每日活动","daily set"};
            case SEARCH -> new String[]{"搜索以赚取","search to earn","搜索积分","search points"};
            case READING -> new String[]{"阅读以赚取","read to earn"};
        };
        for(String value:List.of(RewardsCore.normalize(n.text()),RewardsCore.normalize(n.description())))
            for(String heading:headings) if(value.equals(heading) || value.matches(java.util.regex.Pattern.quote(heading)+" (?:(?:今日|今天|today) )?[+-]?\\d+\\s*/\\s*[+-]?\\d+")) return true;
        return false;
    }
    /** Never let the legacy parser climb out into sibling groups. This adapter supports the
     * heading container's direct children, or the leaf heading's immediate sibling group only.
     * Deeper/unfamiliar layouts are missing, not guesses. */
    private static RewardsCore.Observation parseCounter(List<RewardsCore.Node> nodes,RewardsPlanning.Task task) {
        List<RewardsCore.Node> headings=new ArrayList<>(); Map<String,RewardsCore.Node> ids=new HashMap<>();
        for(RewardsCore.Node n:nodes) { ids.put(n.id(),n); if(taskHeading(n,task))headings.add(n); }
        if(headings.size()!=1) return new RewardsCore.Observation(headings.isEmpty()?RewardsCore.ParseStatus.MISSING:RewardsCore.ParseStatus.AMBIGUOUS,null,"unique task heading required");
        RewardsCore.Node heading=headings.get(0),scope=heading;
        boolean hasChildren=false; for(RewardsCore.Node n:nodes) if(n.parentId().equals(heading.id()))hasChildren=true;
        if(!hasChildren && ids.containsKey(heading.parentId()))scope=ids.get(heading.parentId());
        String boundary="__semantic_boundary__"; while(ids.containsKey(boundary))boundary+="_";
        List<RewardsCore.Node> bounded=new ArrayList<>();
        bounded.add(new RewardsCore.Node(boundary,"","","",false,true,true));
        bounded.add(new RewardsCore.Node(scope.id(),boundary,scope.text(),scope.description(),scope.clickable(),scope.enabled(),true));
        for(RewardsCore.Node n:nodes) if(n.parentId().equals(scope.id()))bounded.add(n);
        return RewardsCore.parse(counterNodes(bounded),coreTask(task));
    }
    private static List<RewardsCore.Node> counterNodes(List<RewardsCore.Node> nodes) {
        List<RewardsCore.Node> result=new ArrayList<>();
        for(RewardsCore.Node n:nodes) result.add(new RewardsCore.Node(n.id(),n.parentId(),counterText(n.text()),
            counterText(n.description()),n.clickable(),n.enabled(),n.visible()));
        return result;
    }
    private static List<RewardsCore.Node> cardScope(List<RewardsCore.Node> nodes,String scope) {
        Map<String,RewardsCore.Node> map=new HashMap<>(); nodes.forEach(n -> map.put(n.id(),n));
        Set<String> ancestors=new HashSet<>();
        for(RewardsCore.Node a=map.get(scope);a!=null;a=map.get(a.parentId())) ancestors.add(a.id());
        List<RewardsCore.Node> result=new ArrayList<>();
        for(RewardsCore.Node n:nodes) {
            boolean include=ancestors.contains(n.id());
            for(RewardsCore.Node a=n;!include && a!=null;a=map.get(a.parentId())) include=a.id().equals(scope);
            if(include)result.add(n);
        }
        return result;
    }
    private static Parsed fail(Provenance p, RewardsPlanning.Task t, Confidence c, String why) {
        return new Parsed(p,t,c,why,new RewardsCore.Observation(RewardsCore.ParseStatus.INVALID,null,why),null);
    }
    private static String validateTree(List<RewardsCore.Node> nodes) {
        if (nodes == null || nodes.size() > 256) return "TREE_SIZE_OR_NULL";
        Map<String,RewardsCore.Node> ids = new HashMap<>();
        for (RewardsCore.Node n : nodes) {
            if (n == null || n.id() == null || n.id().isBlank() || n.id().length()>128 || n.parentId()==null
                || n.parentId().length()>128 || (n.text()!=null && n.text().length()>2048)
                || (n.description()!=null && n.description().length()>2048) || ids.putIfAbsent(n.id(),n)!=null)
                return "NODE_BOUNDS_OR_DUPLICATE_ID";
            if (n.text()!=null && !n.text().isBlank() && n.description()!=null && !n.description().isBlank()
                && !RewardsCore.normalize(n.text()).equals(RewardsCore.normalize(n.description())))
                return "TEXT_DESCRIPTION_CONFLICT";
        }
        for (RewardsCore.Node n : nodes) {
            Set<String> seen = new HashSet<>();
            for (RewardsCore.Node a=n; a!=null; a=ids.get(a.parentId())) {
                if (!seen.add(a.id()) || seen.size()>32) return "TREE_CYCLE_OR_DEPTH";
                if (!a.parentId().isEmpty() && !ids.containsKey(a.parentId())) return "MISSING_PARENT";
                if (n.visible() && !a.visible()) return "HIDDEN_ANCESTOR";
            }
        }
        return null;
    }
    /** Conservative extraction of BingDailyFlow.selectedOutcome. Never verifies/commits an action.
     * A newer semantic frame, stable owner/session and stable card IDs/points are additionally required.
     * This is a synthetic difference explanation, not proof that selection preceded after-frame
     * or caused a change. A future real path would need a before-bound selection receipt.
     */
    public static Assessment attribute(RewardsPlanning.Evidence current, Parsed before, Parsed after, String selectedId) {
        String why = "INVALID_OR_STALE_CARD_EVIDENCE";
        if (before == null || after == null || !before.usableFor(current,RewardsPlanning.Task.ACTIVITIES)
            || !after.usableFor(current,RewardsPlanning.Task.ACTIVITIES) || before.cards==null || after.cards==null)
            return new Assessment(before,after,Attribution.CONFLICT,why);
        if (after.provenance.frame() <= before.provenance.frame())
            return new Assessment(before,after,Attribution.CONFLICT,"NEWER_SEMANTIC_FRAME_REQUIRED");
        if (!before.scope().equals(after.scope())) return new Assessment(before,after,Attribution.CONFLICT,"SCOPE_CHANGED");
        Map<String,BingDailySetPage.Card> old = new HashMap<>(), next = new HashMap<>();
        before.cards.cards().forEach(c -> old.put(c.nodeId(),c)); after.cards.cards().forEach(c -> next.put(c.nodeId(),c));
        BingDailySetPage.Card selected = old.get(selectedId);
        if (!old.keySet().equals(next.keySet()) || selected==null || selected.completed()
            || BingDailySetPage.unambiguousActiveTitle(selected).isEmpty())
            return new Assessment(before,after,Attribution.CONFLICT,"CARD_SET_OR_SELECTION_CHANGED");
        for (String id : old.keySet()) {
            if (old.get(id).points()!=next.get(id).points() || (!id.equals(selectedId) && !old.get(id).equals(next.get(id))))
                return new Assessment(before,after,Attribution.CONFLICT,"OTHER_CARD_CHANGED_NOT_SELECTED");
        }
        if (BingDailySetPage.matchesSelectedCompletion(selected,before.cards,after.cards))
            return new Assessment(before,after,Attribution.SYNTHETIC_SELECTED_CHANGE,"SAMPLE_SELECTED_CARD_CHANGED_ONLY; NOT_EXECUTION_OR_ACCOUNT_CREDIT");
        if (old.equals(next) && before.counter.counter().equals(after.counter.counter()))
            return new Assessment(before,after,Attribution.SYNTHETIC_NO_CHANGE,"SAMPLE_CARD_SET_UNCHANGED; NO_CREDIT_PROVEN");
        return new Assessment(before,after,Attribution.CONFLICT,"COUNTER_INCREASE_CANNOT_ATTRIBUTE_SELECTED_CARD");
    }
}
