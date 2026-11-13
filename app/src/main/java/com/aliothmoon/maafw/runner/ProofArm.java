package com.aliothmoon.maafw.runner;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;

/** App-UID half of the one-shot, read-only card proof handshake. */
public final class ProofArm {
    private static final String PEER = "com.aliothmoon.maafw.offlinepageprobev2";
    private static final String AUTHORITY = PEER + ".cardproof";
    private ProofArm() {}

    public static String arm(Context context, String task) {
        if (context == null || !("ReadingCheck".equals(task) || "SearchCheck".equals(task))) return null;
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo peer = pm.getPackageInfo(PEER, PackageManager.GET_SIGNING_CERTIFICATES);
            ProviderInfo provider = pm.resolveContentProvider(AUTHORITY, 0);
            if (peer.getLongVersionCode() != 36 || !"0.0.0-proof-bridge".equals(peer.versionName)
                    || pm.checkSignatures(PEER, context.getPackageName()) != PackageManager.SIGNATURE_MATCH
                    || provider == null || !provider.exported || !PEER.equals(provider.packageName)
                    || !"com.aliothmoon.maafw.pageprobe.CardProofProvider".equals(provider.name)) return null;
            Bundle input = new Bundle();
            input.putString("task", task);
            Bundle output = context.getContentResolver().call(Uri.parse("content://" + AUTHORITY), "arm", null, input);
            String token = output == null ? null : output.getString("token");
            return token != null && token.matches("[0-9a-f]{64}") ? token : null;
        } catch (RuntimeException | PackageManager.NameNotFoundException failure) {
            return null;
        }
    }
}
