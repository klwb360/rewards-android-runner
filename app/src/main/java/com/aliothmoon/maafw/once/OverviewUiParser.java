package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact known home ancestry plus bounded structural roles. Image recognition owns final boundaries. */
final class OverviewUiParser {
    record ScrollNode(int index, boolean backwardDeclared, boolean forwardDeclared) { }
    private static final Pattern FRACTION = Pattern.compile("(0|[1-9][0-9]{0,4})/(0|[1-9][0-9]{0,4})");
    private record Fraction(Box box, int current, int target) { }
    private final List<NativeUiBoundPointsParser.Node> nodes;
    private final Map<Integer, NativeUiBoundPointsParser.Node> byIndex = new HashMap<>();
    private final List<OverviewUiRegions.Region> regions = new ArrayList<>();
    private NativeUiBoundPointsParser.Node root, home, panel;
    private boolean ambiguous;

    static OverviewUiRegions.Result parse(List<NativeUiBoundPointsParser.Node> nodes) {
        if (nodes == null) return OverviewUiRegions.Result.rejected(OverviewUiRegions.Status.WRONG_STRUCTURE);
        try { return new OverviewUiParser(nodes).parse(); }
        catch (IllegalArgumentException invalid) {
            return OverviewUiRegions.Result.rejected(OverviewUiRegions.Status.WRONG_STRUCTURE);
        }
    }
    static OverviewUiProtocol.ScrollHint scrollHint(List<NativeUiBoundPointsParser.Node> nodes,
                                                    List<ScrollNode> scrollNodes) {
        if(nodes==null||scrollNodes==null||scrollNodes.size()>512)return OverviewUiProtocol.ScrollHint.UNKNOWN;
        var parser=new OverviewUiParser(nodes);
        if(!parser.shape())return OverviewUiProtocol.ScrollHint.UNKNOWN;
        var home=parser.uniqueId("homePageWrapper");
        if(home==null||parser.ambiguous||!visible(home))return OverviewUiProtocol.ScrollHint.UNKNOWN;
        ScrollNode found=null;
        for(var scroll:scrollNodes) {
            if(scroll==null)return OverviewUiProtocol.ScrollHint.UNKNOWN;
            var node=parser.byIndex.get(scroll.index());
            if(!visible(node)||!box(node).contains(box(home))||!parser.descendant(home,node))continue;
            if(found!=null)return OverviewUiProtocol.ScrollHint.UNKNOWN;found=scroll;
        }
        if(found==null)return OverviewUiProtocol.ScrollHint.UNKNOWN;
        return found.backwardDeclared() ? (found.forwardDeclared()?OverviewUiProtocol.ScrollHint.DECLARED_BOTH
                :OverviewUiProtocol.ScrollHint.DECLARED_BACKWARD) : (found.forwardDeclared()?OverviewUiProtocol.ScrollHint.DECLARED_FORWARD
                :OverviewUiProtocol.ScrollHint.DECLARED_NONE);
    }
    private OverviewUiParser(List<NativeUiBoundPointsParser.Node> nodes) { this.nodes = nodes; }

    private OverviewUiRegions.Result parse() {
        if (!shape()) return OverviewUiRegions.Result.rejected(OverviewUiRegions.Status.WRONG_STRUCTURE);
        home = uniqueId("homePageWrapper"); panel = uniqueId("dailyTaskPanel");
        if (ambiguous || home == null || panel == null || !visible(home) || !visible(panel)
                || panel.parentIndex() != home.index() || !box(home).contains(box(panel))) {
            return OverviewUiRegions.Result.rejected(ambiguous ? OverviewUiRegions.Status.AMBIGUOUS_STRUCTURE
                    : OverviewUiRegions.Status.WRONG_STRUCTURE);
        }
        List<NativeUiBoundPointsParser.Node> siblings = children(panel);
        // These are the observed direct roles. Search/read's dailyActivities container is never admitted.
        activities(siblings);
        more(siblings);
        weekly(siblings, "签入", OverviewUiRegions.Role.CHECK_IN);
        weekly(siblings, "每日活动", OverviewUiRegions.Role.DAILY_PROGRESS);
        summary(siblings, "今日积分", OverviewUiRegions.Role.POINTS_SUMMARY);
        summary(siblings, "连续", OverviewUiRegions.Role.STREAK_SUMMARY);
        if (ambiguous) return OverviewUiRegions.Result.rejected(OverviewUiRegions.Status.AMBIGUOUS_STRUCTURE);
        regions.sort(java.util.Comparator.comparingInt(x -> x.role().ordinal()));
        return new OverviewUiRegions.Result(OverviewUiRegions.Status.OBSERVED, box(root), regions);
    }

    private boolean shape() {
        if (nodes.isEmpty() || nodes.size() > 512) return false;
        Map<Integer,Integer> depth = new HashMap<>(); int chars = 0;
        for (var node : nodes) {
            if (node == null || !node.packageMatches() || node.index() < 0 || byIndex.containsKey(node.index())) return false;
            if (node.parentIndex() == -1) {
                if (root != null || !visible(node)) return false;
                root = node; depth.put(node.index(),0);
            } else {
                Integer parentDepth = depth.get(node.parentIndex());
                if (parentDepth == null || parentDepth >= 32) return false;
                depth.put(node.index(), parentDepth+1);
            }
            if (node.resourceId() != null && node.resourceId().length() > 2048) return false;
            for (String text : new String[]{node.text(),node.description()}) if (text != null) {
                if (text.length() > 2048 || (chars += text.length()) > 32768) return false;
            }
            if (node.visible() && (!bounds(node) || root == null || !box(root).contains(box(node)))) return false;
            byIndex.put(node.index(),node);
        }
        return root != null;
    }
    private NativeUiBoundPointsParser.Node uniqueId(String id) {
        NativeUiBoundPointsParser.Node result = null;
        for (var node : nodes) if (id.equals(node.resourceId())) {
            if (result != null) { ambiguous = true; return null; }
            result = node;
        }
        return result;
    }
    private List<NativeUiBoundPointsParser.Node> children(NativeUiBoundPointsParser.Node parent) {
        List<NativeUiBoundPointsParser.Node> result = new ArrayList<>();
        for (var node : nodes) if (node.parentIndex() == parent.index()) result.add(node);
        return result;
    }
    private boolean descendant(NativeUiBoundPointsParser.Node node, NativeUiBoundPointsParser.Node ancestor) {
        for (int i=0;i<32 && node.parentIndex()>=0;i++) {
            if (node.parentIndex() == ancestor.index()) return true;
            node = byIndex.get(node.parentIndex()); if (node == null) return false;
        }
        return false;
    }
    private static boolean exact(NativeUiBoundPointsParser.Node node, String value) {
        return value.equals(node.text()) || value.equals(node.description());
    }
    private NativeUiBoundPointsParser.Node childTitle(NativeUiBoundPointsParser.Node parent, String title) {
        NativeUiBoundPointsParser.Node found=null;
        for (var node : children(parent)) if (exact(node,title)) {
            if (found != null) { ambiguous=true; return null; }
            found=node;
        }
        return found;
    }
    private void activities(List<NativeUiBoundPointsParser.Node> siblings) {
        var container=uniqueId("dailySet");
        if (container==null || !visible(container)) return;
        if (container.parentIndex()!=panel.index() || !box(panel).contains(box(container))) { ambiguous=true; return; }
        var title=childTitle(container,"每日活动");
        if (title==null || visible(title) && !box(container).contains(box(title))) return;
        int index=siblings.indexOf(container);
        // The page's 今日积分 counter belongs to this exact dailySet; never search/read's dailyActivities.
        NativeUiBoundPointsParser.Node today=null;
        for(int i=0;i<index;i++) if(exact(siblings.get(i),"今日积分")) {
            if(today!=null) { ambiguous=true;return; } today=siblings.get(i);
        }
        Fraction fraction=null;
        if(today!=null && visible(today)) {
            int from=siblings.indexOf(today)+1;
            if(index-from==1) fraction=fraction(siblings.get(from));
        }
        Box coarse=box(container);
        if(fraction!=null) coarse=union(coarse,fraction.box());
        add(OverviewUiRegions.Role.DAILY_ACTIVITIES,coarse,visible(title)?box(title):null,fraction,complete(container));
    }
    private void more(List<NativeUiBoundPointsParser.Node> siblings) {
        var title=uniqueId("more-activities-panel-title");
        if(title==null) return;
        var container=byIndex.get(title.parentIndex());
        if(container==null || container.parentIndex()!=panel.index() || !siblings.contains(container)
                || !exact(title,"更多活动")) {
            ambiguous=true; return;
        }
        if(!visible(container))return;
        if(visible(title)&&!box(container).contains(box(title))) {ambiguous=true;return;}
        // A card can itself contain a fraction. Only the current visible heading's right-hand
        // band can own the section counter; an offscreen heading supplies no counter authority.
        Fraction fraction=visible(title)&&title.right()<container.right()
                ? fraction(container,new Box(title.right(),title.top(),container.right(),title.bottom())) : null;
        add(OverviewUiRegions.Role.MORE_ACTIVITIES,box(container),visible(title)?box(title):null,fraction,complete(container));
    }
    private void weekly(List<NativeUiBoundPointsParser.Node> siblings,String label,OverviewUiRegions.Role role) {
        NativeUiBoundPointsParser.Node header=null,title=null;int index=-1;
        for(int i=0;i<siblings.size();i++) {
            var candidate=siblings.get(i);
            if(candidate.resourceId()!=null && !candidate.resourceId().isEmpty()) continue;
            var heading=childTitle(candidate,label);
            if(heading==null) continue;
            if(header!=null) { ambiguous=true;return; }
            header=candidate;title=heading;index=i;
        }
        if(header==null || !visible(header) || !visible(title) || index+1>=siblings.size()) return;
        var days=siblings.get(index+1);
        if(!visible(days) || !sevenDays(days) || !box(header).contains(box(title))
                || header.bottom()>days.top() || header.left()!=days.left() || header.right()!=days.right()) return;
        // The accessibility tree has no outer card node. Bound the candidate to this exact sibling
        // pair and neighbouring siblings, leaving the pixel observer to locate the complete card.
        int top=header.top(),bottom=days.bottom();
        if(index>0 && visible(siblings.get(index-1))) top=Math.min(top,siblings.get(index-1).bottom());
        else top=Math.max(panel.top(),top-(header.bottom()-header.top()));
        if(index+2<siblings.size() && visible(siblings.get(index+2))) bottom=Math.max(bottom,siblings.get(index+2).top());
        else bottom=Math.min(panel.bottom(),bottom+(header.bottom()-header.top()));
        Box coarse=new Box(panel.left(),Math.max(panel.top(),top),panel.right(),Math.min(panel.bottom(),bottom));
        boolean allVisible=complete(header)&&complete(days)&&coarse.top()>home.top()&&coarse.bottom()<home.bottom();
        add(role,coarse,box(title),null,allVisible);
    }
    private boolean sevenDays(NativeUiBoundPointsParser.Node body) {
        boolean[] seen=new boolean[7];int count=0,lastX=-1;
        for(var node:nodes) if(descendant(node,body)) {
            String label=node.text();
            if(label==null || !label.matches("Day [1-7]")) continue;
            int index=label.charAt(4)-'1';
            if(seen[index] || index!=count || !visible(node) || !box(body).contains(box(node)) || node.left()<=lastX) return false;
            seen[index]=true;lastX=node.left();count++;
        }
        return count==7;
    }
    private void summary(List<NativeUiBoundPointsParser.Node> siblings,String label,OverviewUiRegions.Role role) {
        NativeUiBoundPointsParser.Node title=null;int index=-1;
        for(int i=0;i<siblings.size();i++) if(exact(siblings.get(i),label)) {
            if(title!=null) {ambiguous=true;return;}title=siblings.get(i);index=i;
        }
        if(title==null || !visible(title)) return;
        Box coarse=box(title);Fraction found=null;
        for(int i=index+1;i<Math.min(index+3,siblings.size());i++) {
            var next=siblings.get(i);if(!visible(next))continue;
            if(next.top()>=title.bottom() || next.bottom()<=title.top()) break;
            coarse=union(coarse,box(next));
            var possible=fraction(next);if(possible!=null){if(found!=null){ambiguous=true;return;}found=possible;}
        }
        add(role,coarse,box(title),found,complete(title));
    }
    private Fraction fraction(NativeUiBoundPointsParser.Node container) {
        return fraction(container,null);
    }
    private Fraction fraction(NativeUiBoundPointsParser.Node container,Box allowedBand) {
        Fraction found=null;
        for(var node:nodes) if((node.index()==container.index()||descendant(node,container)) && visible(node)) {
            String text=node.text();if(text==null)continue;Matcher m=FRACTION.matcher(text);if(!m.matches())continue;
            if(!box(container).contains(box(node))) {ambiguous=true;return null;}
            if(allowedBand!=null&&!allowedBand.contains(box(node)))continue;
            int current=Integer.parseInt(m.group(1)),target=Integer.parseInt(m.group(2));
            if(target<=0||target>10000||current>target) {ambiguous=true;return null;}
            if(found!=null) {ambiguous=true;return null;}found=new Fraction(box(node),current,target);
        }
        return found;
    }
    private void add(OverviewUiRegions.Role role,Box coarse,Box heading,Fraction counter,boolean complete) {
        if(!box(root).contains(coarse) || heading!=null&&!coarse.contains(heading)) {ambiguous=true;return;}
        for(var prior:regions) if(prior.role()==role) {ambiguous=true;return;}
        regions.add(new OverviewUiRegions.Region(role,coarse,heading,counter==null?null:counter.box(),complete,
                counter==null?-1:counter.current(),counter==null?-1:counter.target()));
    }
    private boolean complete(NativeUiBoundPointsParser.Node node) {
        // The app root can include browser chrome below the actual Rewards viewport.
        return visible(node)&&node.top()>home.top()&&node.bottom()<home.bottom();
    }
    private static Box union(Box a,Box b) {return new Box(Math.min(a.left(),b.left()),Math.min(a.top(),b.top()),Math.max(a.right(),b.right()),Math.max(a.bottom(),b.bottom()));}
    private static Box box(NativeUiBoundPointsParser.Node n) {return new Box(n.left(),n.top(),n.right(),n.bottom());}
    private static boolean visible(NativeUiBoundPointsParser.Node n) {return n!=null&&n.visible()&&bounds(n);}
    private static boolean bounds(NativeUiBoundPointsParser.Node n) {return n.left()>=0&&n.top()>=0&&n.right()>n.left()&&n.bottom()>n.top()&&n.right()<=8192&&n.bottom()<=8192;}
}
