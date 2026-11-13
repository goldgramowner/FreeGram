package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * FreeGram REST client. Call network methods from a worker thread.
 * Telegram MTProto is intentionally not used for FreeGram sessions.
 */
public final class FreeGramApi {
    public static final String BASE_URL = "https://freegram-x.onrender.com";
    private static final String PREFS = "freegram_session";
    private static final String TOKEN = "encrypted_access_token";
    private static final String KEY_ALIAS = "freegram_session_key";
    private static volatile Context appContext;

    private FreeGramApi() {}

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public static JSONObject verifyCode(Context context, String virtualId, String code) throws Exception {
        JSONObject body = new JSONObject();
        body.put("virtual_id", virtualId == null ? "" : virtualId.trim());
        body.put("code", code == null ? "" : code.trim());
        JSONObject response = request("POST", "/v1/auth/verify", body, null);
        String token = response.optString("access_token", "");
        if (token.isEmpty()) throw new Exception("API did not return an access token");
        saveToken(context, token);
        return response;
    }

    public static JSONObject getMe(Context context) throws Exception {
        return request("GET", "/v1/me", null, getToken(context));
    }

    public static JSONObject getChats(Context context) throws Exception {
        return request("GET", "/v1/chats", null, getToken(context));
    }

    public static JSONObject getMessages(Context context, long chatId, long afterId) throws Exception {
        return request("GET", "/v1/chats/" + chatId + "/messages?after_id=" + afterId,
                null, getToken(context));
    }

    public static JSONObject sendMessage(Context context, long chatId, String message) throws Exception {
        JSONObject body = new JSONObject();
        body.put("text", message == null ? "" : message);
        return request("POST", "/v1/chats/" + chatId + "/messages", body, getToken(context));
    }

    public static void logout(Context context) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(TOKEN).apply();
    }

    public static boolean hasSession(Context context) {
        return !getToken(context).isEmpty();
    }

    private static JSONObject request(String method, String path, JSONObject body, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        if (token != null && !token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (stream != null) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            stream.close();
        }
        connection.disconnect();
        String responseText = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        JSONObject result = responseText.isEmpty() ? new JSONObject() : new JSONObject(responseText);
        if (status < 200 || status >= 300) {
            String detail = result.optString("detail", "HTTP " + status);
            throw new Exception(detail);
        }
        return result;
    }

    private static void saveToken(Context context, String token) throws Exception {
        byte[] iv;
        byte[] encrypted;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        iv = cipher.getIV();
        encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        String packed = Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(TOKEN, packed).apply();
    }

    private static String getToken(Context context) {
        try {
            String packed = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN, "");
            if (packed == null || packed.isEmpty()) return "";
            String[] parts = packed.split(":", 2);
            if (parts.length != 2) return "";
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
