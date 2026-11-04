package com.zhaozhan.wxchatgptbridge;

import android.content.Context;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 按微插提供的会话散列隔离短期上下文；不保存到磁盘。 */
final class ConversationMemory {
    private static final int MAX_SESSIONS = 64;
    static final int MAX_TURNS = 20;
    static final int DEFAULT_TURNS = 10;
    private static final int MAX_TEXT = 4_000;
    private static final long MAX_AGE_MS = 12L * 60 * 60 * 1000;
    private static final Map<String, Session> SESSIONS = new LinkedHashMap<>(16, .75f, true);

    interface ReplyAction { String invoke(String body, byte[] image) throws Exception; }
    interface MediaReplyAction {
        String invoke(String body, byte[] image, HistoricalMedia previous) throws Exception;
    }

    private static final class Turn {
        final String user, assistant, mediaToken;
        Turn(String user, String assistant, String mediaToken) {
            this.user = user; this.assistant = assistant; this.mediaToken = mediaToken;
        }
    }

    private static final class Session {
        final ArrayDeque<Turn> turns = new ArrayDeque<>();
        volatile long lastAccess = System.currentTimeMillis();
        int users;
    }

    private ConversationMemory() {}

    static String reply(Context context, String body, byte[] image) throws Exception {
        return reply(context, body, image, null);
    }

    static String reply(Context context, String body, byte[] image, byte[] pdf) throws Exception {
        return reply(context, body, image, pdf, "pdf", PdfLoader.MAX_BYTES);
    }

    static String reply(Context context, String body, byte[] image, byte[] file,
            String extension, int maxBytes) throws Exception {
        android.content.SharedPreferences prefs = SessionStore.prefs(context);
        if (!prefs.getBoolean("history_enabled", true))
            return ModelApi.replyWechat(context, body, image, file, extension, maxBytes, null);
        int limit = historyLimit(prefs.getInt("history_turns", DEFAULT_TURNS));
        return withMediaMemory(body, image, file, extension, limit,
                (prepared, bytes, previous) -> ModelApi.replyWechat(context, prepared, bytes, file,
                        extension, maxBytes, previous));
    }

    static String withMemory(String body, byte[] image, ReplyAction action) throws Exception {
        return withMemory(body, image, DEFAULT_TURNS, action);
    }

    static String withMemory(String body, byte[] image, int requestedLimit, ReplyAction action) throws Exception {
        return withMediaMemory(body, image, null, "", requestedLimit,
                (prepared, bytes, previous) -> action.invoke(prepared, bytes));
    }

    static String withMediaMemory(String body, byte[] image, byte[] file, String extension,
            int requestedLimit, MediaReplyAction action) throws Exception {
        int limit = historyLimit(requestedLimit);
        JSONObject request = new JSONObject(body);
        JSONArray messages = request.getJSONArray("messages");
        if (messages.length() == 0) return action.invoke(body, image, null);
        JSONObject current = messages.optJSONObject(messages.length() - 1);
        if (current == null || !"user".equals(current.optString("role"))) return action.invoke(body, image, null);
        String name = current.optString("name", "");
        Object content = current.opt("content");
        // 没有可用会话标识时不共享记忆，避免不同会话串话。
        if (!name.matches("[0-9a-fA-F]{32,64}") || !(content instanceof String)) {
            Log.i("WxGPTBridge", "conversation_memory_unavailable");
            return action.invoke(body, image, null);
        }
        String sessionKey = name.toLowerCase(Locale.ROOT);
        Session session = acquire(sessionKey);
        if (session == null) return action.invoke(body, image, null);
        try {
            synchronized (session) {
                if (System.currentTimeMillis() - session.lastAccess > MAX_AGE_MS) {
                    session.turns.clear();
                    RecentMediaCache.removeSession(sessionKey);
                }
                trim(session, limit);
                JSONArray combined = new JSONArray();
                for (int i = 0; i < messages.length() - 1; i++) {
                    JSONObject item = messages.optJSONObject(i);
                    if (item == null) continue;
                    String role = item.optString("role");
                    if ("system".equals(role) || "developer".equals(role))
                        combined.put(new JSONObject(item.toString()));
                }
                HistoricalMedia previous = null;
                for (Turn turn : session.turns) {
                    int messageIndex = combined.length();
                    combined.put(new JSONObject().put("role", "user").put("content", turn.user));
                    combined.put(new JSONObject().put("role", "assistant").put("content", turn.assistant));
                    if (image == null && file == null && turn.mediaToken != null) {
                        RecentMediaCache.Entry entry = RecentMediaCache.get(sessionKey, turn.mediaToken);
                        if (entry != null) previous = new HistoricalMedia(messageIndex, entry);
                    }
                }
                Log.i("WxGPTBridge", "conversation_memory_turns=" + session.turns.size());
                combined.put(new JSONObject(current.toString()));
                request.put("messages", combined);
                String result = action.invoke(request.toString(), image, previous);
                String answer = new JSONObject(result).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content");
                if (!answer.trim().isEmpty()) {
                    synchronized (ConversationMemory.class) {
                        if (SESSIONS.get(sessionKey) == session) {
                            String text = (String) content;
                            String mediaToken = null;
                            if (BridgeRules.isImageMarker(text) && image != null)
                                mediaToken = RecentMediaCache.put(sessionKey, image, "", true);
                            else if ((BridgeRules.isPdfMarker(text) || BridgeRules.isFileMarker(text)) && file != null)
                                mediaToken = RecentMediaCache.put(sessionKey, file, FileRules.normalized(extension), false);
                            if (BridgeRules.isImageMarker(text)) text = "[对方之前发送了一张图片]";
                            if (BridgeRules.isPdfMarker(text)) text = "[对方之前发送了一份 PDF]";
                            if (BridgeRules.isFileMarker(text)) text = "[对方之前发送了一份文件]";
                            session.turns.addLast(new Turn(bounded(text), bounded(answer), mediaToken));
                            trim(session, limit);
                        }
                    }
                }
                return result;
            }
        } finally {
            release(session);
        }
    }

    private static String bounded(String text) {
        if (text.length() <= MAX_TEXT) return text;
        int end = Character.isHighSurrogate(text.charAt(MAX_TEXT - 1)) ? MAX_TEXT - 1 : MAX_TEXT;
        return text.substring(0, end);
    }

    static int historyLimit(int value) { return Math.max(1, Math.min(MAX_TURNS, value)); }

    /** 离线验证独立的合成会话，不读取、清空或发送真实聊天。 */
    static void runLocalSelfTest() throws Exception {
        String key = java.util.UUID.randomUUID().toString().replace("-", "");
        synchronized (ConversationMemory.class) {
            if (SESSIONS.size() >= MAX_SESSIONS || SESSIONS.containsKey(key))
                throw new java.io.IOException("当前会话较多，请稍后再测试");
            try {
                if (historyLimit(0) != 1 || historyLimit(11) != 11
                        || historyLimit(20) != 20 || historyLimit(21) != 20)
                    throw new java.io.IOException("记忆范围异常");
                for (int limit = 1; limit <= MAX_TURNS; limit++) {
                    SESSIONS.remove(key);
                    final int testedLimit = limit;
                    for (int i = 0; i < limit + 2; i++) {
                        final int index = i;
                        JSONObject body = new JSONObject().put("messages", new JSONArray()
                                .put(new JSONObject().put("role", "system").put("content", "本地自检"))
                                .put(new JSONObject().put("role", "user").put("name", key)
                                        .put("content", "测试" + i)));
                        withMemory(body.toString(), null, testedLimit, (prepared, image) -> {
                            JSONArray messages = new JSONObject(prepared).getJSONArray("messages");
                            int retained = Math.min(index, testedLimit);
                            if (messages.length() != 2 + retained * 2
                                    || (retained > 0 && !("测试" + (index - retained))
                                            .equals(messages.getJSONObject(1).getString("content"))))
                                throw new java.io.IOException("记忆轮数验证失败");
                            return new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                                    .put("message", new JSONObject().put("content", "回复" + index))))
                                    .toString();
                        });
                    }
                }
            } finally {
                SESSIONS.remove(key);
                RecentMediaCache.removeSession(key);
            }
        }
    }

    private static void trim(Session session, int limit) {
        while (session.turns.size() > limit) RecentMediaCache.removeToken(session.turns.removeFirst().mediaToken);
    }

    /** 数量调低时后台删除多余轮次，避免等待网络请求阻塞设置页面。 */
    static void trimToLimitAsync(int requestedLimit) {
        final int limit = historyLimit(requestedLimit);
        new Thread(() -> {
            ArrayList<Session> sessions;
            synchronized (ConversationMemory.class) {
                sessions = new ArrayList<>(SESSIONS.values());
            }
            for (Session session : sessions) {
                synchronized (session) { trim(session, limit); }
            }
        }, "chatgpt-memory-trim").start();
    }

    private static synchronized Session acquire(String key) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Session> entry = it.next();
            Session s = entry.getValue();
            if (s.users == 0 && now - s.lastAccess > MAX_AGE_MS) {
                RecentMediaCache.removeSession(entry.getKey());
                it.remove();
            }
        }
        Session session = SESSIONS.get(key);
        if (session == null) {
            if (SESSIONS.size() >= MAX_SESSIONS) {
                it = SESSIONS.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Session> entry = it.next();
                    if (entry.getValue().users == 0) {
                        RecentMediaCache.removeSession(entry.getKey());
                        it.remove();
                        break;
                    }
                }
                if (SESSIONS.size() >= MAX_SESSIONS) return null;
            }
            session = new Session();
            SESSIONS.put(key, session);
        }
        session.users++;
        return session;
    }

    private static synchronized void release(Session session) {
        session.lastAccess = System.currentTimeMillis();
        session.users--;
    }

    static synchronized void clear() { SESSIONS.clear(); RecentMediaCache.clear(); }
}
