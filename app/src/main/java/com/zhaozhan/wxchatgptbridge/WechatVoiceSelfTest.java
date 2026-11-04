package com.zhaozhan.wxchatgptbridge;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 只对程序自带的合成语音进行检查，不读取微信聊天记录。 */
final class WechatVoiceSelfTest {
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean();
    private static final String TAG = "WxGPTBridge";
    private WechatVoiceSelfTest() {}

    static void scheduleIfRequested(ClassLoader loader) {
        if (!SCHEDULED.compareAndSet(false, true)) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Thread t = new Thread(() -> runIfRequested(loader), "wechat-voice-selftest");
            t.setDaemon(true);
            t.start();
        }, 8000);
    }

    private static void runIfRequested(ClassLoader loader) {
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return;
        try {
            Bundle claim = app.getContentResolver().call(WechatHook.PROVIDER, "claim_wechat_voice_test", null, null);
            if (claim == null || !claim.getBoolean("run", false)) return;
        } catch (Throwable ignored) { return; }

        final AtomicReference<File> file = new AtomicReference<>();
        final AtomicReference<XC_MethodHook.Unhook> hook = new AtomicReference<>();
        final AtomicBoolean cleaned = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) return;
            XC_MethodHook.Unhook installed = hook.getAndSet(null);
            if (installed != null) try { installed.unhook(); } catch (Throwable ignored) {}
            File own = file.getAndSet(null);
            if (own != null) try { own.delete(); } catch (Throwable ignored) {}
        };
        String status = "failed";
        boolean waitingForWeChat = false;
        try {
            String name = "wxgpttest_" + UUID.randomUUID().toString().replace("-", "");
            File sample = File.createTempFile("wxgpt-synthetic-", ".silk", app.getCacheDir());
            file.set(sample);
            try (InputStream input = app.getPackageManager().getResourcesForApplication(
                    "com.zhaozhan.wxchatgptbridge").getAssets().open("voice-selftest.silk");
                 FileOutputStream output = new FileOutputStream(sample)) {
                byte[] block = new byte[8192];
                int n, total = 0;
                while ((n = input.read(block)) != -1) {
                    total += n;
                    if (total > 100_000) throw new java.io.IOException("测试音频过大");
                    output.write(block, 0, n);
                }
            }
            Class<?> voiceLogic = XposedHelpers.findClass("cm0.v0", app.getClassLoader());
            Method getVoicePath = voiceLogic.getDeclaredMethod("m", String.class);
            hook.set(XposedBridge.hookMethod(getVoicePath, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 1 && name.equals(param.args[0]))
                        param.setResult(sample.getAbsolutePath());
                }
            }));
            status = MediaRouter.testSyntheticVoiceDispatch(loader, name, cleanup) ? "ok" : "unexpected";
        } catch (Throwable e) {
            if (e instanceof java.io.IOException && "微信语音转写超时".equals(e.getMessage())) {
                status = "timeout";
                waitingForWeChat = true;
            }
        } finally {
            if (!waitingForWeChat) new Handler(Looper.getMainLooper()).postDelayed(cleanup, 1000);
            try {
                Bundle result = new Bundle();
                result.putString("status", status);
                boolean pdfProbe = PdfLoader.probe(loader) && PdfMessage.isPdf(
                        "<msg><appmsg><type>6</type><appattach><fileext>pdf</fileext></appattach></appmsg></msg>");
                result.putString("pdf_probe", pdfProbe ? "ok" : "failed");
                result.putString("pdf_route", testSyntheticPdf(app, loader) ? "ok" : "failed");
                result.putString("file_route", testSyntheticFile(app, loader, "txt", false) ? "ok" : "failed");
                result.putString("gif_route", testSyntheticFile(app, loader, "gif", false) ? "ok" : "failed");
                result.putString("sticker_route", testSyntheticFile(app, loader, "gif", true) ? "ok" : "failed");
                app.getContentResolver().call(WechatHook.PROVIDER, "finish_wechat_voice_test", null, result);
            } catch (Throwable ignored) {}
            Log.i(TAG, "wechat_voice_selftest_" + status);
        }
    }

    private static boolean testSyntheticPdf(Application app, ClassLoader loader) {
        File sample = null;
        try {
            sample = File.createTempFile("wxgpt-synthetic-pdf-", ".pdf", app.getCacheDir());
            android.graphics.pdf.PdfDocument pdf = new android.graphics.pdf.PdfDocument();
            android.graphics.pdf.PdfDocument.Page page = pdf.startPage(
                    new android.graphics.pdf.PdfDocument.PageInfo.Builder(200, 150, 1).create());
            page.getCanvas().drawColor(android.graphics.Color.WHITE);
            android.graphics.Paint paint = new android.graphics.Paint(); paint.setTextSize(18);
            page.getCanvas().drawText("Synthetic PDF", 20, 50, paint);
            pdf.finishPage(page);
            try (FileOutputStream output = new FileOutputStream(sample)) { pdf.writeTo(output); }
            finally { pdf.close(); }
            return MediaRouter.testSyntheticPdfDispatch(loader, sample);
        } catch (Throwable ignored) { return false; }
        finally { if (sample != null) try { sample.delete(); } catch (Throwable ignored) {} }
    }

    private static boolean testSyntheticFile(Application app, ClassLoader loader, String extension, boolean sticker) {
        File sample = null;
        try {
            sample = File.createTempFile("wxgpt-synthetic-", "." + extension, app.getCacheDir());
            try (FileOutputStream output = new FileOutputStream(sample)) {
                if ("txt".equals(extension)) output.write("Synthetic file test".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                else try (InputStream input = app.getPackageManager().getResourcesForApplication(
                        "com.zhaozhan.wxchatgptbridge").getAssets().open("image-selftest.gif")) {
                    byte[] block = new byte[8192]; int n;
                    while ((n = input.read(block)) != -1) output.write(block, 0, n);
                }
            }
            return MediaRouter.testSyntheticFileDispatch(loader, sample, extension, sticker);
        } catch (Throwable ignored) { return false; }
        finally { if (sample != null) try { sample.delete(); } catch (Throwable ignored) {} }
    }
}
