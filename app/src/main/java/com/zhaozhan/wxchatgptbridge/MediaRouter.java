package com.zhaozhan.wxchatgptbridge;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.ContentValues;
import android.os.Bundle;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.IOException;
import java.lang.reflect.Member;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** 仅把通过微插原始过滤链的图片和语音交给聊天机器人。 */
final class MediaRouter {
    private static final String TAG = "WxGPTBridge";
    private static final String EVENT = "b.\u02d1\u0674.\u02c6\u0674";
    private static final ThreadLocal<ImageRef> CURRENT = new ThreadLocal<>();
    private static final Set<Class<?>> INSTALLED =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<String, ImageRef> PENDING = new HashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long TTL_MS = 180_000;
    private static final ThreadPoolExecutor IMAGE_QUEUE = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "wechat-image-autoreply");
                thread.setDaemon(true);
                return thread;
            });
    private static final ThreadPoolExecutor VOICE_QUEUE = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "wechat-voice-autoreply");
                thread.setDaemon(true);
                return thread;
            });
    private static final ThreadPoolExecutor PDF_QUEUE = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "wechat-pdf-autoreply");
                thread.setDaemon(true);
                return thread;
            });
    private static final ThreadPoolExecutor STICKER_QUEUE = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "wechat-sticker-autoreply");
                thread.setDaemon(true);
                return thread;
            });

    static final class Payload {
        final byte[] image, pdf;
        final String extension;
        Payload(byte[] image, byte[] pdf) { this(image, pdf, "pdf"); }
        Payload(byte[] image, byte[] pdf, String extension) {
            this.image = image; this.pdf = pdf; this.extension = extension;
        }
    }

    static final class ImageRef {
        final int type;
        final long localId, serverId, created, expectedBytes;
        final String imagePath, talker, extension;
        final ClassLoader loader;
        final byte[] preparedJpeg;
        final boolean synthetic;
        final Runnable syntheticCleanup, syntheticFailure;
        ImageRef(int type, long localId, long serverId, String imagePath, String talker, ClassLoader loader) {
            this(type, localId, serverId, imagePath, talker, loader, false, null, null);
        }
        ImageRef(int type, long localId, long serverId, String imagePath, String talker,
                 ClassLoader loader, String extension) {
            this(type, localId, serverId, imagePath, talker, loader, false, null, null, extension, -1);
        }
        ImageRef(int type, long localId, long serverId, String imagePath, String talker,
                 ClassLoader loader, String extension, long expectedBytes) {
            this(type, localId, serverId, imagePath, talker, loader, false, null, null, extension, expectedBytes);
        }
        ImageRef(int type, long localId, long serverId, String imagePath, String talker, ClassLoader loader,
                 boolean synthetic, Runnable syntheticCleanup, Runnable syntheticFailure) {
            this(type, localId, serverId, imagePath, talker, loader, synthetic, syntheticCleanup,
                    syntheticFailure, type == 49 ? "pdf" : "", -1);
        }
        ImageRef(int type, long localId, long serverId, String imagePath, String talker, ClassLoader loader,
                 boolean synthetic, Runnable syntheticCleanup, Runnable syntheticFailure, String extension,
                 long expectedBytes) {
            this(type, localId, serverId, imagePath, talker, loader, synthetic, syntheticCleanup,
                    syntheticFailure, extension, expectedBytes, null);
        }
        ImageRef(int type, long localId, long serverId, String imagePath, String talker, ClassLoader loader,
                 boolean synthetic, Runnable syntheticCleanup, Runnable syntheticFailure, String extension,
                 long expectedBytes, byte[] preparedJpeg) {
            this.type = type;
            this.localId = localId;
            this.serverId = serverId;
            this.imagePath = imagePath;
            this.talker = talker;
            this.extension = FileRules.normalized(extension);
            this.expectedBytes = expectedBytes;
            this.loader = loader;
            this.preparedJpeg = preparedJpeg;
            this.synthetic = synthetic;
            this.syntheticCleanup = syntheticCleanup;
            this.syntheticFailure = syntheticFailure;
            this.created = android.os.SystemClock.elapsedRealtime();
        }
    }

    private MediaRouter() {}

    static synchronized void install(ClassLoader loader) {
        try {
            Class<?> event = XposedHelpers.findClassIfExists(EVENT, loader);
            Class<?> reply = XposedHelpers.findClassIfExists(BridgeRules.AUTOREPLY_CLASS, loader);
            if (event == null || reply == null || event.getClassLoader() != loader
                    || reply.getClassLoader() != loader || !INSTALLED.add(event)) return;
            boolean dispatchInstalled = false;
            try {
                Class<?> task = XposedHelpers.findClass("b.\u02c8\u02c8.\u02ca\u02cf$\u2071\u02be", loader);
                XposedHelpers.findAndHookMethod(reply, "\u02d1\u0674", task, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object original = param.args[0];
                            if (original == null) return;
                            Object raw = XposedHelpers.getObjectField(original, "\u2071\u02be");
                            if (!(raw instanceof String)) return;
                            boolean voice = BridgeRules.isVoiceMarker((String) raw);
                            boolean pdf = BridgeRules.isPdfMarker((String) raw);
                            boolean file = BridgeRules.isFileMarker((String) raw);
                            boolean image = BridgeRules.isImageMarker((String) raw);
                            if (!voice && !pdf && !file && !image) return;
                            String talker = (String) XposedHelpers.getObjectField(original, "\u0674\u0640");
                            ImageRef ref;
                            synchronized (MediaRouter.class) {
                                expire();
                                ref = PENDING.get((String) raw);
                                if (ref == null || !ref.talker.equals(talker)) return;
                                if ((voice && ref.type != 34) || ((pdf || file) && ref.type != 49)
                                        || (image && ref.type != 3 && ref.type != 47 && ref.type != 49)) return;
                                PENDING.remove((String) raw);
                            }
                            param.setResult(null);
                            if (ref.type == 3) {
                                Member method = param.method;
                                Object owner = param.thisObject;
                                String marker = (String) raw;
                                IMAGE_QUEUE.execute(() -> WakeGuard.run(AndroidAppHelper.currentApplication(),
                                        () -> sendImageReply(ref, marker, method, owner, original)));
                                Log.i(TAG, "image_autoreply_queued");
                                return;
                            }
                            if (ref.type == 49) {
                                Member method = param.method;
                                Object owner = param.thisObject;
                                String marker = (String) raw;
                                PDF_QUEUE.execute(() -> WakeGuard.run(AndroidAppHelper.currentApplication(),
                                        () -> sendPdfReply(ref, marker, method, owner, original)));
                                Log.i(TAG, "file_autoreply_queued");
                                return;
                            }
                            if (ref.type == 47) {
                                Member method = param.method;
                                Object owner = param.thisObject;
                                String marker = (String) raw;
                                STICKER_QUEUE.execute(() -> WakeGuard.run(AndroidAppHelper.currentApplication(),
                                        () -> sendStickerReply(ref, marker, method, owner, original)));
                                Log.i(TAG, "sticker_autoreply_queued");
                                return;
                            }
                            Object bot = XposedHelpers.getObjectField(original, "\u02c6\u0674");
                            String profile = (String) XposedHelpers.getObjectField(original, "\u1d54\u02be");
                            String sender = (String) XposedHelpers.getObjectField(original, "\u02ca\uff9e");
                            String extra = (String) XposedHelpers.getObjectField(original, "\uff9e\u1427");
                            Member method = param.method;
                            Object owner = param.thisObject;
                            VOICE_QUEUE.execute(() -> WakeGuard.run(AndroidAppHelper.currentApplication(),
                                    () -> sendVoiceReply(ref, task, method, owner,
                                            bot, profile, talker, sender, extra)));
                            Log.i(TAG, "voice_autoreply_queued");
                        } catch (Throwable ignored) {
                            Log.w(TAG, "voice_autoreply_queue_failed");
                        }
                    }
                });
                dispatchInstalled = true;
                Log.i(TAG, "voice_dispatch_installed");
            } catch (Throwable ignored) {
                Log.w(TAG, "voice_dispatch_unavailable");
            }
            final boolean voiceReady = dispatchInstalled;
            XposedHelpers.findAndHookMethod(event, "\u02c6\u0674", Object[].class,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    param.setObjectExtra("previous_media", CURRENT.get());
                    CURRENT.remove();
                    try {
                        Object[] args = (Object[]) param.args[0];
                        if (args == null || args.length < 2 || !(args[1] instanceof ContentValues)) return;
                        ContentValues values = (ContentValues) args[1];
                        Integer type = values.getAsInteger("type");
                        Integer sent = values.getAsInteger("isSend");
                        Long local = values.getAsLong("msgId"), server = values.getAsLong("msgSvrId");
                        String path = values.getAsString("imgPath"), talker = values.getAsString("talker");
                        if (type != null && (type == 3 || type == 34) && sent != null && sent == 0
                                && local != null && local > 0 && server != null && server > 0
                                && path != null && !path.isEmpty() && talker != null && !talker.isEmpty()) {
                            CURRENT.set(new ImageRef(type, local, server, path, talker, loader));
                        } else if (type != null && BridgeRules.isFileMessageType(type) && sent != null && sent == 0
                                && local != null && local > 0 && server != null && server > 0
                                && talker != null && !talker.isEmpty()) {
                            PdfMessage.Metadata meta = PdfMessage.parse(values.getAsString("content"));
                            if (!meta.extension.isEmpty())
                                CURRENT.set(new ImageRef(49, local, server, "", talker, loader,
                                        meta.extension, meta.expectedBytes));
                        } else if (type != null && type == 47 && sent != null && sent == 0
                                && local != null && local > 0 && server != null && server > 0
                                && talker != null && !talker.isEmpty()) {
                            String hash = EmojiLoader.identity(path, values.getAsString("content"));
                            if (!hash.isEmpty()) CURRENT.set(new ImageRef(type, local, server, hash, talker, loader));
                        }
                    } catch (Throwable ignored) { CURRENT.remove(); }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object previous = param.getObjectExtra("previous_media");
                    if (previous instanceof ImageRef) CURRENT.set((ImageRef) previous);
                    else CURRENT.remove();
                }
            });
            XposedHelpers.findAndHookMethod(reply, "\u1427\u2071", int.class, int.class,
                    String.class, String.class, String.class, String.class, String.class,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        ImageRef image = CURRENT.get();
                        if (image == null) return;
                        if (!voiceReady) return;
                        int type = (Integer) param.args[0], sent = (Integer) param.args[1];
                        boolean valid = image.type == 3
                                ? BridgeRules.shouldRouteImage(type, sent, image.talker, (String) param.args[3],
                                        image.localId, image.serverId, image.imagePath, true, true)
                                : image.type == 34
                                  ? BridgeRules.shouldRouteVoice(type, sent, image.talker, (String) param.args[3],
                                          image.localId, image.serverId, image.imagePath, true, true)
                                   : ((image.type == 49 && (BridgeRules.isFileMessageType(type) || type == 1))
                                           || (image.type == 47 && type == 47)) && sent == 0
                                          && image.localId > 0 && image.serverId > 0
                                          && image.talker.equals(param.args[3]);
                        if (!valid) return;
                        Application app = AndroidAppHelper.currentApplication();
                        if (app == null) return;
                        Bundle status = app.getContentResolver().call(WechatHook.PROVIDER, "status", null, null);
                        String feature = image.type == 3 ? "image_enabled"
                                : image.type == 34 ? "wechat_voice_enabled"
                                : image.type == 47 ? "sticker_enabled" : "pdf_enabled";
                        if (status == null || !status.getBoolean("enabled", false)
                                || !status.getBoolean(feature, false)) return;
                        if ((image.type == 47 || (image.type == 49 && FileRules.isImage(image.extension)))
                                && !status.getBoolean("image_enabled", false)) return;
                        if ((image.type == 47 || "gif".equals(image.extension))
                                && !status.getBoolean("sticker_enabled", false)) return;
                        String marker = remember(image);
                        param.args[0] = 1;
                        param.args[2] = marker;
                        Log.i(TAG, image.type == 3 ? "image_autoreply_prepared"
                                : image.type == 34 ? "voice_autoreply_prepared"
                                : image.type == 47 ? "sticker_autoreply_prepared" : "pdf_autoreply_prepared");
                    } catch (Throwable ignored) {
                        Log.w(TAG, "image_autoreply_prepare_failed");
                    }
                }
            });
            Log.i(TAG, "image_router_installed");
        } catch (Throwable ignored) {
            Log.w(TAG, "image_router_unavailable");
        }
    }

    private static synchronized String remember(ImageRef image) {
        expire();
        if (PENDING.size() >= 32) throw new IllegalStateException("媒体队列已满");
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        StringBuilder token = new StringBuilder(32);
        for (byte b : random) token.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        String marker = (image.type == 3 || image.type == 47 || (image.type == 49 && FileRules.isImage(image.extension))
                ? BridgeRules.IMAGE_PREFIX : image.type == 34 ? BridgeRules.VOICE_PREFIX
                : "pdf".equals(image.extension) ? BridgeRules.PDF_PREFIX : BridgeRules.FILE_PREFIX) + token + "]";
        PENDING.put(marker, image);
        return marker;
    }

    private static synchronized void expire() {
        long now = android.os.SystemClock.elapsedRealtime();
        Iterator<ImageRef> items = PENDING.values().iterator();
        while (items.hasNext()) if (now - items.next().created > TTL_MS) items.remove();
    }

    private static void sendVoiceReply(ImageRef ref, Class<?> task, Member method, Object owner,
            Object bot, String profile, String talker, String sender, String extra) {
        try {
            String transcript;
            if (ref.synthetic) transcript = WechatVoiceTranscriber.transcribeSynthetic(ref.imagePath, ref.syntheticCleanup);
            else {
                transcript = WechatVoiceTranscriber.transcribe(ref);
                Application app = AndroidAppHelper.currentApplication();
                if (app == null) return;
                Bundle status = app.getContentResolver().call(WechatHook.PROVIDER, "status", null, null);
                if (status == null || !status.getBoolean("enabled", false)
                        || !status.getBoolean("wechat_voice_enabled", false)) return;
            }
            String content = "对方发来一条语音，转写内容如下：" + transcript;
            Object replacement = XposedHelpers.newInstance(task, bot, profile, talker, content, sender, extra);
            XposedBridge.invokeOriginalMethod(method, owner, new Object[]{replacement});
            Log.i(TAG, "voice_autoreply_transcribed");
        } catch (Throwable ignored) {
            Log.w(TAG, "voice_autoreply_failed");
            if (ref.syntheticFailure != null) ref.syntheticFailure.run();
        }
    }

    private static boolean enabled(Application app, String feature) {
        Bundle status = app.getContentResolver().call(WechatHook.PROVIDER, "status", null, null);
        return status != null && status.getBoolean("enabled", false) && status.getBoolean(feature, false);
    }

    private static void sendImageReply(ImageRef ref, String marker, Member method, Object owner, Object job) {
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app == null || !enabled(app, "image_enabled")) return;
            byte[] jpeg = ImageLoader.load(ref);
            if (!enabled(app, "image_enabled")) return;
            ImageRef ready = new ImageRef(3, ref.localId, ref.serverId, ref.imagePath, ref.talker,
                    ref.loader, false, null, null, "", -1, jpeg);
            synchronized (MediaRouter.class) { expire(); PENDING.put(marker, ready); }
            XposedBridge.invokeOriginalMethod(method, owner, new Object[]{job});
            Log.i(TAG, "image_autoreply_ready");
        } catch (Throwable ignored) {
            Log.w(TAG, "image_download_or_decode_failed");
            Application app = AndroidAppHelper.currentApplication();
            if (app != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    android.widget.Toast.makeText(app, "图片等待下载后仍无法读取，请打开原图后重试", android.widget.Toast.LENGTH_LONG).show());
        }
    }

    private static void sendPdfReply(ImageRef ref, String marker, Member method, Object owner, Object job) {
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app == null) return;
            java.io.File pdf;
            if (ref.synthetic) pdf = new java.io.File(ref.imagePath);
            else {
                Bundle status = app.getContentResolver().call(WechatHook.PROVIDER, "status", null, null);
                if (status == null || !status.getBoolean("enabled", false) || !status.getBoolean("pdf_enabled", false)) return;
                if (FileRules.isImage(ref.extension) && !status.getBoolean("image_enabled", false)) return;
                if ("gif".equals(ref.extension) && !status.getBoolean("sticker_enabled", false)) return;
                int maxBytes = FileRules.maxBytes(status.getInt("file_max_mb", FileRules.DEFAULT_MB));
                if (ref.expectedBytes > maxBytes) { Log.i(TAG, "file_exceeds_limit"); return; }
                PdfLoader.logState(app, ref.localId, ref.loader, ref.extension, maxBytes, ref.expectedBytes);
                AtomicBoolean downloadRequested = new AtomicBoolean();
                pdf = MediaWait.untilReady(() -> {
                    java.io.File candidate = PdfLoader.find(app, ref.localId, ref.loader, ref.extension, maxBytes);
                    if (candidate == null && !downloadRequested.get())
                        downloadRequested.set(WechatFileDownloader.startIfReady(app, ref, maxBytes));
                    return candidate != null && (ref.expectedBytes <= 0 || candidate.length() == ref.expectedBytes)
                            ? candidate : null;
                }, 75_000, 1000, MediaWait.REAL_CLOCK);
                if (!enabled(app, "pdf_enabled")) return;
            }
            if (pdf == null || (ref.expectedBytes > 0 && pdf.length() != ref.expectedBytes)) {
                if (!ref.synthetic) PdfLoader.logState(app, ref.localId, ref.loader, ref.extension,
                        FileRules.maxBytes(FileRules.MAX_MB), ref.expectedBytes);
                Log.i(TAG, "file_not_downloaded_or_unsupported"); return;
            }
            ImageRef ready = new ImageRef(49, ref.localId, ref.serverId, pdf.getPath(), ref.talker,
                    ref.loader, ref.extension);
            synchronized (MediaRouter.class) { expire(); PENDING.put(marker, ready); }
            XposedBridge.invokeOriginalMethod(method, owner, new Object[]{job});
            Log.i(TAG, "file_autoreply_ready");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Throwable ignored) { Log.w(TAG, "file_autoreply_failed"); }
    }

    private static void sendStickerReply(ImageRef ref, String marker, Member method, Object owner, Object job) {
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app == null) return;
            Bundle status = app.getContentResolver().call(WechatHook.PROVIDER, "status", null, null);
            if (status == null || !status.getBoolean("enabled", false)
                    || !status.getBoolean("image_enabled", false) || !status.getBoolean("sticker_enabled", false)) return;
            AtomicBoolean requested = new AtomicBoolean();
            byte[] source = ref.synthetic ? ImageLoader.readCandidate(ref.imagePath, ref.loader)
                    : MediaWait.untilReady(() -> WechatEmojiCache.load(app, ref.imagePath, ref.loader,
                            requested),
                            60_000, 1000, MediaWait.REAL_CLOCK);
            if (source == null) { Log.i(TAG, "sticker_not_downloaded_or_unsupported"); return; }
            if (!enabled(app, "sticker_enabled") || !enabled(app, "image_enabled")) return;
            byte[] jpeg = ImageLoader.encode(source);
            ImageRef ready = new ImageRef(47, ref.localId, ref.serverId, ref.imagePath, ref.talker,
                    ref.loader, false, null, null, "", -1, jpeg);
            synchronized (MediaRouter.class) { expire(); PENDING.put(marker, ready); }
            XposedBridge.invokeOriginalMethod(method, owner, new Object[]{job});
            Log.i(TAG, "sticker_autoreply_ready");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Throwable ignored) { Log.w(TAG, "sticker_autoreply_failed"); }
    }

    /** 用临时合成 PDF 和虚拟机器人检查文件队列，不向微信联系人发送消息。 */
    static boolean testSyntheticPdfDispatch(ClassLoader loader, java.io.File file) throws Exception {
        return testSyntheticFileDispatch(loader, file, "pdf", false);
    }

    static boolean testSyntheticFileDispatch(ClassLoader loader, java.io.File file,
            String extension, boolean sticker) throws Exception {
        if (!sticker && !PdfLoader.isComplete(file, extension, FileRules.maxBytes(FileRules.DEFAULT_MB)))
            throw new IOException("测试文件未通过完整性检查");
        Class<?> reply = XposedHelpers.findClass(BridgeRules.AUTOREPLY_CLASS, loader);
        Class<?> task = XposedHelpers.findClass("b.\u02c8\u02c8.\u02ca\u02cf$\u2071\u02be", loader);
        Class<?> botInterface = task.getDeclaredConstructors()[0].getParameterTypes()[0];
        if (!botInterface.isInterface()) throw new IOException("测试机器人接口不可用");
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean correct = new AtomicBoolean();
        Object bot = Proxy.newProxyInstance(loader, new Class<?>[]{botInterface}, (proxy, method, args) -> {
            if ("\u02c6\u0674".equals(method.getName()) && args != null && args.length > 0) {
                try {
                    String body = new JSONObject().put("messages", new JSONArray().put(
                            new JSONObject().put("role", "user").put("content", args[0]))).toString();
                    Payload payload = resolveForBody(body);
                    boolean image = sticker || FileRules.isImage(extension);
                    correct.set(payload != null && (image
                            ? payload.image != null && payload.image.length > 5
                            : payload.pdf != null && payload.pdf.length > 5));
                } catch (Throwable ignored) { correct.set(false); }
                done.countDown();
            }
            return null;
        });
        Object owner = XposedHelpers.newInstance(reply, new Object[]{null});
        String talker = "wxgpt-selftest";
        ImageRef ref = new ImageRef(sticker ? 47 : 49, 1, 1, file.getCanonicalPath(), talker, loader,
                true, null, done::countDown, extension, -1);
        String marker = remember(ref);
        Object job = XposedHelpers.newInstance(task, bot, "", talker, marker, "", "");
        XposedHelpers.callMethod(owner, "\u02d1\u0674", job);
        return done.await(15, TimeUnit.SECONDS) && correct.get();
    }

    /** 用虚拟机器人接收合成语音的转写，不向任何微信会话发送消息。 */
    static boolean testSyntheticVoiceDispatch(ClassLoader loader, String name, Runnable cleanup) throws Exception {
        Class<?> reply = XposedHelpers.findClass(BridgeRules.AUTOREPLY_CLASS, loader);
        Class<?> task = XposedHelpers.findClass("b.\u02c8\u02c8.\u02ca\u02cf$\u2071\u02be", loader);
        Constructor<?> taskConstructor = task.getDeclaredConstructors()[0];
        Class<?> botInterface = taskConstructor.getParameterTypes()[0];
        if (!botInterface.isInterface()) throw new IOException("测试机器人接口不可用");
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean correct = new AtomicBoolean();
        Object bot = Proxy.newProxyInstance(loader, new Class<?>[]{botInterface}, (proxy, method, args) -> {
            if ("\u02c6\u0674".equals(method.getName()) && args != null && args.length > 0) {
                correct.set(args[0] instanceof String && ((String) args[0]).replace(" ", "").contains("天气"));
                done.countDown();
            }
            return null;
        });
        Object owner = XposedHelpers.newInstance(reply, new Object[]{null});
        String talker = "wxgpt-selftest";
        ImageRef ref = new ImageRef(34, 1, 1, name, talker, loader, true, cleanup, done::countDown);
        String marker = remember(ref);
        Object job = XposedHelpers.newInstance(task, bot, "", talker, marker, "", "");
        XposedHelpers.callMethod(owner, "\u02d1\u0674", job);
        if (!done.await(65, TimeUnit.SECONDS)) throw new IOException("微信语音转写超时");
        return correct.get();
    }

    static Payload resolveForBody(String body) throws IOException {
        try {
            JSONArray messages = new JSONObject(body).getJSONArray("messages");
            if (messages.length() == 0) return null;
            JSONObject last = messages.getJSONObject(messages.length() - 1);
            if (!"user".equals(last.optString("role"))) return null;
            Object content = last.opt("content");
            if (!(content instanceof String)) return null;
            boolean isImage = BridgeRules.isImageMarker((String) content);
            boolean isPdf = BridgeRules.isPdfMarker((String) content);
            boolean isFile = BridgeRules.isFileMarker((String) content);
            if (!isImage && !isPdf && !isFile) return null;
            ImageRef image;
            synchronized (MediaRouter.class) {
                expire();
                image = PENDING.remove((String) content);
            }
            if (image == null || (isImage && image.type != 3 && image.type != 47 && image.type != 49)
                    || ((isPdf || isFile) && image.type != 49))
                throw new IOException("媒体消息已过期或不可用");
            if (isImage) {
                byte[] jpeg;
                if (image.type == 3) {
                    if (image.preparedJpeg == null) throw new IOException("图片尚未准备完成");
                    jpeg = image.preparedJpeg;
                }
                else if (image.type == 47) {
                    if (image.preparedJpeg == null) throw new IOException("表情包不可用");
                    jpeg = image.preparedJpeg;
                } else {
                    Application app = AndroidAppHelper.currentApplication();
                    if (app == null) throw new IOException("微信尚未准备就绪");
                    Bundle status = app.getContentResolver().call(WechatHook.PROVIDER, "status", null, null);
                    if (status == null) throw new IOException("接入服务不可用");
                    int maxBytes = FileRules.maxBytes(status.getInt("file_max_mb", FileRules.DEFAULT_MB));
                    byte[] source = PdfLoader.read(app, new java.io.File(image.imagePath), image.extension, maxBytes);
                    jpeg = ImageLoader.encode(source);
                }
                Log.i(TAG, "image_payload_ready");
                return new Payload(jpeg, null);
            }
            Application app = AndroidAppHelper.currentApplication();
            if (app == null) throw new IOException("微信尚未准备就绪");
            Bundle status = app.getContentResolver().call(WechatHook.PROVIDER, "status", null, null);
            if (status == null) throw new IOException("接入服务不可用");
            int maxBytes = FileRules.maxBytes(status.getInt("file_max_mb", FileRules.DEFAULT_MB));
            byte[] bytes = PdfLoader.read(app, new java.io.File(image.imagePath), image.extension, maxBytes);
            Log.i(TAG, "file_payload_ready");
            return new Payload(null, bytes, image.extension);
        } catch (IOException e) {
            Log.w(TAG, "media_payload_unavailable");
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "media_payload_unavailable");
            throw new IOException("媒体处理失败");
        }
    }
}
