package com.zhaozhan.wxchatgptbridge;

import android.app.Application;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/** 使用微信现有表情记录和解密能力读取缓存。 */
final class WechatEmojiCache {
    private static final int MAX_BYTES = 12_000_000;
    private static final ThreadLocal<Boolean> PRIVATE_READ = new ThreadLocal<>();
    private static boolean guarded;
    private WechatEmojiCache() {}

    /** 避免微信的表情解密诊断把缓存路径或标识写入其日志。 */
    private static synchronized boolean installPrivacyGuard(ClassLoader loader) {
        if (guarded) return true;
        try {
            Class<?> logger = XposedHelpers.findClass("com.tencent.mm.sdk.platformtools.k2", loader);
            XC_MethodHook filter = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(PRIVATE_READ.get()) && param.args.length > 0
                            && ("MicroMsg.emoji.EmojiInfo".equals(param.args[0])
                                || "MicroMsg.emoji.EmojiFileEncryptMgr".equals(param.args[0])
                                || "MicroMsg.EmojiLoader".equals(param.args[0])))
                        param.setResult(null);
                }
            };
            XposedBridge.hookMethod(XposedHelpers.findMethodExact(logger, "j",
                    String.class, String.class, Object[].class), filter);
            XposedBridge.hookMethod(XposedHelpers.findMethodExact(logger, "q",
                    String.class, String.class, Object[].class), filter);
            XposedBridge.hookMethod(XposedHelpers.findMethodExact(logger, "n",
                    String.class, Throwable.class, String.class, Object[].class), filter);
            guarded = true;
            return true;
        } catch (Throwable ignored) { return false; }
    }

    static byte[] load(Application app, String identity, ClassLoader loader,
                       AtomicBoolean requested) {
        if (app == null || identity == null || !identity.equals(EmojiLoader.identity(identity, null))) return null;
        ClassLoader wechat = app.getClassLoader();
        if (installPrivacyGuard(wechat)) {
            try {
                PRIVATE_READ.set(true);
                Class<?> storageClass = XposedHelpers.findClass("com.tencent.mm.storage.x5", wechat);
                Object storage = XposedHelpers.callMethod(XposedHelpers.callStaticMethod(storageClass, "i"), "e");
                Object emoji = XposedHelpers.callMethod(storage, "W2", identity);
                if (emoji != null && identity.equalsIgnoreCase(
                        (String) XposedHelpers.callMethod(emoji, "getMd5"))) {
                    int catalog = XposedHelpers.getIntField(emoji, "field_catalog");
                    if (catalog == 17 || catalog == 18 || catalog == 49 || catalog == 50) {
                        try {
                            Object bitmap = XposedHelpers.callMethod(emoji, "P", app, 256);
                            if (bitmap instanceof android.graphics.Bitmap) {
                                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                                if (((android.graphics.Bitmap) bitmap).compress(
                                        android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                                        && output.size() > 0 && output.size() <= MAX_BYTES) {
                                    Log.i("WxGPTBridge", "sticker_builtin_asset_ready");
                                    return output.toByteArray();
                                }
                            }
                        } catch (Throwable ignored) { }
                    }
                    int expectedBytes = XposedHelpers.getIntField(emoji, "field_size");
                    if (expectedBytes > MAX_BYTES) return null;
                    String path = (String) XposedHelpers.callMethod(emoji, "T0");
                    Class<?> vfs = XposedHelpers.findClass("com.tencent.mm.vfs.p6", wechat);
                    long size = ((Number) XposedHelpers.callStaticMethod(vfs, "l", path)).longValue();
                    if (size <= 0 && requested.compareAndSet(false, true)) {
                        try {
                            Class<?> emojiLoader = XposedHelpers.findClass("km.h", wechat);
                            Object manager = XposedHelpers.getStaticObjectField(emojiLoader, "a");
                            XposedHelpers.callMethod(manager, "f", emoji, true, null);
                            Log.i("WxGPTBridge", "sticker_native_download_started");
                        } catch (Throwable ignored) {
                            Log.w("WxGPTBridge", "sticker_native_download_unavailable");
                        }
                    }
                    if (size > 0 && size <= MAX_BYTES) {
                        Class<?> decoder = XposedHelpers.findClass("hm.a", wechat);
                        byte[] data = (byte[]) XposedHelpers.callMethod(
                                XposedHelpers.callStaticMethod(decoder, "f"), "c", emoji);
                        if (data != null && data.length > 0 && data.length <= MAX_BYTES) return data;
                    }
                }
            } catch (Throwable ignored) {
                // 不兼容的微信版本继续尝试普通缓存路径。
            } finally { PRIVATE_READ.remove(); }
        }
        File file = EmojiLoader.find(app, identity, loader);
        return file == null ? null : ImageLoader.readCandidate(file.getPath(), loader);
    }
}
