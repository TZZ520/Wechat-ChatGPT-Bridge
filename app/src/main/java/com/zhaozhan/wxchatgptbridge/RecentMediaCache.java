package com.zhaozhan.wxchatgptbridge;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 仅保存在内存中；每个会话最多一份最近媒体，所有会话合计不超过 24 MB。 */
final class RecentMediaCache {
    static final int MAX_TOTAL_BYTES = 24_000_000;
    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<>(16, .75f, true);
    private static int totalBytes;

    static final class Entry {
        final String token, extension;
        final byte[] bytes;
        final boolean image;
        Entry(String token, String extension, byte[] bytes, boolean image) {
            this.token = token; this.extension = extension; this.bytes = bytes; this.image = image;
        }
    }

    private RecentMediaCache() {}

    static synchronized String put(String session, byte[] bytes, String extension, boolean image) {
        removeSession(session);
        if (bytes == null || bytes.length == 0 || bytes.length > FileRules.maxBytes(FileRules.MAX_MB)) return null;
        Iterator<Map.Entry<String, Entry>> iterator = ENTRIES.entrySet().iterator();
        while (totalBytes + bytes.length > MAX_TOTAL_BYTES && iterator.hasNext()) {
            totalBytes -= iterator.next().getValue().bytes.length;
            iterator.remove();
        }
        String token = UUID.randomUUID().toString();
        ENTRIES.put(session, new Entry(token, extension, bytes, image));
        totalBytes += bytes.length;
        return token;
    }

    static synchronized Entry get(String session, String token) {
        Entry value = ENTRIES.get(session);
        return value != null && value.token.equals(token) ? value : null;
    }

    static synchronized void removeSession(String session) {
        Entry old = ENTRIES.remove(session);
        if (old != null) totalBytes -= old.bytes.length;
    }

    static synchronized void removeToken(String token) {
        if (token == null) return;
        Iterator<Entry> iterator = ENTRIES.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (token.equals(entry.token)) {
                totalBytes -= entry.bytes.length;
                iterator.remove();
                return;
            }
        }
    }

    static synchronized void clear() { ENTRIES.clear(); totalBytes = 0; }
}
