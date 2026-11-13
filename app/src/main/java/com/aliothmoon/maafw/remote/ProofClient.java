package com.aliothmoon.maafw.remote;

import android.content.IContentProvider;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import com.aliothmoon.maafw.root.RootIContentProviderCompat;
import com.aliothmoon.maafw.third.wrappers.ServiceManager;

/** Shell-UID half of the one-shot proof exchange; never logs tokens or page content. */
public final class ProofClient {
    private static final String AUTHORITY = "com.aliothmoon.maafw.offlinepageprobev2.cardproof";
    private ProofClient() {}

    public static Bundle call(String method, String token, Bundle data) {
        if (method == null || token == null || !token.matches("[0-9a-f]{64}")) return null;
        Bundle input = data == null ? new Bundle() : new Bundle(data);
        input.putString("token", token);
        IBinder providerToken = new Binder();
        IContentProvider provider = null;
        try {
            provider = ServiceManager.getActivityManager().getContentProviderExternal(AUTHORITY, 0, providerToken, AUTHORITY);
            if (provider == null || !provider.asBinder().pingBinder()) return null;
            return RootIContentProviderCompat.call(provider, null, null, AUTHORITY, method, null, input);
        } catch (Throwable failure) {
            return null;
        } finally {
            if (provider != null) ServiceManager.getActivityManager().removeContentProviderExternal(AUTHORITY, providerToken);
        }
    }
}
