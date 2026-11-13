package com.aliothmoon.maafw.once;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;

/** Static Android wiring contracts only, not Android lifecycle execution. */
public final class ForegroundAndroidWiringContractTest {
    private static final Path ROOT=root();
    private static Path root(){
        Path p=Path.of(System.getProperty("staging.root",".")).toAbsolutePath().normalize();
        for(int i=0;i<4&&p!=null;i++,p=p.getParent())
            if(Files.isRegularFile(p.resolve("capture-review/src/main/AndroidManifest.xml")))return p;
        throw new IllegalStateException("Project source root required for wiring contract");
    }
    private static String source(String name)throws Exception{return Files.readString(ROOT.resolve("app/src/main/java/com/aliothmoon/maafw/once/"+name));}
    @Test public void afterServiceIsPrivateSameProcessAndAddsNoPermission()throws Exception{
        var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
        var doc=f.newDocumentBuilder().parse(ROOT.resolve("capture-review/src/main/AndroidManifest.xml").toFile());
        String android="http://schemas.android.com/apk/res/android";int found=0;
        var services=doc.getElementsByTagName("service");
        for(int i=0;i<services.getLength();i++){
            var e=(org.w3c.dom.Element)services.item(i);
            if(e.getAttributeNS(android,"name").equals(".once.DormantProjectionForegroundService$After")){
                found++;assertEquals("false",e.getAttributeNS(android,"exported"));assertEquals(":capture_once",e.getAttributeNS(android,"process"));
                assertEquals("mediaProjection",e.getAttributeNS(android,"foregroundServiceType"));assertEquals("true",e.getAttributeNS(android,"stopWithTask"));
            }
        }
        assertEquals(1,found);
        var permissions=new java.util.HashSet<String>();var nodes=doc.getElementsByTagName("uses-permission");
        for(int i=0;i<nodes.getLength();i++){String name=((org.w3c.dom.Element)nodes.item(i)).getAttributeNS(android,"name");if(!name.isEmpty())permissions.add(name);}
        assertEquals(java.util.Set.of("moe.shizuku.manager.permission.API_V23","android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"),permissions);
    }
    @Test public void runtimeRoutesBothTrustedAfterPhasesAndKeepsCheckinDisabled()throws Exception{
        String runtime=source("CaptureSessionRuntime.java");
        assertTrue(runtime.contains("cardPhase==NativeRewardCardSession.Phase.AFTER||nativePhase==NativeCheckInScope.Phase.AFTER"));
        assertTrue(runtime.contains("?ForegroundPhaseSlots.Phase.AFTER:ForegroundPhaseSlots.Phase.BEFORE"));
        assertTrue(runtime.contains("v16 verification-only build: account input route disabled"));
    }
    @Test public void serviceUsesExactComponentsNoExtrasAndMainCannotAwaitOwnDestruction()throws Exception{
        String service=source("DormantProjectionForegroundService.java");
        assertTrue(service.contains("getClass()==DormantProjectionForegroundService.class"));assertTrue(service.contains("getClass()==After.class"));
        assertFalse(service.contains("putExtra"));assertFalse(service.contains("getExtras"));assertFalse(service.contains("startSpent=false"));
        int method=service.indexOf("static void finishAndAwait");
        assertTrue(service.indexOf("Looper.myLooper()==Looper.getMainLooper()",method)<service.indexOf("SLOTS.awaitClosed",method));
        int destroy=service.indexOf("@Override public void onDestroy");
        assertTrue(service.indexOf("super.onDestroy()",destroy)<service.indexOf("SLOTS.destroyed(finished,this)",destroy));
        assertTrue(service.indexOf("pending.ports.serviceDestroyed(this)",destroy)<service.indexOf("SLOTS.destroyed(finished,this)",destroy));
        assertFalse(service.substring(destroy).contains("awaitClosed"));
    }
    @Test public void portsDoNotTreatStopRequestAsCompletionOrEraseUnknownPending()throws Exception{
        String ports=source("AndroidCaptureAdmissionPorts.java");
        assertTrue(ports.contains("DormantProjectionForegroundService.finishAndAwait(activity,foregroundStart)"));
        assertFalse(ports.contains("cancelPending("));assertFalse(ports.contains("activity.stopService("));
        assertTrue(ports.contains("ForegroundPhaseSlots.Phase.BEFORE"));
    }
}
