package com.whisper.wowaudio;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureApiKeyStore {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "wow_audio_gemini_api_key_v1";
    private static final String PREFS = "secure_api_keys";
    private static final String KEY_IV = "gemini_iv";
    private static final String KEY_DATA = "gemini_data";

    private SecureApiKeyStore() { }

    static void saveGeminiKey(Context context, String apiKey) throws Exception {
        String clean = apiKey == null ? "" : apiKey.trim();
        if (clean.isEmpty()) {
            clearGeminiKey(context);
            return;
        }

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();

        prefs(context).edit()
                .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(KEY_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply();
    }

    static String getGeminiKey(Context context) {
        try {
            String iv64 = prefs(context).getString(KEY_IV, null);
            String data64 = prefs(context).getString(KEY_DATA, null);
            if (iv64 == null || data64 == null) return "";

            KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
            store.load(null);
            java.security.Key key = store.getKey(ALIAS, null);
            if (!(key instanceof SecretKey)) return "";

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, (SecretKey) key,
                    new GCMParameterSpec(128, Base64.decode(iv64, Base64.NO_WRAP)));
            byte[] plain = cipher.doFinal(Base64.decode(data64, Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    static boolean hasGeminiKey(Context context) {
        return !getGeminiKey(context).isEmpty();
    }

    static void clearGeminiKey(Context context) {
        prefs(context).edit().remove(KEY_IV).remove(KEY_DATA).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
        store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
