package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class FreeGramApi {
    public static final String BASE = "https://freegram-x.onrender.com";
    private static final String PREFS = "freegram_session";
    private static final String TOKEN = "access_token";

    private FreeGramApi() {}

    private static SharedPreferences prefs() {
        Context c = ApplicationLoader.applicationContext;
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean hasSession() {
        return !prefs().getString(TOKEN, "").isEmpty();
    }

    public static String token() {
        return prefs().getString(TOKEN, "");
    }

    public static void saveToken(String token) {
        prefs().edit().putString(TOKEN, token).apply();
    }

    public static void clearToken() {
        prefs().edit().remove(TOKEN).apply();
    }

    public static JSONObject request(String method, String path, JSONObject body, boolean authenticated) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
        }
        if (authenticated) connection.setRequestProperty("Authorization", "Bearer " + token());
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder result = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null) result.append(line);
            }
        }
        connection.disconnect();
        JSONObject json = new JSONObject(result.toString().isEmpty() ? "{}" : result.toString());
        if (code < 200 || code >= 300) throw new Exception(json.optString("detail", "FreeGram API error " + code));
        return json;
    }
}
