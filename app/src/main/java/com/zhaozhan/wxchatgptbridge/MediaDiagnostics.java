package com.zhaozhan.wxchatgptbridge;

import android.content.ContentValues;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 只检查图片和语音事件的字段是否存在，不记录消息、联系人、路径或标识。 */
final class MediaDiagnostics {
    private static final String TAG = "WxGPTBridge";
    private static final String EVENT = "b.\u02d1\u0674.\u02c6\u0674";
    private static final Set<Class<?>> INSTALLED =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private MediaDiagnostics() {}

    static synchronized void install(ClassLoader loader) {
        try {
            Class<?> event = XposedHelpers.findClassIfExists(EVENT, loader);
            if (event != null && event.getClassLoader() == loader && INSTALLED.add(event)) {
                XposedHelpers.findAndHookMethod(event, "\u02c6\u0674", Object[].class,
                        new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object[] args = (Object[]) param.args[0];
                            if (args == null || args.length < 2 || !(args[1] instanceof ContentValues)) return;
                            ContentValues values = (ContentValues) args[1];
                            Integer type = values.getAsInteger("type");
                            if (type == null || (type != 3 && type != 34
                                    && !BridgeRules.isFileMessageType(type))) return;
                            Long local = values.getAsLong("msgId");
                            Long server = values.getAsLong("msgSvrId");
                            String path = values.getAsString("imgPath");
                            Integer sent = values.getAsInteger("isSend");
                            boolean file = BridgeRules.isFileMessageType(type);
                            String content = file ? values.getAsString("content") : null;
                            Log.i(TAG, "media_event type=" + (type == 3 ? "image" : type == 34 ? "voice" : "file")
                                    + " local_id=" + (local != null && local > 0)
                                    + " server_id=" + (server != null && server > 0)
                                    + " media_path=" + (path != null && !path.isEmpty())
                                    + " incoming=" + (sent != null && sent == 0)
                                    + (file ? " supported=" + !PdfMessage.parse(content).extension.isEmpty()
                                            + " extended_type=" + (type != 49) : ""));
                        } catch (Throwable ignored) {
                            Log.w(TAG, "media_event_probe_unavailable");
                        }
                    }
                });
                Log.i(TAG, "media_event_probe_installed");
            }
        } catch (Throwable ignored) {
            Log.w(TAG, "media_event_probe_unavailable");
        }
        try {
            Class<?> reply = XposedHelpers.findClassIfExists(BridgeRules.AUTOREPLY_CLASS, loader);
            if (reply != null && reply.getClassLoader() == loader && INSTALLED.add(reply)) {
                XposedHelpers.findAndHookMethod(reply, "\u1427\u2071", int.class, int.class,
                        String.class, String.class, String.class, String.class, String.class,
                        new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        int type = (Integer) param.args[0];
                        if (type == 3 || type == 34 || BridgeRules.isFileMessageType(type))
                            Log.i(TAG, "media_autoreply_received type="
                                    + (type == 3 ? "image" : type == 34 ? "voice" : "file"));
                    }
                });
                XposedHelpers.findAndHookMethod(reply, "\u0674\u0640", String.class, String.class,
                        new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.FALSE.equals(param.getResult())) Log.i(TAG, "autoreply_count_limit_reached");
                    }
                });
            }
        } catch (Throwable ignored) {
            Log.w(TAG, "media_autoreply_probe_unavailable");
        }
    }

}
