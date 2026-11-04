package com.zhaozhan.wxchatgptbridge;

import android.app.Application;
import android.database.Cursor;
import android.util.Log;
import de.robv.android.xposed.XposedHelpers;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;

/** 根据消息编号只读查询已下载的微信附件。 */
final class PdfLoader {
    static final int MAX_BYTES = 2_000_000;
    private static final String DATABASE = "b.\u2071\u1d54.\u02bb";
    private PdfLoader() {}

    /** 使用不可能的消息编号检查数据库接口，不读取实际附件记录。 */
    static boolean probe(ClassLoader loader) {
        Cursor cursor = null;
        try {
            Class<?> type = XposedHelpers.findClass(DATABASE, loader);
            Object db = XposedHelpers.getStaticObjectField(type, "\uff9e\u0674");
            cursor = (Cursor) XposedHelpers.callMethod(db, "\u05d9\u02bb",
                    "SELECT fileFullPath FROM appattach WHERE msgInfoId=? LIMIT 1", new String[]{"-1"});
            return cursor != null && cursor.getColumnIndex("fileFullPath") >= 0;
        } catch (Throwable ignored) { return false; }
        finally { if (cursor != null) try { cursor.close(); } catch (Exception ignored) {} }
    }

    static File find(Application app, long msgId, ClassLoader loader) {
        return find(app, msgId, loader, "pdf", MAX_BYTES);
    }

    static File find(Application app, long msgId, ClassLoader loader, String extension, int maxBytes) {
        Cursor cursor = null;
        try {
            Class<?> type = XposedHelpers.findClass(DATABASE, loader);
            Object db = XposedHelpers.getStaticObjectField(type, "\uff9e\u0674");
            cursor = (Cursor) XposedHelpers.callMethod(db, "\u05d9\u02bb",
                    "SELECT fileFullPath FROM appattach WHERE msgInfoId=? LIMIT 1",
                    new String[]{Long.toString(msgId)});
            if (cursor == null || !cursor.moveToFirst()) return null;
            String path = cursor.getString(0);
            if (path == null || path.isEmpty()) return null;
            File file = new File(path).getCanonicalFile();
            if (!allowed(app, file)) return null;
            if (!isComplete(file, extension, maxBytes)) return null;
            return file;
        } catch (Throwable ignored) { return null; }
        finally { if (cursor != null) try { cursor.close(); } catch (Exception ignored) {} }
    }

    /** 对当前通过过滤的消息只读查询其完整附件元数据。 */
    static String completeMessage(MediaRouter.ImageRef ref, int maxBytes) {
        Cursor cursor = null;
        try {
            Class<?> type = XposedHelpers.findClass(DATABASE, ref.loader);
            Object db = XposedHelpers.getStaticObjectField(type, "\uff9e\u0674");
            cursor = (Cursor) XposedHelpers.callMethod(db, "\u05d9\u02bb",
                    "SELECT content FROM message WHERE msgId=? AND talker=? AND isSend=0 LIMIT 1",
                    new String[]{Long.toString(ref.localId), ref.talker});
            if (cursor == null || !cursor.moveToFirst()) return null;
            String xml = cursor.getString(0);
            PdfMessage.Metadata meta = PdfMessage.parse(xml);
            if (meta.appType != 6 || !ref.extension.equals(meta.extension) || meta.expectedBytes > maxBytes
                    || (ref.expectedBytes > 0 && meta.expectedBytes > 0 && ref.expectedBytes != meta.expectedBytes))
                return null;
            return xml;
        } catch (Throwable ignored) { return null; }
        finally { if (cursor != null) try { cursor.close(); } catch (Exception ignored) {} }
    }

    static boolean hasAttachment(long msgId, ClassLoader loader) {
        Cursor cursor = null;
        try {
            Class<?> type = XposedHelpers.findClass(DATABASE, loader);
            Object db = XposedHelpers.getStaticObjectField(type, "\uff9e\u0674");
            cursor = (Cursor) XposedHelpers.callMethod(db, "\u05d9\u02bb",
                    "SELECT 1 FROM appattach WHERE msgInfoId=? LIMIT 1", new String[]{Long.toString(msgId)});
            return cursor != null && cursor.moveToFirst();
        } catch (Throwable ignored) { return false; }
        finally { if (cursor != null) try { cursor.close(); } catch (Exception ignored) {} }
    }

    /** 诊断只记录状态，不记录附件名称、路径、标识或内容。 */
    static void logState(Application app, long msgId, ClassLoader loader, String extension,
                         int maxBytes, long expectedBytes) {
        Cursor cursor = null;
        try {
            Class<?> type = XposedHelpers.findClass(DATABASE, loader);
            Object db = XposedHelpers.getStaticObjectField(type, "\uff9e\u0674");
            cursor = (Cursor) XposedHelpers.callMethod(db, "\u05d9\u02bb",
                    "SELECT fileFullPath FROM appattach WHERE msgInfoId=? LIMIT 1",
                    new String[]{Long.toString(msgId)});
            boolean row = cursor != null && cursor.moveToFirst();
            String path = row ? cursor.getString(0) : null;
            File file = path == null || path.isEmpty() ? null : new File(path).getCanonicalFile();
            boolean allowed = file != null && allowed(app, file);
            boolean exists = allowed && file.isFile();
            boolean sizeMatches = exists && (expectedBytes <= 0 || file.length() == expectedBytes);
            boolean complete = exists && isComplete(file, extension, maxBytes);
            Log.i("WxGPTBridge", "file_lookup row=" + row + " path=" + (file != null)
                    + " allowed=" + allowed + " exists=" + exists + " size_matches=" + sizeMatches
                    + " readable=" + complete);
        } catch (Throwable ignored) { Log.w("WxGPTBridge", "file_lookup_probe_unavailable"); }
        finally { if (cursor != null) try { cursor.close(); } catch (Exception ignored) {} }
    }

    private static boolean within(File file, File root) {
        return root != null && file.getPath().startsWith(root.getPath() + File.separator);
    }

    static boolean isComplete(File file) {
        return isComplete(file, "pdf", MAX_BYTES);
    }

    static boolean isComplete(File file, String extension, int maxBytes) {
        String ext = FileRules.normalized(extension);
        int limit = Math.min(maxBytes, FileRules.maxBytes(FileRules.MAX_MB));
        if (!FileRules.supported(ext) || file == null || !file.isFile() || file.length() < 1
                || file.length() > limit || !file.getName().toLowerCase(Locale.ROOT).endsWith("." + ext))
            return false;
        if (!"pdf".equals(ext)) return true;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] head = new byte[5];
            if (input.read(head) != 5 || head[0] != '%' || head[1] != 'P' || head[2] != 'D'
                    || head[3] != 'F' || head[4] != '-') return false;
        } catch (IOException ignored) { return false; }
        try (android.os.ParcelFileDescriptor fd = android.os.ParcelFileDescriptor.open(file,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY);
             android.graphics.pdf.PdfRenderer renderer = new android.graphics.pdf.PdfRenderer(fd)) {
            return renderer.getPageCount() > 0;
        } catch (Throwable ignored) { return false; }
    }

    private static boolean allowed(Application app, File file) throws IOException {
        File internal = app.getDataDir().getCanonicalFile();
        File externalFiles = app.getExternalFilesDir(null);
        File external = externalFiles == null ? null : externalFiles.getParentFile().getCanonicalFile();
        return within(file, internal) || within(file, external);
    }

    static byte[] read(Application app, File original) throws IOException {
        return read(app, original, "pdf", MAX_BYTES);
    }

    static byte[] read(Application app, File original, String extension, int maxBytes) throws IOException {
        String ext = FileRules.normalized(extension);
        int limit = Math.min(maxBytes, FileRules.maxBytes(FileRules.MAX_MB));
        File file = original == null ? null : original.getCanonicalFile();
        if (!FileRules.supported(ext) || file == null || !allowed(app, file) || !file.isFile()
                || file.length() < 1 || file.length() > limit
                || !file.getName().toLowerCase(Locale.ROOT).endsWith("." + ext))
            throw new IOException("文件未下载、格式不支持或超过大小上限");
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[16_384]; int n;
            while ((n = input.read(block)) != -1) {
                if (output.size() + n > limit) throw new IOException("文件大小超过设置上限");
                output.write(block, 0, n);
            }
            byte[] bytes = output.toByteArray();
            if (FileRules.isDocument(ext) && !FileRules.validDocument(bytes, ext, limit))
                throw new IOException("文件类型或格式不受支持");
            return bytes;
        }
    }
}
