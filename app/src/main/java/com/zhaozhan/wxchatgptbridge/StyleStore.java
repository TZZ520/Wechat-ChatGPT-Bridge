package com.zhaozhan.wxchatgptbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 用户输入的提示和示例由独立密钥加密保存在本机应用私有存储。 */
final class StyleStore {
    private static final String ALIAS = "wx_chatgpt_bridge_reply_style_v1";
    private static final String DATA = "encrypted_reply_style";
    private static final String ENABLED = "reply_style_enabled";
    private static final String ERROR = "reply_style_error";
    private static final Object LOCK = new Object();
    private StyleStore() {}

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }

    static boolean enabled(Context context) {
        return SessionStore.prefs(context).getBoolean(ENABLED, false);
    }

    static String read(Context context) throws Exception {
        synchronized (LOCK) {
            String saved = SessionStore.prefs(context).getString(DATA, "");
            if (saved.isEmpty()) return "";
            byte[] packed = Base64.decode(saved, Base64.NO_WRAP);
            if (packed.length < 29) throw new IllegalStateException("风格设置无法读取");
            byte[] iv = Arrays.copyOfRange(packed, 0, 12);
            byte[] ciphertext = Arrays.copyOfRange(packed, 12, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return StyleRules.checked(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        }
    }

    static void save(Context context, String value, boolean enabled) throws Exception {
        String text = StyleRules.checked(value);
        if (enabled && text.isEmpty()) throw new IllegalArgumentException("请先填写风格说明或示例");
        synchronized (LOCK) {
            SharedPreferences.Editor editor = SessionStore.prefs(context).edit()
                    .putBoolean(ENABLED, enabled).remove(ERROR);
            if (text.isEmpty()) {
                editor.remove(DATA);
            } else {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key());
                byte[] iv = cipher.getIV();
                if (iv.length != 12) throw new IllegalStateException("无法保护风格设置");
                byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
                byte[] packed = new byte[iv.length + encrypted.length];
                System.arraycopy(iv, 0, packed, 0, iv.length);
                System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
                editor.putString(DATA, Base64.encodeToString(packed, Base64.NO_WRAP));
            }
            if (!editor.commit()) throw new IllegalStateException("无法保存风格设置");
        }
    }

    /** 读取失败时保持原自动回复可用，并在设置页提示风格暂未生效。 */
    static String active(Context context) {
        if (!enabled(context)) return "";
        try {
            String result = read(context);
            if (result.isEmpty()) throw new IllegalStateException("风格设置为空");
            SessionStore.prefs(context).edit().remove(ERROR).apply();
            return result;
        } catch (Exception e) {
            SessionStore.prefs(context).edit().putString(ERROR, "风格设置暂时无法读取，自动回复仍按原设置运行").apply();
            Log.w("WxGPTBridge", "reply_style_unavailable");
            return "";
        }
    }

    static String lastError(Context context) {
        return SessionStore.prefs(context).getString(ERROR, "");
    }
}
