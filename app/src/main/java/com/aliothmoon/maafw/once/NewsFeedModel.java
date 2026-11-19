package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.once.NativeUiBoundPointsParser.Node;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interprets a single owned Bing tree. Its boxes are observations, never input authority. */
final class NewsFeedModel {
    enum Page { REWARDS, FEED, ARTICLE, BLOCKED, UNKNOWN }
    enum Kind { ENTRY, ARTICLE, AD, UNKNOWN }

    // Names are transient UI evidence, needed for same-card local OCR and matching the opened article.
    record Card(Kind kind, Box box, Box source, Box title, String publisher, String headline,
                String key, String layoutKey) { }
    record Frame(Page page, Box viewport, Box entry, boolean entryComplete, Card card,
                 int stride, boolean atEnd, String reason) { }

    private static final Pattern BYLINE = Pattern.compile(
            "^(.{1,100}?)\\s*[·•丨|]\\s*(?:刚刚|昨天|\\d{1,3}\\s*(?:分钟|小时|天|周|个月)(?:前)?"
            + "|\\d{1,3}\\s*(?:m(?:in)?|h(?:our)?s?|d(?:ay)?s?|weeks?)(?:\\s+ago)?)$");
    private static final Pattern AGE = Pattern.compile("^[·•丨|]?\\s*(?:刚刚|昨天|\\d{1,3}\\s*(?:分钟|小时|天|周|个月)(?:前)?"
            + "|\\d{1,3}\\s*(?:m(?:in)?|h(?:our)?s?|d(?:ay)?s?|weeks?)(?:\\s+ago)?)$");
    private static final Set<String> FEED_HEADINGS = Set.of("新闻","资讯","新闻资讯","为你推荐","news","news feed","for you");
    private static final Set<String> END_LABELS = Set.of("已到列表底部","没有更多内容了","没有更多新闻","no more stories");
    private static final Set<String> AD_LABELS = Set.of("广告","ad","sponsored","advertisement");

    static Frame inspect(List<Node> nodes, int width, int height, int anchorY, Card opened) {
        if (width < 128 || height < 128 || width > 8192 || height > 8192) return unknown("DISPLAY");
        Tree tree = Tree.create(nodes);
        if (tree == null) return unknown("TREE");
        var guard = new LiveVisitGuard.Accumulator();
        var ready = new NativeUiReadyDiagnostics.Accumulator();
        for (Node n : nodes) {
            if (!n.visible()) continue;
            guard.node("", n.text(), n.description(), true);
            ready.node("", n.text(), n.description());
        }
        if (guard.challenges > 0 || guard.dialogs > 0 || ready.blocked())
            return new Frame(Page.BLOCKED, null, null, false, null, 0, false, "BLOCKER");
        Box display = new Box(0, 0, width, height);
        if (tree.hasId("homePageWrapper")) return rewards(tree, display);
        // An open article may render a complete list of recommendations below its body.
        if (opened != null && opened.kind() == Kind.ARTICLE && article(tree, opened))
            return new Frame(Page.ARTICLE, display, null, false, null, 0, false, "MATCHED_ARTICLE");
        return feed(tree, display, anchorY);
    }

    private static Frame rewards(Tree tree, Box display) {
        Node home = tree.uniqueId("homePageWrapper"), panel = tree.uniqueId("dailyTaskPanel");
        Node section = tree.uniqueId("dailyActivities"), entry = tree.uniqueId("read");
        if (home == null || panel == null || section == null || entry == null || !home.visible()
                || panel.parentIndex() != home.index() || section.parentIndex() != panel.index()
                || entry.parentIndex() != section.index()) return unknown("REWARDS_ANCESTRY");
        Box viewport = clip(bounds(home), display);
        if (viewport == null) return unknown("REWARDS_VIEWPORT");
        var bound = NativeUiBoundPointsParser.parseRegions(tree.nodes);
        var count = bound.counters().reading();
        if (count.status() == NativeUiStructureParser.Status.AMBIGUOUS) return unknown("AMBIGUOUS_ENTRY");
        Box card = bounds(entry);
        boolean full = entry.visible() && wellInside(viewport, card);
        // WebView clips an off-screen card to a zero-height rectangle. Only scrolling is possible.
        if (!full) {
            // The clipped position still distinguishes a card above the viewport from one below.
            // Do not infer a direction from the zero box emitted for a detached WebView child.
            boolean horizontal = card.width() > 0 && card.left() >= viewport.left()
                    && card.right() <= viewport.right() && card.bottom() >= card.top();
            boolean above = horizontal && card.top() <= viewport.top() && card.bottom() < viewport.bottom();
            boolean below = horizontal && card.bottom() >= viewport.bottom() && card.top() > viewport.top();
            if (above == below) return unknown("ENTRY_CLIP_POSITION");
            return new Frame(Page.REWARDS, viewport, null, false, null, 0, false,
                    above ? "ENTRY_ABOVE" : "ENTRY_BELOW");
        }
        if (bound.regions().reading() == null || count.status() != NativeUiStructureParser.Status.KNOWN)
            return unknown("ENTRY_SUMMARY");
        return new Frame(Page.REWARDS, viewport, card, count.current() == count.target(),
                null, 0, false, "READING_ENTRY");
    }

    private record Candidate(Node owner, Card card) { }

    private static Frame feed(Tree tree, Box display, int anchorY) {
        List<Candidate> candidates = new ArrayList<>();
        for (Node row : tree.nodes) {
            if (!row.visible()) continue;
            Box box = bounds(row);
            if (!plausibleCard(box, display)) continue;
            if (!row.clickable() && !tree.hasClickableContent(row)) continue;
            candidates.add(new Candidate(row,describe(tree,row)));
        }
        if (candidates.isEmpty()) return unknown("NO_FEED_CARDS");
        candidates.sort(Comparator.comparingInt(c -> c.card.box().top()));
        // A common list with equal-width adjacent siblings establishes pitch. Ignore nested unrelated controls.
        Map<Integer, List<Candidate>> groups = new HashMap<>();
        for (Candidate c : candidates) groups.computeIfAbsent(c.owner.parentIndex(), k -> new ArrayList<>()).add(c);
        List<Candidate> group = null;
        int pitch = 0;
        for (List<Candidate> siblings : groups.values()) {
            int spacing = pitch(siblings);
            if (spacing == 0 || siblings.stream().noneMatch(c -> c.card.kind() == Kind.ARTICLE)) continue;
            if (group != null) return unknown("AMBIGUOUS_FEED");
            group = siblings;
            pitch = spacing;
        }
        if (group == null) return unknown("NO_VERIFIED_CARD_GROUP");
        Node parent = tree.byId.get(group.get(0).owner.parentIndex());
        Box viewport = clip(bounds(parent), display);
        if (viewport == null) return unknown("FEED_VIEWPORT");
        boolean heading = tree.nodes.stream().anyMatch(n -> n.visible()
                && FEED_HEADINGS.contains(value(n)) && !tree.below(n, parent));
        long identified = group.stream().filter(c -> c.card.kind() != Kind.UNKNOWN).count();
        if (identified < 2 && !heading) return unknown("NO_FEED_EVIDENCE");

        List<Card> full = new ArrayList<>();
        Map<String,Integer> occurrences = new HashMap<>();
        for (Candidate c : group) {
            Card card = c.card;
            if (card.kind() == Kind.ARTICLE) occurrences.merge(card.key(),1,Integer::sum);
            if (wellInside(viewport, card.box())) full.add(card);
        }
        int anchor = anchorY < 0 && !full.isEmpty() ? (int)full.get(0).box().centerY() : anchorY;
        Card selected = null;
        int nearest = Integer.MAX_VALUE;
        for (Card card : full) {
            int distance = Math.abs((int)card.box().centerY() - anchor);
            if (distance > card.box().height() / 4) continue;
            if (distance == nearest) return unknown("AMBIGUOUS_ANCHOR");
            if (distance < nearest) { nearest=distance; selected=card; }
        }
        if (selected != null && selected.kind()==Kind.ARTICLE && occurrences.get(selected.key()) != 1)
            selected = unqualified(selected);
        Card last = group.get(group.size()-1).card;
        boolean anchorAtLast = selected == null ? anchor > last.box().centerY() : selected.box().equals(last.box());
        boolean end = anchorAtLast && tree.nodes.stream().anyMatch(n -> n.visible() && END_LABELS.contains(value(n))
                && bounds(n).top() >= last.box().bottom() && viewport.contains(bounds(n)));
        return new Frame(Page.FEED, viewport, null, false, selected, pitch, end,
                selected == null ? "NO_COMPLETE_ANCHORED_CARD" : "FEED_CANDIDATE");
    }

    private static boolean plausibleCard(Box b, Box display) {
        return b.width() >= display.width()*0.48 && b.width() <= display.width()
                && b.height() >= display.width()*0.23 && b.height() <= display.height()*0.85;
    }

    private static int pitch(List<Candidate> siblings) {
        if (siblings.size() < 2) return 0;
        List<Integer> deltas = new ArrayList<>();
        for (int i=1; i<siblings.size(); i++) {
            Box a=siblings.get(i-1).card.box(), b=siblings.get(i).card.box();
            int h=Math.max(a.height(), b.height()), dx=Math.max(4,a.width()/24);
            if (Math.abs(a.left()-b.left())>dx || Math.abs(a.right()-b.right())>dx
                    || b.top()<a.bottom() || b.top()-a.bottom()>Math.max(16,h/3)) return 0;
            // A top-clipped first card must not supply a false short stride.
            if (Math.abs(a.height()-b.height())<=Math.max(4,h/9)) deltas.add(b.top()-a.top());
            else if (a.height()>b.height() && i==siblings.size()-1) deltas.add(b.top()-a.top());
            else if (a.height()<b.height() && i==1) deltas.add(b.height()+b.top()-a.bottom());
            else return 0;
        }
        deltas.sort(Integer::compareTo);
        int median=deltas.get(deltas.size()/2);
        for (int d:deltas) if (Math.abs(d-median)>Math.max(5,median/9)) return 0;
        return median;
    }

    private static Card describe(Tree tree, Node owner) {
        Box card=bounds(owner);
        List<Node> parts=new ArrayList<>();
        for (Node n:tree.nodes) if (n.visible() && tree.below(n,owner) && positive(bounds(n))
                && card.contains(bounds(n)) && !value(n).isEmpty()) parts.add(n);
        List<Box> source=new ArrayList<>();
        String media="", ad="";
        for (Node n:parts) {
            Box b=bounds(n);
            if (b.centerY()<card.top()+card.height()*0.43 || b.centerY()>card.top()+card.height()*0.88) continue;
            String s=value(n);
            if (sourceAd(s) && b.left()<=card.left()+card.width()/3) {
                source.add(b);
                ad=s;
            } else {
                Matcher m=BYLINE.matcher(s);
                if (m.matches()) {source.add(b); media=m.group(1).trim();}
                else if(AGE.matcher(s).matches()){
                    Node publisher=leftPublisher(parts,n);
                    if(publisher!=null){
                        Box both=new Box(publisher.left(),Math.min(publisher.top(),n.top()),n.right(),Math.max(n.bottom(),publisher.bottom()));
                        source.add(both);
                        media=value(publisher);
                    }
                }
            }
        }
        Box src=null;
        for (Box b:source) {
            if (src!=null && !src.equals(b)) return unknownCard(owner,parts);
            src=b;
        }
        if (src==null) return unknownCard(owner,parts);
        String headline="";
        Box title=null;
        for (Node n:parts) {
            Box b=bounds(n); String s=value(n);
            if (b.top()<src.bottom()-2 || b.height()>card.height()*.43 || b.width()<card.width()*.18
                    || s.length()<2 || s.length()>512 || END_LABELS.contains(s) || isAd(s)) continue;
            if (title!=null && (!title.equals(b) || !headline.equals(s))) return unknownCard(owner,parts);
            headline=s;title=b;
        }
        String layout=layout(card,parts);
        if (!ad.isEmpty()) return new Card(Kind.AD,card,src,title,"",headline,"",layout);
        if (media.isEmpty() || title==null || ellipsis(headline))
            return new Card(Kind.UNKNOWN,card,src,title,media,headline,"",layout);
        return new Card(Kind.ARTICLE,card,src,title,media,headline,
                LiveCardIdentity.hashText("publisher:"+media.length()+":"+media+"|article:"+headline),layout);
    }

    private static Node leftPublisher(List<Node> parts,Node age){
        Node best=null;
        for(Node n:parts) {
            String s=value(n);
            if(n.index()==age.index()||n.parentIndex()!=age.parentIndex()||n.right()>age.left()+2
                    ||s.length()<2||s.length()>80||!s.matches(".*[\\p{L}].*")||isAd(s)
                    ||Math.abs(bounds(n).centerY()-bounds(age).centerY())>Math.max(3,bounds(age).height()/3)
                    ||age.left()-n.right()>Math.max(56,bounds(age).height()*2))continue;
            if(best!=null)return null;
            best=n;
        }
        return best;
    }

    private static Card unknownCard(Node owner,List<Node> parts) {
        return new Card(Kind.UNKNOWN,bounds(owner),null,null,"","","",layout(bounds(owner),parts));
    }
    private static Card unqualified(Card c) {
        return new Card(Kind.UNKNOWN,c.box(),c.source(),c.title(),c.publisher(),c.headline(),"",c.layoutKey());
    }
    private static String layout(Box b,List<Node> parts) {
        StringBuilder s=new StringBuilder().append(b.left()).append(',').append(b.top())
                .append(',').append(b.right()).append(',').append(b.bottom());
        for(Node n:parts) s.append('|').append(LiveCardIdentity.hashText(value(n))).append(':').append(bounds(n));
        return LiveCardIdentity.hashText(s.toString());
    }
    static boolean isAd(String value) {
        return AD_LABELS.contains(LiveCardIdentity.normalize(value));
    }
    static boolean sourceAd(String value) {
        String s=LiveCardIdentity.normalize(value);
        return isAd(s) || s.replace(" ","").startsWith("广告")
                || s.matches("^(?:ad|sponsored|advertisement)[\\s,.:|·]+.+$");
    }
    static boolean ellipsis(String s) { return s.endsWith("…") || s.endsWith("..."); }

    private static boolean article(Tree tree, Card opened) {
        Node title=null,publisher=null;
        List<Node> paragraphs=new ArrayList<>();
        for (Node n:tree.nodes) {
            if(!n.visible()||!positive(bounds(n)))continue;
            String s=value(n);
            if (s.equals(opened.headline())){
                if(title!=null||tree.insideLink(n))return false;
                title=n;
            } else if (s.equals(opened.publisher())){
                if(publisher!=null||tree.insideLink(n))return false;
                publisher=n;
            }else if (!tree.insideLink(n) && s.length()>=45 && s.length()<=2048
                    && !s.contains("http://") && !s.contains("https://")
                    && (s.contains("。") || s.contains(". ") || s.contains("！") || s.contains("! ")))
                paragraphs.add(n);
        }
        if(title==null||publisher==null||paragraphs.isEmpty())return false;
        for(Node host=tree.byId.get(title.parentIndex());host!=null&&host.parentIndex()>=0;host=tree.byId.get(host.parentIndex())) {
            if(!tree.below(publisher,host)||host.clickable()||!host.visible()||!bounds(host).contains(bounds(title))
                    ||!bounds(host).contains(bounds(publisher)))continue;
            // Do not bind coincidental labels across separate branches of the page.
            Node headerBranch=tree.directBranch(title,host), publisherBranch=tree.directBranch(publisher,host);
            if(headerBranch!=publisherBranch && (title.parentIndex()!=host.index()||publisher.parentIndex()!=host.index()))continue;
            for(Node body:paragraphs)if(tree.below(body,host)&&bounds(host).contains(bounds(body))
                    &&body.top()>=Math.max(title.bottom(),publisher.bottom())-2
                    &&body.right()>title.left()&&body.left()<title.right())return true;
        }
        return false;
    }

    static Box bounds(Node n) {return new Box(n.left(),n.top(),n.right(),n.bottom());}
    static boolean positive(Box b){return b!=null && b.width()>0 && b.height()>0;}
    private static Box clip(Box a,Box b) {
        if(!positive(a)||!positive(b))return null;
        Box result=new Box(Math.max(a.left(),b.left()),Math.max(a.top(),b.top()),
                Math.min(a.right(),b.right()),Math.min(a.bottom(),b.bottom()));
        return positive(result)?result:null;
    }
    private static boolean wellInside(Box v,Box b){return positive(b) && v.contains(b) && b.top()>v.top() && b.bottom()<v.bottom();}
    private static String value(Node n) {
        String t=LiveCardIdentity.normalize(n.text()), d=LiveCardIdentity.normalize(n.description());
        return !t.isEmpty()?t:d;
    }
    private static Frame unknown(String why){return new Frame(Page.UNKNOWN,null,null,false,null,0,false,why);}

    private static final class Tree {
        final List<Node> nodes;
        final Map<Integer,Node> byId=new HashMap<>();
        Tree(List<Node> nodes){this.nodes=nodes;}
        static Tree create(List<Node> nodes) {
            if(nodes==null||nodes.isEmpty()||nodes.size()>512)return null;
            Tree tree=new Tree(nodes);
            Map<Integer,Integer>depths=new HashMap<>();
            int roots=0,chars=0;
            for(Node n:nodes) {
                if(n==null||!n.packageMatches()||n.index()<0||tree.byId.containsKey(n.index()))return null;
                Integer d=n.parentIndex()==-1?Integer.valueOf(0):depths.get(n.parentIndex());
                if(d==null||d>32)return null;
                if(n.parentIndex()==-1){if(++roots>1||!n.visible()||!positive(bounds(n)))return null;}
                else d++;
                if(d>32)return null;
                for(String field:new String[]{n.text(),n.description(),n.resourceId()}){
                    if(field!=null){if(field.length()>2048)return null;chars+=field.length();}
                }
                if(chars>131072)return null;
                depths.put(n.index(),d);tree.byId.put(n.index(),n);
            }
            return roots==1?tree:null;
        }
        boolean below(Node n,Node parent){
            for(Node p=byId.get(n.parentIndex());p!=null;p=byId.get(p.parentIndex()))
                if(p.index()==parent.index())return true;
            return false;
        }
        boolean insideLink(Node n){for(Node at=n;at!=null;at=byId.get(at.parentIndex()))if(at.clickable())return true;return false;}
        boolean hasClickableContent(Node n){
            for(Node child:nodes)if(child.visible()&&child.clickable()&&below(child,n)
                    &&bounds(n).contains(bounds(child))&&bounds(child).width()>=bounds(n).width()/2)return true;
            return false;
        }
        Node directBranch(Node child,Node parent){
            Node at=child;
            while(at!=null && at.parentIndex()!=parent.index())at=byId.get(at.parentIndex());
            return at;
        }
        boolean hasId(String id){return nodes.stream().anyMatch(n->id.equals(n.resourceId()));}
        Node uniqueId(String id){Node found=null;for(Node n:nodes)if(id.equals(n.resourceId())){
            if(found!=null)return null;found=n;}return found;}
    }
    private NewsFeedModel(){}
}
