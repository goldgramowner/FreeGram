package org.telegram.ui;

import android.content.Context;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

/**
 * Compatibility facade for older FreeGram UI fragments.
 * All screens now use the same encrypted session created by FreeGramLoginActivity.
 */
public final class FreeGramApi {
    public static final String BASE = org.telegram.messenger.FreeGramApi.BASE_URL;

    private FreeGramApi() {}

    private static Context context() {
        return ApplicationLoader.applicationContext;
    }

    public static boolean ensureDeviceSession() {
        try {
            Context context = context();
            if (context == null) return false;
            org.telegram.messenger.FreeGramApi.validateSession(context);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static JSONObject request(String method, String path, JSONObject body,
                                     boolean authenticated) throws Exception {
        Context context = context();
        if (context == null) throw new Exception("FreeGram ещё не инициализирован");
        return org.telegram.messenger.FreeGramApi.requestApi(
                context, method, path, body, authenticated);
    }
}
