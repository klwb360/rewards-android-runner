package com.aliothmoon.maafw.once;
import java.util.regex.*;
/** Fixed-format parser for a shell-owned window-manager snapshot. No log output.
 * This is window identity, not publisher authentication or continuous pixel stability. */
final class BingForegroundSnapshot {
    private static final Pattern DISPLAY = Pattern.compile("(?m)^\\s*mTopFocusedDisplayId=(\\d+)\\s*$");
    private static final Pattern FOCUS = Pattern.compile("(?m)^\\s*mCurrentFocus=Window\\{([a-fA-F0-9]+) u0 (com\\.microsoft\\.bing/[^\\s}]+)\\}\\s*$");
    private static final Pattern APP = Pattern.compile("(?m)^\\s*mFocusedApp=ActivityRecord\\{([a-fA-F0-9]+) u0 (com\\.microsoft\\.bing/[^\\s}]+) t([0-9]+)(?:[^}]*?)\\}\\s*$");
    record Snapshot(String window, String activity, String component, String task) {}
    static Snapshot parse(String text) {
        if (text == null || text.length() > 262144 || text.indexOf('\0') >= 0) return null;
        Matcher d=DISPLAY.matcher(text), w=FOCUS.matcher(text), a=APP.matcher(text);
        if(!d.find() || !d.group(1).equals("0") || d.find() || !w.find()) return null;
        String window=w.group(1), component=w.group(2);
        if(w.find() || !a.find()) return null;
        String activity=a.group(1), app=a.group(2), task=a.group(3);
        if(a.find() || !component.equals(app)) return null;
        // A null or second focus record must never disappear due to a strict regex.
        if(count(text,"mCurrentFocus=")!=1 || count(text,"mFocusedApp=")!=1) return null;
        return new Snapshot(window,activity,component,task);
    }
    private static int count(String s,String token){int n=0,p=0;while((p=s.indexOf(token,p))>=0){n++;p+=token.length();}return n;}
    private BingForegroundSnapshot(){}
}
