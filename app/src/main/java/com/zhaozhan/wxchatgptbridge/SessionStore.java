package com.zhaozhan.wxchatgptbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class SessionStore {
    static final Object LOCK = new Object();
    private static final String KEY_ALIAS = "wx_chatgpt_bridge_session_v1";
    private static final String PREFS = "bridge_private";
    private static final String SESSION = "encrypted_session";

    private SessionStore() {}

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String hostId(Context context) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            String id = p.getString("host_id", null);
            if (id == null) {
                id = "urn:uuid:" + UUID.randomUUID();
                if (!p.edit().putString("host_id", id).commit())
                    throw new IllegalStateException("无法保存设备标识");
            }
            return id;
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());
            gen.generateKey();
        }
        return (SecretKey) ks.getKey(KEY_ALIAS, null);
    }

    static JSONObject read(Context context) throws Exception {
        synchronized (LOCK) {
            String value = prefs(context).getString(SESSION, null);
            if (value == null) return null;
            byte[] packed = Base64.decode(value, Base64.NO_WRAP);
            if (packed.length < 29) throw new IllegalStateException("凭据记录损坏");
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, 12);
            byte[] ciphertext = java.util.Arrays.copyOfRange(packed, 12, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        }
    }

    static void save(Context context, JSONObject session) throws Exception {
        synchronized (LOCK) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            if (iv.length != 12) throw new IllegalStateException("无法保护凭据");
            byte[] encrypted = cipher.doFinal(session.toString().getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            if (!prefs(context).edit().putString(SESSION,
                    Base64.encodeToString(packed, Base64.NO_WRAP)).commit())
                throw new IllegalStateException("无法保存登录状态");
        }
    }

    static boolean enabled(Context context) {
        SharedPreferences p = prefs(context);
        return p.getBoolean("enabled", false) && p.contains(SESSION);
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            try {
                JSONObject session = read(context);
                if (session != null) {
                    JSONObject registration = new JSONObject();
                    registration.put("client_id", session.optString("client_id", ""));
                    registration.put("subject", session.optString("subject", ""));
                    registration.put("email", session.optString("email", ""));
                    save(context, registration);
                }
                if (!prefs(context).edit().remove("model_slug")
                        .putBoolean("enabled", false).commit())
                    throw new IllegalStateException("无法清除本地登录状态");
            } catch (Exception e) {
                prefs(context).edit().remove(SESSION).remove("model_slug")
                        .putBoolean("enabled", false).commit();
                throw new IllegalStateException("无法保留账号注册信息");
            }
        }
    }
}
