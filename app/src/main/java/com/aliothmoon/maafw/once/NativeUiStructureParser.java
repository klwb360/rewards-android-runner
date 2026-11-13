package com.aliothmoon.maafw.once;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact source-backed full-field UI summaries only. No tree relationship grants counter ownership. */
final class NativeUiStructureParser {
    private NativeUiStructureParser() {}
    enum Status { UNKNOWN, KNOWN, AMBIGUOUS, INVALID }
    enum Unit { EXPLICIT_TEXT_POINTS, UNESTABLISHED }
    record Node(int index,int parentIndex,boolean visible,boolean packageMatches,String text,String description) {}
    record TaskResult(Status status,int current,int target,int matchCount,Unit unit) {}
    enum CounterSource { UI_STRUCTURE }
    record Result(TaskResult search,TaskResult reading) {
        CounterSource counterSource(){return CounterSource.UI_STRUCTURE;}
        boolean actionAuthority(){return false;}
    }
    private static final Pattern UINT=Pattern.compile("[0-9]+");
    private static Pattern english(String task){return Pattern.compile("\\A"+task+" to earn, , (\\S+) out of (\\S+) points earned\\z");}
    private static Pattern chinese(String task){return Pattern.compile("\\A"+task+"以赚取, , 已赚取 (\\S+) 积分\\(需要 (\\S+) 积分\\)\\z");}
    private static final Pattern SEARCH=english("Search"),READ=english("Read"),SEARCH_CN=chinese("搜索"),READ_CN=chinese("阅读");
    static Result parse(List<Node> nodes){
        if(!shape(nodes)){TaskResult bad=result(Status.INVALID,0,0,0);return new Result(bad,bad);}
        return new Result(scan(nodes,true),scan(nodes,false));
    }
    private static boolean shape(List<Node> nodes){
        if(nodes==null||nodes.size()>512)return false;Set<Integer> seen=new HashSet<>();int chars=0,roots=0;
        for(Node node:nodes){
            if(node==null||node.index()<0||seen.contains(node.index()))return false;
            if(node.parentIndex()==-1){if(++roots>1)return false;}else if(!seen.contains(node.parentIndex()))return false;
            seen.add(node.index());
            for(String field:new String[]{node.text(),node.description()}){if(field!=null){if(field.length()>2048)return false;chars+=field.length();if(chars>32768)return false;}}
        }
        return true;
    }
    private static TaskResult scan(List<Node> nodes,boolean search){
        int count=0,current=0,target=0;boolean invalid=false;String en=search?"Search":"Read",cn=search?"搜索":"阅读";
        for(Node node:nodes){
            if(!node.visible()||!node.packageMatches())continue;
            String[] fields={node.text(),node.description()};
            for(int i=0;i<fields.length;i++){
                String field=fields[i];if(field==null||(i==1&&field.equals(fields[0])))continue;
                Matcher matcher=(search?SEARCH:READ).matcher(field);
                if(!matcher.matches()){matcher=(search?SEARCH_CN:READ_CN).matcher(field);if(!matcher.matches()){
                    // Incomplete full-summary syntax is invalid, but shorthand completion never invents a target.
                    if(field.startsWith(en+" to earn, ,")||field.startsWith(cn+"以赚取, ,"))invalid=true;
                    continue;
                }}
                count++;
                Integer n=number(matcher.group(1)),d=number(matcher.group(2));
                if(n==null||d==null||d==0||n>d)invalid=true;else{current=n;target=d;}
            }
        }
        if(invalid)return result(Status.INVALID,0,0,count);
        if(count>1)return result(Status.AMBIGUOUS,0,0,count);
        if(count==0)return result(Status.UNKNOWN,0,0,0);
        return result(Status.KNOWN,current,target,1);
    }
    private static Integer number(String token){
        if(token.length()>5||!UINT.matcher(token).matches())return null;
        try{int value=Integer.parseInt(token);return value<=10000?value:null;}catch(NumberFormatException invalid){return null;}
    }
    private static TaskResult result(Status status,int current,int target,int count){return new TaskResult(status,current,target,count,status==Status.KNOWN?Unit.EXPLICIT_TEXT_POINTS:Unit.UNESTABLISHED);}
}
