package com.zhaozhan.wxchatgptbridge;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** 接入启用时，微插自动回复使用 ChatGPT，其他功能仍走原来的选择器。 */
final class AutoReplyRouter {
    private static final String SELECTOR = "b.\u2071\u1d54.\u02c6\u1d4e";
    private static final String OPENAI = "b.\u1427\u2071.\u0674\u0640";
    private static final Set<Class<?>> INSTALLED =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<Class<?>, Map<String, Object>> BOTS = new IdentityHashMap<>();

    private AutoReplyRouter() {}

    static synchronized void install(ClassLoader loader) {
        Class<?> selector = XposedHelpers.findClassIfExists(SELECTOR, loader);
        Class<?> bot = XposedHelpers.findClassIfExists(OPENAI, loader);
        if (selector == null || bot == null || selector.getClassLoader() != loader) return;
        if (!INSTALLED.add(selector)) return;
        try {
            XposedHelpers.findAndHookMethod(selector, "\u02ca\uff9e", String.class,
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()
                            || !BridgeRules.isAutoReplyCaller(Thread.currentThread().getStackTrace())) return;
                    try {
                        Application app = AndroidAppHelper.currentApplication();
                        if (app == null) throw new IllegalStateException("微信尚未准备就绪");
                        Bundle status = app.getContentResolver().call(WechatHook.PROVIDER, "status", null, null);
                        if (status == null) throw new IllegalStateException("接入服务无响应");
                        if (!status.getBoolean("enabled", false)) return;
                        String profile = param.args[0] instanceof String ? (String) param.args[0] : "";
                        param.setResult(botFor(bot, profile));
                        Log.i("WxGPTBridge", "autoreply_provider_routed_to_chatgpt");
                    } catch (Throwable ignored) {
                        // 不让接入故障将自动回复意外发给先前配置的其他机器人。
                        param.setResult(null);
                        Log.w("WxGPTBridge", "autoreply_provider_unavailable");
                    }
                }
            });
            Log.i("WxGPTBridge", "autoreply_provider_hook_installed");
        } catch (Throwable ignored) {
            INSTALLED.remove(selector);
            Log.w("WxGPTBridge", "autoreply_provider_hook_unavailable");
        }
        try {
            Class<?> reply = XposedHelpers.findClass(BridgeRules.AUTOREPLY_CLASS, loader);
            Class<?> task = XposedHelpers.findClass("b.\u02c8\u02c8.\u02ca\u02cf$\u2071\u02be", loader);
            XposedHelpers.findAndHookMethod(reply, "\u02d1\u0674", task, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Application app = AndroidAppHelper.currentApplication();
                        if (app == null) throw new IllegalStateException("微信尚未准备就绪");
                        Bundle status = app.getContentResolver().call(WechatHook.PROVIDER, "status", null, null);
                        if (status == null) throw new IllegalStateException("接入服务无响应");
                        if (!status.getBoolean("enabled", false)) return;
                        Object job = param.args[0];
                        if (job == null) return;
                        String profile = (String) XposedHelpers.getObjectField(job, "\u1d54\u02be");
                        String talker = (String) XposedHelpers.getObjectField(job, "\u0674\u0640");
                        String content = (String) XposedHelpers.getObjectField(job, "\u2071\u02be");
                        String sender = (String) XposedHelpers.getObjectField(job, "\u02ca\uff9e");
                        String extra = (String) XposedHelpers.getObjectField(job, "\uff9e\u1427");
                        Object previous = XposedHelpers.getObjectField(job, "\u02c6\u0674");
                        if (previous != null && Proxy.isProxyClass(previous.getClass())) return;
                        Object selected = botFor(bot, profile == null ? "" : profile);
                        param.args[0] = XposedHelpers.newInstance(task, selected, profile, talker,
                                content, sender, extra);
                        Log.i("WxGPTBridge", "autoreply_dispatch_routed previous_openai=" + bot.isInstance(previous));
                    } catch (Throwable ignored) {
                        // 接入不可用时不回退到旧机器人或密钥。
                        param.setResult(null);
                        Log.w("WxGPTBridge", "autoreply_dispatch_unavailable");
                    }
                }
            });
            Log.i("WxGPTBridge", "autoreply_dispatch_hook_installed");
        } catch (Throwable ignored) {
            Log.w("WxGPTBridge", "autoreply_dispatch_hook_unavailable");
        }
    }

    private static synchronized Object botFor(Class<?> type, String profile) {
        Map<String, Object> byProfile = BOTS.get(type);
        if (byProfile == null) {
            byProfile = new HashMap<>();
            BOTS.put(type, byProfile);
        }
        Object existing = byProfile.get(profile);
        if (existing != null) return existing;
        Object bot = XposedHelpers.newInstance(type, "");
        XposedHelpers.setObjectField(bot, "\u0674\u0640", profile.isEmpty() ? "" : profile + "_");
        byProfile.put(profile, bot);
        return bot;
    }
}
