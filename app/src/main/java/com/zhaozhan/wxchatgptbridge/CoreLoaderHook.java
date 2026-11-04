package com.zhaozhan.wxchatgptbridge;

import android.util.Log;
import dalvik.system.InMemoryDexClassLoader;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.nio.ByteBuffer;

/** 微插运行时解密加载核心时，只识别其 DEX 并安装选择器。 */
final class CoreLoaderHook {
    private CoreLoaderHook() {}

    static void install() {
        XposedBridge.hookAllConstructors(InMemoryDexClassLoader.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.hasThrowable() || param.args.length == 0) return;
                    Object input = param.args[0];
                    boolean core = input instanceof ByteBuffer && BridgeRules.isCoreDex((ByteBuffer) input);
                    if (input instanceof ByteBuffer[])
                        for (ByteBuffer part : (ByteBuffer[]) input) core |= BridgeRules.isCoreDex(part);
                    if (core) {
                        AutoReplyRouter.install((ClassLoader) param.thisObject);
                        MediaDiagnostics.install((ClassLoader) param.thisObject);
                        MediaRouter.install((ClassLoader) param.thisObject);
                        WechatVoiceSelfTest.scheduleIfRequested((ClassLoader) param.thisObject);
                    }
                } catch (Throwable ignored) {
                    Log.w("WxGPTBridge", "core_loader_probe_unavailable");
                }
            }
        });
    }
}
