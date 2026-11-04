package com.zhaozhan.wxchatgptbridge;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import de.robv.android.xposed.XposedHelpers;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 请求微信自带的语音转文字，不调用微信共享的音频编解码器。 */
final class WechatVoiceTranscriber {
    private static final String TAG = "WxGPTBridge";
    private static final String EVENT = "com.tencent.mm.autogen.events.ExtTranslateVoiceEvent";
    private static final long TIMEOUT_SECONDS = 55;

    private WechatVoiceTranscriber() {}

    static String transcribe(MediaRouter.ImageRef ref) throws IOException {
        if (ref == null || ref.type != 34 || ref.localId <= 0 || ref.imagePath == null || ref.imagePath.isEmpty())
            throw new IOException("语音消息信息不完整");
        return transcribeRequest(Long.toString(ref.localId), ref.imagePath,
                ref.talker.endsWith("@chatroom") ? 2 : 1, null);
    }

    static String transcribeSynthetic(String name, Runnable cleanup) throws IOException {
        if (name == null || !name.matches("wxgpttest_[0-9a-f]{32}")) throw new IOException("测试音频标识无效");
        return transcribeRequest("wxgpt-selftest", name, 8, cleanup);
    }

    private static String transcribeRequest(String id, String name, int scene, Runnable cleanup) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("不能在微信界面线程等待语音转写");
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) throw new IOException("微信尚未准备就绪");
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicReference<String> text = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicBoolean cleaned = new AtomicBoolean();
        Runnable finishCleanup = () -> {
            if (cleanup != null && cleaned.compareAndSet(false, true))
                new Handler(Looper.getMainLooper()).postDelayed(cleanup, 1000);
        };
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Class<?> type = XposedHelpers.findClass(EVENT, app.getClassLoader());
                Object event = XposedHelpers.newInstance(type);
                Object request = XposedHelpers.getObjectField(event, "g");
                XposedHelpers.setObjectField(request, "a", name);
                XposedHelpers.setObjectField(request, "b", id);
                XposedHelpers.setIntField(request, "c", 0);
                XposedHelpers.setIntField(request, "d", 0);
                XposedHelpers.setIntField(request, "f", scene);
                XposedHelpers.setObjectField(request, "e", (Runnable) () -> {
                    try {
                        Object result = XposedHelpers.getObjectField(event, "h");
                        if (!XposedHelpers.getBooleanField(result, "b")) return;
                        int status = XposedHelpers.getIntField(result, "c");
                        Object raw = XposedHelpers.getObjectField(result, "a");
                        if (status == 0 && raw instanceof String && !((String) raw).trim().isEmpty()) {
                            String output = ((String) raw).trim();
                            if (output.length() <= 20_000) text.set(output);
                            else failure.set("语音转写结果过长");
                        } else failure.set("微信未能完成语音转写");
                    } catch (Throwable ignored) {
                        failure.set("无法读取微信语音转写结果");
                    } finally {
                        // 部分结果并不结束等待；微信标记完成后才继续。
                        if (text.get() != null || failure.get() != null) {
                            if (terminal.compareAndSet(false, true)) done.countDown();
                            finishCleanup.run();
                        }
                    }
                });
                Log.i(TAG, "wechat_voice_transcription_started");
                Object published = XposedHelpers.callMethod(event, "d");
                if (!Boolean.TRUE.equals(published) && terminal.compareAndSet(false, true)) {
                    failure.set("微信语音转写正忙或暂不可用");
                    done.countDown();
                    finishCleanup.run();
                }
            } catch (Throwable ignored) {
                if (terminal.compareAndSet(false, true)) {
                    failure.set("微信语音转写接口不可用");
                    done.countDown();
                    finishCleanup.run();
                }
            }
        });
        try {
            if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                terminal.set(true);
                Log.w(TAG, "wechat_voice_transcription_timeout");
                throw new IOException("微信语音转写超时");
            }
        } catch (InterruptedException e) {
            terminal.set(true);
            Thread.currentThread().interrupt();
            throw new IOException("微信语音转写已中断");
        }
        String output = text.get();
        if (output == null || output.isEmpty()) {
            Log.w(TAG, "wechat_voice_transcription_failed");
            throw new IOException(failure.get() == null ? "微信语音转写失败" : failure.get());
        }
        return output;
    }
}
