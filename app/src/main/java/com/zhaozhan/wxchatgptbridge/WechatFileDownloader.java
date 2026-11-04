package com.zhaozhan.wxchatgptbridge;

import android.app.Application;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;

/** 调用当前微信版本原有的附件初始化和下载队列。 */
final class WechatFileDownloader {
    private static final ThreadLocal<Boolean> HIDE_XML = new ThreadLocal<>();
    private static boolean guarded;

    private WechatFileDownloader() {}

    private static synchronized boolean installPrivacyGuard(ClassLoader loader) {
        if (guarded) return true;
        try {
            Class<?> logger = XposedHelpers.findClass("com.tencent.mm.sdk.platformtools.k2", loader);
            XC_MethodHook filter = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(HIDE_XML.get())
                            && ("MicroMsg.AppMsgLogic".equals(param.args[0])
                                || "MicroMsg.NetSceneDownloadAppAttach".equals(param.args[0])))
                        param.setResult(null);
                }
            };
            XposedBridge.hookMethod(XposedHelpers.findMethodExact(logger, "j",
                    String.class, String.class, Object[].class), filter);
            XposedBridge.hookMethod(XposedHelpers.findMethodExact(logger, "n",
                    String.class, Throwable.class, String.class, Object[].class), filter);
            guarded = true;
            return true;
        } catch (Throwable ignored) { return false; }
    }

    /** 元数据未就绪时返回 false，调用方继续等待。 */
    static boolean startIfReady(Application app, MediaRouter.ImageRef ref, int maxBytes) {
        String xml = PdfLoader.completeMessage(ref, maxBytes);
        if (xml == null) return false;
        if (PdfLoader.hasAttachment(ref.localId, ref.loader)) return true;
        ClassLoader loader = app.getClassLoader();
        if (!installPrivacyGuard(loader)) {
            Log.w("WxGPTBridge", "file_downloader_privacy_guard_unavailable");
            return true;
        }
        try {
            Class<?> manager = XposedHelpers.findClass("com.tencent.mm.pluginsdk.model.app.u0", loader);
            Method initialize = XposedHelpers.findMethodExact(manager, "n", long.class, String.class, String.class);
            String mediaId;
            HIDE_XML.set(true);
            try { mediaId = (String) initialize.invoke(null, ref.localId, xml, null); }
            finally { HIDE_XML.remove(); }
            if (mediaId == null || mediaId.isEmpty()) {
                Log.w("WxGPTBridge", "file_downloader_metadata_unavailable");
                return true;
            }
            Class<?> sceneClass = XposedHelpers.findClass("f13.j0", loader);
            Class<?> callbackClass = XposedHelpers.findClass("com.tencent.mm.modelbase.t0", loader);
            Object scene = XposedHelpers.findConstructorExact(sceneClass, long.class, String.class, callbackClass)
                    .newInstance(ref.localId, mediaId, null);
            // 微信自身会用名称、长度和 MD5 校验已下载副本，避免重复转发时依赖失效的 CDN 地址。
            boolean reused = false;
            try {
                HIDE_XML.set(true);
                try { reused = Boolean.TRUE.equals(XposedHelpers.callMethod(scene, "R")); }
                finally { HIDE_XML.remove(); }
            } catch (Throwable ignored) { }
            if (reused) {
                Log.i("WxGPTBridge", "file_native_reused_verified_copy");
                return true;
            }
            Class<?> service = XposedHelpers.findClass("tj0.h8", loader);
            Object queue = XposedHelpers.callStaticMethod(service, "e");
            XposedHelpers.callMethod(queue, "f", scene);
            Log.i("WxGPTBridge", "file_native_download_started");
        } catch (Throwable ignored) {
            Log.w("WxGPTBridge", "file_native_download_failed");
        }
        return true;
    }
}
