package com.zhaozhan.wxchatgptbridge;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Movie;
import android.graphics.Paint;
import android.media.ExifInterface;
import de.robv.android.xposed.XposedHelpers;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

/** 使用微插已有的图片定位和下载能力，仅在内存中缩放编码。 */
final class ImageLoader {
    private static final String MEDIA = "b.\u02c6\u0674.\uff9e\u0674";
    private static final String DOWNLOAD = "b.\u2071\u1d54.\u0640\u0640";
    private static final String CALLBACK = "b.\u02bb\u02d1.\u02bb\u02d1";
    private static final String VFS = "b.\u02c8\u02c8.\u02be\u1d62";
    private static final int MAX_SOURCE_BYTES = 40_000_000;
    private static final long DOWNLOAD_WAIT_MS = 75_000;
    private static final int MAX_DIMENSION = 1280;

    private ImageLoader() {}

    static byte[] load(MediaRouter.ImageRef image) throws IOException {
        try {
            Class<?> media = XposedHelpers.findClass(MEDIA, image.loader);
            byte[] local = readExisting(media, image);
            if (local != null) {
                try { return encode(local); }
                catch (IOException incomplete) { /* 文件尚在下载时继续等待。 */ }
            }

            Class<?> callback = XposedHelpers.findClass(CALLBACK, image.loader);
            Class<?> download = XposedHelpers.findClass(DOWNLOAD, image.loader);
            AtomicReference<String> path = new AtomicReference<>();
            Object proxy = Proxy.newProxyInstance(image.loader, new Class<?>[]{callback},
                    (self, method, args) -> {
                        if ("\u02c6\u0674".equals(method.getName()) && args != null
                                && args.length == 1 && args[0] instanceof String) {
                            path.set((String) args[0]);
                        }
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(self);
                        if ("equals".equals(method.getName())) return self == args[0];
                        if ("toString".equals(method.getName())) return "ImageReadyCallback";
                        return null;
                    });
            XposedHelpers.callStaticMethod(download, "\u2071\u02c9", 0,
                    image.localId, image.serverId, proxy, image.imagePath);
            byte[] jpeg = MediaWait.untilReady(() -> {
                byte[] candidate = readExisting(media, image);
                String callbackPath = path.get();
                if (candidate == null && callbackPath != null) {
                    candidate = readCandidate(callbackPath, image.loader);
                    if (candidate == null) {
                        try {
                            String resolved = (String) XposedHelpers.callStaticMethod(media, "\u02ca\u02ce", callbackPath);
                            candidate = readCandidate(resolved, image.loader);
                        } catch (Throwable ignored) {}
                    }
                }
                if (candidate == null) return null;
                try { return encode(candidate); }
                catch (IOException incomplete) { return null; }
            }, DOWNLOAD_WAIT_MS, 1000, MediaWait.REAL_CLOCK);
            if (jpeg == null) throw new IOException("图片在等待后仍未下载完成或无法读取，请打开图片后重试");
            return jpeg;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("图片处理已中断");
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("图片处理失败");
        }
    }

    private static byte[] readExisting(Class<?> media, MediaRouter.ImageRef image) {
        try {
            String path = (String) XposedHelpers.callStaticMethod(media, "\u02bc\u02ce", image.serverId);
            return readCandidate(path, image.loader);
        } catch (Throwable ignored) { return null; }
    }

    static byte[] readCandidate(String path, ClassLoader loader) {
        if (path == null || path.isEmpty()) return null;
        File file = new File(path);
        if (file.isFile()) {
            try (InputStream input = new FileInputStream(file)) {
                byte[] data = readBounded(input);
                if (isImage(data)) return data;
            } catch (Exception ignored) {}
        }
        try {
            Class<?> vfs = XposedHelpers.findClass(VFS, loader);
            Object fileObject = XposedHelpers.newInstance(vfs, path);
            try (InputStream input = (InputStream) XposedHelpers.callMethod(fileObject, "\u02be\u1d62")) {
                if (input == null) return null;
                byte[] data = readBounded(input);
                return isImage(data) ? data : null;
            }
        } catch (Throwable ignored) { return null; }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (out.size() + n > MAX_SOURCE_BYTES) throw new IOException("图片过大");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static boolean isImage(byte[] data) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        return options.outWidth > 0 && options.outHeight > 0;
    }

    static byte[] encode(byte[] source) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(source, 0, source.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || bounds.outWidth > 50_000 || bounds.outHeight > 50_000)
            throw new IOException("无法识别图片格式");
        Bitmap frames = gifFrames(source);
        if (frames != null) {
            try { return compress(frames); }
            finally { frames.recycle(); }
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > MAX_DIMENSION)
            options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(source, 0, source.length, options);
        if (bitmap == null) throw new IOException("图片解码失败");
        try {
            int orientation = 1;
            try {
                orientation = new ExifInterface(new ByteArrayInputStream(source))
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (Exception ignored) {}
            Matrix matrix = new Matrix();
            if (orientation == 2 || orientation == 4 || orientation == 5 || orientation == 7)
                matrix.postScale(-1, 1);
            if (orientation == 3 || orientation == 4) matrix.postRotate(180);
            if (orientation == 5 || orientation == 6) matrix.postRotate(90);
            if (orientation == 7 || orientation == 8) matrix.postRotate(270);
            if (!matrix.isIdentity()) {
                Bitmap transformed = Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (transformed != bitmap) { bitmap.recycle(); bitmap = transformed; }
            }
            return compress(bitmap);
        } finally { bitmap.recycle(); }
    }

    private static byte[] compress(Bitmap bitmap) throws IOException {
        for (int quality : new int[]{82, 70, 55, 40, 25}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out))
                throw new IOException("图片编码失败");
            byte[] jpeg = out.toByteArray();
            if (jpeg.length <= BridgeRules.MAX_IMAGE_BYTES) return jpeg;
        }
        throw new IOException("图片压缩后仍然过大");
    }

    /** 动图接口只接受静态图，按顺序提取四个画面组成一张缩略图。 */
    private static Bitmap gifFrames(byte[] source) {
        if (source.length < 6 || source[0] != 'G' || source[1] != 'I' || source[2] != 'F') return null;
        try {
            Movie movie = Movie.decodeByteArray(source, 0, source.length);
            if (movie == null || movie.duration() <= 0 || movie.width() <= 0 || movie.height() <= 0
                    || movie.width() > 4096 || movie.height() > 4096) return null;
            int tile = 600, margin = 12;
            Bitmap sheet = Bitmap.createBitmap(tile * 2, tile * 2, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(sheet);
            canvas.drawColor(Color.WHITE);
            Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
            label.setTextSize(30); label.setColor(Color.BLACK);
            for (int i = 0; i < 4; i++) {
                int x = (i % 2) * tile, y = (i / 2) * tile;
                float scale = Math.min((tile - 2f * margin) / movie.width(),
                        (tile - 2f * margin - 32f) / movie.height());
                float dx = x + (tile - movie.width() * scale) / 2f;
                float dy = y + 32f + (tile - 32f - movie.height() * scale) / 2f;
                movie.setTime((int) (((long) (movie.duration() - 1) * i) / 3));
                canvas.save(); canvas.translate(dx, dy); canvas.scale(scale, scale);
                movie.draw(canvas, 0, 0); canvas.restore();
                canvas.drawText(Integer.toString(i + 1), x + margin, y + 30, label);
            }
            return sheet;
        } catch (Throwable ignored) { return null; }
    }
}
