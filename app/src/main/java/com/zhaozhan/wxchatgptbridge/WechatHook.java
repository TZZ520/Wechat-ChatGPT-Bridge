package com.zhaozhan.wxchatgptbridge;

import android.app.Application;
import android.app.AndroidAppHelper;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.IOException;
import java.net.Proxy;
import java.net.URL;

public final class WechatHook implements IXposedHookLoadPackage {
    static final Uri PROVIDER = Uri.parse("content://com.zhaozhan.wxchatgptbridge.provider");
    private static final String TAG = "WxGPTBridge";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam info) {
        if (!"com.tencent.mm".equals(info.packageName)
                || !"com.tencent.mm".equals(info.processName)) return;
        try { CoreLoaderHook.install(); }
        catch (Throwable ignored) { Log.w(TAG, "core_loader_hook_unavailable"); }
        try { SettingsEntry.install(); }
        catch (Throwable ignored) { Log.w(TAG, "settings_entry_hook_unavailable"); }
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                URL url = (URL) param.thisObject;
                String host = url.getHost(), path = url.getPath();
                boolean openAiHost = "api.openai.com".equalsIgnoreCase(host);
                boolean chatPath = "/v1/chat/completions".equals(path);
                if (!openAiHost && (path == null || !path.contains("/chat/"))) return;
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                if (!BridgeRules.isMicroNetwork(stack)) return;
                boolean matched = BridgeRules.isMicroChatRequest(url, stack);
                // 仅记录路由阶段与布尔结果，不记录网址、消息或凭据。
                Log.i(TAG, "micro_network openai_host=" + openAiHost
                        + " chat_path=" + chatPath + " matched=" + matched);
                if (!matched) return;
                try {
                    Application app = AndroidAppHelper.currentApplication();
                    if (app == null) throw new IOException("微信尚未准备就绪");
                    Bundle status = app.getContentResolver().call(PROVIDER, "status", null, null);
                    if (status == null) throw new IOException("接入服务无响应");
                    if (status.getBoolean("enabled", false)) {
                        Log.i(TAG, "micro_request routed_to_bridge");
                        param.setResult(new MicroConnection(url, app));
                    } else {
                        Log.i(TAG, "micro_request bridge_disabled");
                    }
                } catch (Throwable ignored) {
                    // 服务不可用时停止当前机器人请求，避免意外使用旧 API Key。
                    Log.w(TAG, "micro_request bridge_unavailable");
                    param.setThrowable(new IOException("ChatGPT 接入服务暂不可用"));
                }
            }
        };
        XposedHelpers.findAndHookMethod(URL.class, "openConnection", hook);
        XposedHelpers.findAndHookMethod(URL.class, "openConnection", Proxy.class, hook);
    }
}
