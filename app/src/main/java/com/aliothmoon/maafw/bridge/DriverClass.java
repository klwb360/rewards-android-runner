package com.aliothmoon.maafw.bridge;

/** Read-only diagnostic: keep the native ABI but reject every input or app-control upcall. */
public final class DriverClass {
    private DriverClass() {}
    public static boolean startApp(String packageName, int displayId, boolean forceStop) { return false; }
    public static boolean stopApp(String packageName, int displayId) { return false; }
    public static boolean inputText(byte[] utf8, int displayId) { return false; }
    public static boolean touchDown(int x, int y, int contact, int displayId) { return false; }
    public static boolean touchMove(int x, int y, int contact, int displayId) { return false; }
    public static boolean touchUp(int x, int y, int contact, int displayId) { return false; }
    public static boolean keyDown(int keyCode, int displayId) { return false; }
    public static boolean keyUp(int keyCode, int displayId) { return false; }
}
