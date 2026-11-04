package com.zhaozhan.wxchatgptbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import java.io.IOException;

public final class BridgeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private void enforceCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        String[] names = getContext().getPackageManager().getPackagesForUid(uid);
        if (names != null) for (String name : names)
            if ("com.tencent.mm".equals(name)) return;
        throw new SecurityException("调用方未获授权");
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        enforceCaller();
        Bundle result = new Bundle();
        if ("style_selftest".equals(method) || "style_preview_selftest".equals(method)) {
            if (Binder.getCallingUid() != Process.myUid()) throw new SecurityException("调用方未获授权");
            try {
                boolean passed;
                if ("style_selftest".equals(method)) {
                    StyleDiagnostics.local(getContext());
                    passed = true;
                } else passed = StyleDiagnostics.preview(getContext());
                result.putString("result", passed ? "ok" : "failed");
            } catch (Exception e) {
                result.putString("result", "failed");
                result.putString("reason", e instanceof IOException && e.getMessage() != null
                        ? e.getMessage() : "风格自检失败");
            }
            return result;
        }
        if ("schedule_wechat_voice_test".equals(method)) {
            if (Binder.getCallingUid() != Process.myUid()) throw new SecurityException("调用方未获授权");
            synchronized (BridgeProvider.class) {
                SessionStore.prefs(getContext()).edit().putBoolean("wechat_voice_test_requested", true)
                        .putString("last_wechat_voice_test_status", "requested")
                        .putLong("last_wechat_voice_test_time", System.currentTimeMillis()).commit();
            }
            return result;
        }
        if ("claim_wechat_voice_test".equals(method)) {
            synchronized (BridgeProvider.class) {
                boolean requested = SessionStore.prefs(getContext()).getBoolean("wechat_voice_test_requested", false);
                if (requested) SessionStore.prefs(getContext()).edit()
                        .putBoolean("wechat_voice_test_requested", false)
                        .putString("last_wechat_voice_test_status", "running")
                        .putLong("last_wechat_voice_test_time", System.currentTimeMillis()).commit();
                result.putBoolean("run", requested);
            }
            return result;
        }
        if ("finish_wechat_voice_test".equals(method)) {
            String status = extras == null ? "" : extras.getString("status", "");
            if (!"ok".equals(status) && !"failed".equals(status) && !"unexpected".equals(status)
                    && !"timeout".equals(status)) throw new IllegalArgumentException("测试状态无效");
            String pdfProbe = extras == null ? "failed" : extras.getString("pdf_probe", "failed");
            if (!"ok".equals(pdfProbe) && !"failed".equals(pdfProbe)) throw new IllegalArgumentException("测试状态无效");
            String pdfRoute = extras == null ? "failed" : extras.getString("pdf_route", "failed");
            if (!"ok".equals(pdfRoute) && !"failed".equals(pdfRoute)) throw new IllegalArgumentException("测试状态无效");
            android.content.SharedPreferences.Editor editor = SessionStore.prefs(getContext()).edit()
                    .putString("last_pdf_db_test_status", pdfProbe)
                    .putString("last_pdf_route_test_status", pdfRoute)
                    .putString("last_wechat_voice_test_status", status)
                    .putLong("last_wechat_voice_test_time", System.currentTimeMillis());
            for (String name : new String[]{"file_route", "gif_route", "sticker_route"}) {
                if (extras != null && extras.containsKey(name)) {
                    String value = extras.getString(name, "failed");
                    if (!"ok".equals(value) && !"failed".equals(value)) throw new IllegalArgumentException("测试状态无效");
                    editor.putString("last_" + name + "_test_status", value);
                }
            }
            editor.commit();
            return result;
        }
        if ("status".equals(method)) {
            result.putBoolean("enabled", SessionStore.enabled(getContext()));
            result.putBoolean("image_enabled", SessionStore.prefs(getContext())
                    .getBoolean("image_enabled", false));
            result.putBoolean("wechat_voice_enabled", SessionStore.prefs(getContext())
                    .getBoolean("wechat_voice_enabled", false));
            result.putBoolean("pdf_enabled", SessionStore.prefs(getContext()).getBoolean("pdf_enabled", false));
            result.putBoolean("sticker_enabled", SessionStore.prefs(getContext()).getBoolean("sticker_enabled", true));
            result.putInt("file_max_mb", FileRules.megabytes(SessionStore.prefs(getContext())
                    .getInt("file_max_mb", FileRules.DEFAULT_MB)));
            return result;
        }
        if (!"request".equals(method)) throw new IllegalArgumentException("不支持的操作");
        if (!SessionStore.enabled(getContext())) {
            result.putInt("status", 403);
            result.putString("body", "{}");
            return result;
        }
        SessionStore.prefs(getContext()).edit().putLong("last_request_time", System.currentTimeMillis())
                .putString("last_request_status", "started").apply();
        android.os.PowerManager.WakeLock wakeLock = WakeGuard.acquire(getContext());
        try {
            if (extras == null) throw new IOException("缺少请求内容");
            byte[] image = extras.getByteArray("image_jpeg");
            ParcelFileDescriptor pdfInput = extras.getParcelable("pdf_file");
            if (image != null && pdfInput != null) throw new IOException("媒体消息类型不匹配");
            if (image != null && !SessionStore.prefs(getContext()).getBoolean("image_enabled", false))
                throw new IOException("图片识别已关闭");
            byte[] pdf = null;
            String extension = FileRules.normalized(extras.getString("file_extension", "pdf"));
            int maxBytes = FileRules.maxBytes(SessionStore.prefs(getContext())
                    .getInt("file_max_mb", FileRules.DEFAULT_MB));
            if (pdfInput != null) {
                try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pdfInput);
                     java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    if (!SessionStore.prefs(getContext()).getBoolean("pdf_enabled", false))
                        throw new IOException("文件识别已关闭");
                    if (!FileRules.isDocument(extension)) throw new IOException("文件格式不受支持");
                    byte[] block = new byte[16_384]; int n;
                    while ((n = input.read(block)) != -1) {
                        if (bytes.size() + n > maxBytes) throw new IOException("文件大小超过设置上限");
                        bytes.write(block, 0, n);
                    }
                    pdf = bytes.toByteArray();
                }
            }
            String output = ConversationMemory.reply(getContext(), extras.getString("body"), image, pdf,
                    extension, maxBytes);
            SessionStore.prefs(getContext()).edit().remove("last_error")
                    .putString("last_request_status", "ok").apply();
            result.putInt("status", 200);
            result.putString("body", output);
            android.util.Log.i("WxGPTBridge", "model_request_completed");
        } catch (Exception e) {
            String message = e instanceof IOException ? e.getMessage() : "接入服务处理失败";
            SessionStore.prefs(getContext()).edit().putString("last_error",
                    message == null ? "接入服务处理失败" : message)
                    .putString("last_request_status", "failed").apply();
            result.putInt("status", 503);
            result.putString("body", "{}");
            android.util.Log.w("WxGPTBridge", "model_request_failed");
            // 不将请求内容、登录凭据或服务端原始响应写入日志。
        } finally { WakeGuard.release(wakeLock); }
        return result;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
