package io.github.klwb360.rewards.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static io.github.klwb360.rewards.core.RewardsCore.*;

/** Pure recognition of the observed three-card Play Bing Daily set group. */
public final class BingDailySetPage {
    private static final Pattern ACTIVE = Pattern.compile("\\A(.+), earn ([0-9]+) points\\z");
    private static final Pattern COMPLETED = Pattern.compile("\\A(.+), ([0-9]+) points earned\\z");
    private static final Pattern ACTIVE_ZH = Pattern.compile("\\A(.+), 赚取 ([0-9]+) 积分\\z");
    private static final Pattern COMPLETED_ZH = Pattern.compile("\\A(.+), 已赚取 ([0-9]+) 积分\\z");
    private static final String EARNED_SUFFIX = ", 10 points earned";
    private BingDailySetPage() {}

    /** Labels are transient. The caller must persist only its own hashes and policy state. */
    public record Card(String nodeId, String label, int points, boolean completed) {}
    public record Result(ParseStatus status, String scopeId, List<Card> cards, Observation observation) {
        public Result { cards = List.copyOf(cards); }
    }

    /** Active labels expose no independent title field. Only one ASCII comma can identify its boundary. */
    public static Optional<String> unambiguousActiveTitle(Card card) {
        if (card == null || card.completed() || card.points() != 10 || card.label() == null) return Optional.empty();
        Matcher active = activeMatcher(card.label());
        if (active == null || !tenPoints(active.group(2))) return Optional.empty();
        String body = active.group(1);
        int separator = body.indexOf(", ");
        if (separator <= 0 || separator + 2 >= body.length() || body.indexOf(',') != separator
                || body.lastIndexOf(',') != separator) return Optional.empty();
        return Optional.of(body.substring(0, separator));
    }

    public static Result parse(List<Node> nodes) {
        if (nodes == null) return failure(ParseStatus.INVALID, "null tree");
        Map<String, Node> byId = new HashMap<>();
        Map<String, List<Node>> children = new HashMap<>();
        for (Node node : nodes) {
            if (node == null || node.id() == null || node.id().isEmpty() || byId.putIfAbsent(node.id(), node) != null)
                return failure(ParseStatus.INVALID, "duplicate or empty node ID");
            if (node.parentId() == null) return failure(ParseStatus.INVALID, "null parent ID");
            children.computeIfAbsent(node.parentId(), ignored -> new ArrayList<>()).add(node);
        }
        List<Node> activityHeadings = new ArrayList<>(), dailyHeadings = new ArrayList<>();
        for (Node node : nodes) {
            if (!node.visible()) continue;
            String label = label(node);
            if ("Daily activities".equals(label)) activityHeadings.add(node);
            if ("Daily set".equals(label)) dailyHeadings.add(node);
        }
        if (activityHeadings.isEmpty() && dailyHeadings.isEmpty())
            return parseChinese(nodes, byId, children);
        if (activityHeadings.size() > 1) return failure(ParseStatus.AMBIGUOUS, "multiple Daily activities headings");
        if (activityHeadings.isEmpty() || dailyHeadings.isEmpty()) return failure(ParseStatus.MISSING, "headings missing");
        Node activityGroup = byId.get(activityHeadings.get(0).parentId());
        if (activityGroup == null || !activityGroup.visible() || activityGroup.parentId().isEmpty())
            return failure(ParseStatus.INVALID, "Daily activities scope missing");

        List<Node> candidates = new ArrayList<>();
        for (Node heading : dailyHeadings) {
            Node group = byId.get(heading.parentId());
            if (group == null || !group.visible()) return failure(ParseStatus.INVALID, "Daily set scope missing");
            if (group.id().equals(activityGroup.id()) || !group.parentId().equals(activityGroup.parentId())) continue;
            int offerShapedSiblings = 0;
            for (Node sibling : children.getOrDefault(group.id(), List.of())) {
                if (sibling == heading || !sibling.visible()) continue;
                String label = label(sibling);
                if (label != null && (label.contains(", earn ") || label.matches(".+, [^,]+ points earned")))
                    offerShapedSiblings++;
            }
            // A progress header may show points, but it has no offer cards.
            if (offerShapedSiblings > 0) candidates.add(heading);
        }
        if (candidates.isEmpty()) return failure(ParseStatus.MISSING, "Daily set offers missing");
        if (candidates.size() != 1) return failure(ParseStatus.AMBIGUOUS, "multiple Daily set offer groups");
        Node heading = candidates.get(0);
        Node group = byId.get(heading.parentId());
        List<Card> cards = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (Node sibling : children.getOrDefault(group.id(), List.of())) {
            if (!sibling.visible() || sibling == heading) continue;
            Card card = parseCard(sibling, children);
            if (card == null) return failure(ParseStatus.INVALID, "unknown or malformed Daily set card");
            if (!labels.add(card.label())) return failure(ParseStatus.AMBIGUOUS, "duplicate sibling card label");
            cards.add(card);
        }
        if (cards.size() != 3) return failure(ParseStatus.INVALID, "Daily set is not three logical cards");
        int completed = (int) cards.stream().filter(Card::completed).count();
        Observation observation = new Observation(ParseStatus.OK, new Counter(Task.WEB, completed, 3, false),
                "exact scoped Daily set card states observed");
        return new Result(ParseStatus.OK, group.id(), cards, observation);
    }

    /** A completion is attributable only when exactly the selected active label changes. */
    public static boolean matchesSelectedCompletion(Card attempted, Result before, Result after) {
        if (attempted == null || before == null || after == null || attempted.completed()
                || before.status() != ParseStatus.OK || after.status() != ParseStatus.OK
                || !before.scopeId().equals(after.scopeId()) || !before.cards().contains(attempted)
                || after.observation().counter().current() != before.observation().counter().current() + 1) return false;
        List<Card> newCompleted = new ArrayList<>();
        for (Card old : before.cards()) {
            if (old.completed() && after.cards().stream().noneMatch(n -> n.completed() && n.label().equals(old.label()))) return false;
            if (!old.completed() && !old.equals(attempted)
                    && after.cards().stream().noneMatch(n -> !n.completed() && n.label().equals(old.label()))) return false;
        }
        if (after.cards().stream().anyMatch(n -> !n.completed() && n.label().equals(attempted.label()))) return false;
        for (Card card : after.cards()) if (card.completed()
                && before.cards().stream().noneMatch(old -> old.completed() && old.label().equals(card.label()))) newCompleted.add(card);
        if (newCompleted.size() != 1) return false;
        Card completed = newCompleted.get(0);
        Optional<String> title = unambiguousActiveTitle(attempted);
        String suffix = ACTIVE_ZH.matcher(attempted.label()).matches() ? ", 已赚取 10 积分" : EARNED_SUFFIX;
        if (title.isEmpty() || completed.points() != attempted.points()
                || !completed.label().equals(title.get() + suffix)) return false;
        return before.cards().stream().filter(c -> !c.completed()
                && unambiguousActiveTitle(c).filter(title.get()::equals).isPresent()).count() == 1;
    }

    private static Card parseCard(Node node, Map<String, List<Node>> children) {
        if (!node.enabled()) return null;
        String label = label(node);
        if (label == null || label.isEmpty()) return null;
        Matcher active = activeMatcher(label), completed = completedMatcher(label);
        if (active != null) {
            String titleAndDescription = active.group(1);
            int separator = titleAndDescription.indexOf(", ");
            if (separator <= 0 || separator + 2 >= titleAndDescription.length()
                    || !node.clickable() || !tenPoints(active.group(2))) return null;
            List<Node> descendants = children.getOrDefault(node.id(), List.of());
            if (descendants.size() != 1) return null;
            Node inner = descendants.get(0);
            if (!inner.visible() || !inner.enabled() || !inner.clickable() || !label.equals(label(inner))
                    || !children.getOrDefault(inner.id(), List.of()).isEmpty()) return null;
            return new Card(node.id(), label, 10, false);
        }
        if (completed != null) {
            if (completed.group(1).isEmpty() || node.clickable() || !tenPoints(completed.group(2))
                    || !children.getOrDefault(node.id(), List.of()).isEmpty()) return null;
            return new Card(node.id(), label, 10, true);
        }
        return null;
    }

    /** Shared exact text grammar only. Does not infer semantic/card state from OCR. */
    public enum TextStatus { EXPLICIT_EARN_OFFER, EXPLICIT_POINTS_EARNED_TEXT }
    public record OfferText(int titleCodePoints,int points,TextStatus status) {}
    public static Optional<OfferText> observeOfferText(String label) {
        if(label==null||label.length()>512||label.chars().anyMatch(Character::isISOControl))return Optional.empty();
        Matcher active=activeMatcher(label),completed=completedMatcher(label);
        if(active!=null && tenPoints(active.group(2))) {
            String body=active.group(1);int comma=body.indexOf(", ");
            if(comma<=0||comma+2>=body.length()||body.indexOf(',')!=comma||body.lastIndexOf(',')!=comma)return Optional.empty();
            String title=body.substring(0,comma);
            return Optional.of(new OfferText(title.codePointCount(0,title.length()),10,TextStatus.EXPLICIT_EARN_OFFER));
        }
        if(completed!=null && tenPoints(completed.group(2)) && completed.group(1).indexOf(',')<0){
            String title=completed.group(1);
            return Optional.of(new OfferText(title.codePointCount(0,title.length()),10,TextStatus.EXPLICIT_POINTS_EARNED_TEXT));
        }
        return Optional.empty();
    }
    private static boolean tenPoints(String value) { return "10".equals(value); }
    private static Matcher activeMatcher(String label) {
        Matcher english = ACTIVE.matcher(label);
        if (english.matches()) return english;
        Matcher chinese = ACTIVE_ZH.matcher(label);
        return chinese.matches() ? chinese : null;
    }
    private static Matcher completedMatcher(String label) {
        Matcher english = COMPLETED.matcher(label);
        if (english.matches()) return english;
        Matcher chinese = COMPLETED_ZH.matcher(label);
        return chinese.matches() ? chinese : null;
    }
    /** Chinese UI uses the same visible heading for two sibling task groups. */
    private static Result parseChinese(List<Node> nodes, Map<String, Node> byId, Map<String, List<Node>> children) {
        List<Node> headings = new ArrayList<>(), candidates = new ArrayList<>();
        for (Node node : nodes) if ("每日活动".equals(label(node))) headings.add(node);
        for (Node heading : headings) {
            if (!heading.visible()) continue;
            Node group = byId.get(heading.parentId());
            if (group == null || !group.visible() || group.parentId().isEmpty()) continue;
            boolean siblingHeading = headings.stream().filter(other -> !other.id().equals(heading.id()))
                    .map(other -> byId.get(other.parentId())).filter(Objects::nonNull)
                    .anyMatch(other -> !other.id().equals(group.id()) && other.parentId().equals(group.parentId()));
            if (!siblingHeading) continue;
            boolean offerShaped = children.getOrDefault(group.id(), List.of()).stream().filter(Node::visible)
                    .map(BingDailySetPage::label).filter(Objects::nonNull)
                    .anyMatch(value -> value.contains(", 赚取 ") || value.contains(", 已赚取 "));
            if (offerShaped) candidates.add(heading);
        }
        if (candidates.isEmpty()) return failure(ParseStatus.MISSING, "Chinese Daily set offers missing");
        if (candidates.size() != 1) return failure(ParseStatus.AMBIGUOUS, "multiple Chinese Daily set offer groups");
        Node heading = candidates.get(0), group = byId.get(heading.parentId());
        List<Card> cards = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (Node sibling : children.getOrDefault(group.id(), List.of())) {
            if (!sibling.visible() || sibling.id().equals(heading.id())) continue;
            Card card = parseCard(sibling, children);
            if (card == null) return failure(ParseStatus.INVALID, "unknown or malformed Chinese Daily set card");
            if (!labels.add(card.label())) return failure(ParseStatus.AMBIGUOUS, "duplicate Chinese Daily set card");
            cards.add(card);
        }
        if (cards.size() != 3) return failure(ParseStatus.INVALID, "Chinese Daily set is not three logical cards");
        int completed = (int) cards.stream().filter(Card::completed).count();
        return new Result(ParseStatus.OK, group.id(), cards,
                new Observation(ParseStatus.OK, new Counter(Task.WEB, completed, 3, false), "exact scoped Chinese Daily set card states observed"));
    }
    private static String label(Node node) {
        String text = node.text() == null ? "" : node.text();
        String description = node.description() == null ? "" : node.description();
        if (!text.isEmpty() && !description.isEmpty() && !text.equals(description)) return null;
        return text.isEmpty() ? description : text;
    }
    private static Result failure(ParseStatus status, String reason) {
        return new Result(status, "", List.of(), new Observation(status, null, reason));
    }
}
