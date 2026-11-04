package com.zhaozhan.wxchatgptbridge;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 控制可处理的附件类型与大小；不使用来自消息的文件名。 */
final class FileRules {
    static final int DEFAULT_MB = 10;
    static final int MAX_MB = 20;
    private static final Map<String, String> TYPES;
    static {
        Map<String, String> types = new HashMap<>();
        types.put("pdf", "application/pdf");
        types.put("doc", "application/msword");
        types.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        types.put("rtf", "application/rtf");
        types.put("odt", "application/vnd.oasis.opendocument.text");
        types.put("ppt", "application/vnd.ms-powerpoint");
        types.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        types.put("xls", "application/vnd.ms-excel");
        types.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        types.put("csv", "text/csv");
        types.put("tsv", "text/tsv");
        types.put("txt", "text/plain");
        types.put("md", "text/markdown");
        types.put("json", "application/json");
        types.put("xml", "text/xml");
        types.put("html", "text/html");
        types.put("htm", "text/html");
        types.put("py", "text/x-python");
        types.put("java", "text/x-java");
        types.put("js", "text/javascript");
        types.put("c", "text/x-c");
        types.put("cpp", "text/x-c++");
        types.put("h", "text/x-c");
        types.put("sql", "text/x-sql");
        types.put("log", "text/plain");
        TYPES = Collections.unmodifiableMap(types);
    }

    private FileRules() {}
    static int megabytes(int value) { return Math.max(1, Math.min(MAX_MB, value)); }
    static int maxBytes(int megabytes) { return megabytes(megabytes) * 1_000_000; }
    static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
    static boolean isDocument(String value) { return TYPES.containsKey(normalized(value)); }
    static boolean isImage(String value) {
        String ext = normalized(value);
        return "gif".equals(ext) || "jpg".equals(ext) || "jpeg".equals(ext)
                || "png".equals(ext) || "webp".equals(ext);
    }
    static boolean supported(String value) { return isDocument(value) || isImage(value); }
    static String mime(String value) { return TYPES.get(normalized(value)); }

    static boolean validDocument(byte[] bytes, String value, int maxBytes) {
        String ext = normalized(value);
        if (!isDocument(ext) || bytes == null || bytes.length == 0 || bytes.length > maxBytes) return false;
        if ("pdf".equals(ext)) return starts(bytes, new byte[]{'%', 'P', 'D', 'F', '-'});
        if ("docx".equals(ext) || "xlsx".equals(ext) || "pptx".equals(ext) || "odt".equals(ext))
            return starts(bytes, new byte[]{'P', 'K', 3, 4});
        if ("doc".equals(ext) || "xls".equals(ext) || "ppt".equals(ext))
            return starts(bytes, new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0});
        return true;
    }
    private static boolean starts(byte[] bytes, byte[] expected) {
        if (bytes.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (bytes[i] != expected[i]) return false;
        return true;
    }
}
