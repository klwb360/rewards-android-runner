package com.aliothmoon.maafw.once;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
/** Offline parser only. Input: BE int32 byte length + UTF-8 windows, then same for activities. No device calls. */
public final class RootReadOnlyMetadataRunner {
    public static void main(String[] args) throws Exception {
        if(args.length!=7)throw new IllegalArgumentException("width height rotation bingUid systemUiUid ownUid ownTaskId");
        byte[] windows=null,activities=null;
        try {
            DataInputStream input=new DataInputStream(System.in);
            windows=read(input);activities=read(input);if(input.read()!=-1)throw new IllegalArgumentException("trailing input");
            var shape=new BingNavigationSnapshot.Shape(Integer.parseInt(args[0]),Integer.parseInt(args[1]),Integer.parseInt(args[2]));
            var profile=new BingNavigationSnapshot.Profile(Integer.parseInt(args[3]),Integer.parseInt(args[4]),shape);
            var owner=new BingNavigationSnapshot.ReadOnlyGuard(Integer.parseInt(args[5]),Integer.parseInt(args[6]),
                "com.aliothmoon.maafw.productrewardsworking.capturereview/com.aliothmoon.maafw.once.DormantCaptureConfirmationActivity");
            var result=BingNavigationSnapshot.parseRootReadOnly(new String(windows,StandardCharsets.UTF_8),
                new String(activities,StandardCharsets.UTF_8),profile,owner,1000,1000);
            var gate=result.readOnlyGate()==BingNavigationSnapshot.ReadOnlyGate.NOT_APPLIED
                ?BingNavigationSnapshot.ReadOnlyGate.LEGACY_METADATA:result.readOnlyGate();
            System.out.println("status="+result.status()+" gate="+gate+" evidence=OFFLINE_METADATA_ONLY");
        } finally {if(windows!=null)Arrays.fill(windows,(byte)0);if(activities!=null)Arrays.fill(activities,(byte)0);}
    }
    private static byte[] read(DataInputStream input) throws Exception {
        int n=input.readInt();if(n<=0||n>262144)throw new IllegalArgumentException("metadata length");
        byte[] value=input.readNBytes(n);if(value.length!=n){Arrays.fill(value,(byte)0);throw new IllegalArgumentException("truncated input");}return value;
    }
    private RootReadOnlyMetadataRunner(){}
}