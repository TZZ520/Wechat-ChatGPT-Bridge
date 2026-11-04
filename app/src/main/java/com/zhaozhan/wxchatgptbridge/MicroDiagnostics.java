package com.zhaozhan.wxchatgptbridge;

import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 仅记录自动回复的处理阶段和开关结果，不读取或记录聊天内容。 */
final class MicroDiagnostics {
    private static final String TAG = "WxGPTBridge";
    private static final String REPLY = "b.\u02c8\u02c8.\u02ca\u02cf";
    private static final String OPENAI = "b.\u1427\u2071.\u0674\u0640";
    private static final String SELECTOR = "b.\u2071\u1d54.\u02c6\u1d4e";
    private static final String CONTACTS = "b.\u2071\u1d54.\u02bb";
    private static final Set<Class<?>> INSTALLED =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final ThreadLocal<Boolean> IN_EVENT = new ThreadLocal<>();

    private MicroDiagnostics() {}

    static void installWithLoader(ClassLoader loader) {
        for (String name : new String[] { REPLY, OPENAI, SELECTOR, CONTACTS }) {
            try {
                Class<?> type = XposedHelpers.findClassIfExists(name, loader);
                if (type != null) installFor(type, name);
            } catch (Throwable ignored) {
                Log.w(TAG, "diagnostic_type_probe_unavailable");
            }
        }
    }

    static void install() {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class,
                    boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String name = (String) param.args[0];
                        if (!REPLY.equals(name) && !OPENAI.equals(name)) return;
                        Object loaded = param.getResult();
                        if (!(loaded instanceof Class<?>)) return;
                        installFor((Class<?>) loaded, name);
                    } catch (Throwable ignored) {
                        Log.w(TAG, "diagnostic_class_probe_unavailable");
                    }
                }
            });
        } catch (Throwable ignored) {
            Log.w(TAG, "diagnostic_loader_probe_unavailable");
        }
    }

    private static synchronized void installFor(Class<?> type, String name) {
        if (!INSTALLED.add(type)) return;
        if (REPLY.equals(name)) {
            try {
                XposedHelpers.findAndHookMethod(type, "\u1427\u2071", int.class, int.class,
                        String.class, String.class, String.class, String.class, String.class,
                        new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        IN_EVENT.set(Boolean.TRUE);
                        Log.i(TAG, "autoreply_event_received");
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        IN_EVENT.remove();
                    }
                });
                XposedHelpers.findAndHookMethod(type, "\u02bb\u02d1", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(IN_EVENT.get()) && !param.hasThrowable()) {
                            Log.i(TAG, "autoreply_master_ready=" + Boolean.TRUE.equals(param.getResult()));
                            try {
                                Log.i(TAG, "autoreply_runtime_active="
                                        + XposedHelpers.getBooleanField(param.thisObject, "\u02be\u1d62")
                                        + " listener_ready="
                                        + XposedHelpers.getBooleanField(param.thisObject, "\u02c6\u02bf"));
                            } catch (Throwable ignored) {}
                        }
                    }
                });
                XposedHelpers.findAndHookMethod(type, "\u02be\u1d62", String.class,
                        new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(IN_EVENT.get()) && !param.hasThrowable())
                            Log.i(TAG, "autoreply_scope_ready=" + Boolean.TRUE.equals(param.getResult()));
                    }
                });
                XposedBridge.hookAllMethods(type, "\u02c8\u02ca", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Log.i(TAG, "autoreply_robot_selected="
                                + (param.args.length > 0 && param.args[0] != null));
                    }
                });
                XposedHelpers.findAndHookMethod(type, "\u0674\u0640", String.class, String.class,
                        new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!param.hasThrowable())
                            Log.i(TAG, "autoreply_limit_allowed=" + Boolean.TRUE.equals(param.getResult()));
                    }
                });
                Log.i(TAG, "diagnostic_reply_probe_installed");
            } catch (Throwable ignored) {
                Log.w(TAG, "diagnostic_reply_probe_unavailable");
            }
        } else if (OPENAI.equals(name)) {
            try {
                XposedBridge.hookAllMethods(type, "\u02c6\u0674", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Log.i(TAG, "openai_bot_invoked");
                    }
                });
                Log.i(TAG, "diagnostic_openai_probe_installed");
            } catch (Throwable ignored) {
                Log.w(TAG, "diagnostic_openai_probe_unavailable");
            }
        } else if (SELECTOR.equals(name)) {
            try {
                XposedHelpers.findAndHookMethod(type, "\u02ca\uff9e", String.class,
                        new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable()) return;
                        Object selected = param.getResult();
                        Log.i(TAG, "autoreply_provider=" + (selected == null ? "none"
                                : OPENAI.equals(selected.getClass().getName()) ? "openai"
                                : "b.\u1427\u2071.\u02c6\u0674".equals(selected.getClass().getName())
                                        ? "chatgpt_for_bot" : "other"));
                        try {
                            String profile = param.args[0] instanceof String ? (String) param.args[0] : "";
                            Object configured = XposedHelpers.callStaticMethod(type, "\u02be\u1d62", profile);
                            Log.i(TAG, "autoreply_profile=" + (profile.isEmpty() ? "general" : "custom")
                                    + " configured=" + ("openai".equals(configured) ? "openai"
                                    : "chatgpt_for_bot".equals(configured) ? "chatgpt_for_bot" : "other"));
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable ignored) {
                Log.w(TAG, "diagnostic_provider_probe_unavailable");
            }
        } else if (CONTACTS.equals(name)) {
            try {
                XposedHelpers.findAndHookMethod(type, "\u0640\u02cb", String.class, String.class,
                        new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(IN_EVENT.get()) && !param.hasThrowable())
                            Log.i(TAG, "autoreply_sender_allowed=" + Boolean.TRUE.equals(param.getResult()));
                    }
                });
            } catch (Throwable ignored) {
                Log.w(TAG, "diagnostic_contact_probe_unavailable");
            }
        }
    }
}
