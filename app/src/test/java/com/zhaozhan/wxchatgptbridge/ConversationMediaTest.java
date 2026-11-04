package com.zhaozhan.wxchatgptbridge;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ConversationMediaTest {
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String FILE = BridgeRules.FILE_PREFIX + "00000000000000000000000000000000]";
    private static final String IMAGE = BridgeRules.IMAGE_PREFIX + "00000000000000000000000000000000]";
    private static final byte[] XLSX = new byte[]{'P', 'K', 3, 4, 42};
    private static final byte[] JPEG = new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2};

    @Before public void clear() { ConversationMemory.clear(); }

    private static String body(String id, String text) throws Exception {
        return new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", "rules"))
                .put(new JSONObject().put("role", "user").put("name", id).put("content", text))).toString();
    }
    private static String result() throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message",
                new JSONObject().put("role", "assistant").put("content", "done")))).toString();
    }

    @Test public void followupIncludesOnlySameSessionsRecentFile() throws Exception {
        ConversationMemory.withMediaMemory(body(A, FILE), null, XLSX, "xlsx", 10,
                (prepared, image, previous) -> { assertNull(previous); return result(); });
        ConversationMemory.withMediaMemory(body(B, "other"), null, null, "", 10,
                (prepared, image, previous) -> { assertNull(previous); return result(); });
        ConversationMemory.withMediaMemory(body(A, "cell B1"), null, null, "", 10,
                (prepared, image, previous) -> {
                    assertNotNull(previous);
                    assertArrayEquals(XLSX, previous.bytes);
                    JSONObject request = BridgeRules.makeResponseRequest(prepared, "model", null, null,
                            "", 1_000_000, previous);
                    JSONArray input = request.getJSONArray("input");
                    JSONArray parts = input.getJSONObject(0).getJSONArray("content");
                    assertEquals("input_file", parts.getJSONObject(1).getString("type"));
                    assertEquals("attachment.xlsx", parts.getJSONObject(1).getString("filename"));
                    assertEquals("cell B1", input.getJSONObject(2).getString("content"));
                    return result();
                });
        ConversationMemory.clear();
        ConversationMemory.withMediaMemory(body(A, "after clear"), null, null, "", 10,
                (prepared, image, previous) -> { assertNull(previous); return result(); });
    }

    @Test public void nextMediaReplacesEarlierAndTrimRemovesIt() throws Exception {
        ConversationMemory.withMediaMemory(body(A, FILE), null, XLSX, "xlsx", 1,
                (prepared, image, previous) -> result());
        ConversationMemory.withMediaMemory(body(A, IMAGE), JPEG, null, "", 1,
                (prepared, image, previous) -> { assertNull(previous); return result(); });
        ConversationMemory.withMediaMemory(body(A, "image question"), null, null, "", 1,
                (prepared, image, previous) -> {
                    assertNotNull(previous); assertTrue(previous.image); assertArrayEquals(JPEG, previous.bytes);
                    JSONObject request = BridgeRules.makeResponseRequest(prepared, "model", null, null,
                            "", 1_000_000, previous);
                    assertEquals("input_image", request.getJSONArray("input").getJSONObject(0)
                            .getJSONArray("content").getJSONObject(1).getString("type"));
                    return result();
                });
        ConversationMemory.withMediaMemory(body(A, "after trim"), null, null, "", 1,
                (prepared, image, previous) -> { assertNull(previous); return result(); });
    }

    @Test public void boundedCacheEvictsOldSessionsAndHonorsCurrentFileLimit() throws Exception {
        byte[] large = new byte[13_000_000];
        String first = RecentMediaCache.put(A, large, "txt", false);
        String second = RecentMediaCache.put(B, new byte[13_000_000], "txt", false);
        assertNull(RecentMediaCache.get(A, first));
        assertNotNull(RecentMediaCache.get(B, second));
        RecentMediaCache.clear();
        ConversationMemory.withMediaMemory(body(A, FILE), null, XLSX, "xlsx", 10,
                (prepared, image, previous) -> result());
        ConversationMemory.withMediaMemory(body(A, "too small"), null, null, "", 10,
                (prepared, image, previous) -> {
                    JSONObject request = BridgeRules.makeResponseRequest(prepared, "model", null, null,
                            "", 4, previous);
                    assertEquals("[对方之前发送了一份文件]", request.getJSONArray("input")
                            .getJSONObject(0).getString("content"));
                    return result();
                });
    }

    @Test public void clearDuringRequestDoesNotRestoreMediaOrHistory() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread request = new Thread(() -> {
            try {
                ConversationMemory.withMediaMemory(body(A, FILE), null, XLSX, "xlsx", 10,
                        (prepared, image, previous) -> {
                            started.countDown();
                            if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("请求未继续");
                            return result();
                        });
            } catch (Throwable problem) { failure.set(problem); }
        });
        request.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            ConversationMemory.clear();
        } finally {
            finish.countDown();
            request.join(5000);
        }
        assertFalse(request.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        ConversationMemory.withMediaMemory(body(A, "clear followup"), null, null, "", 10,
                (prepared, image, previous) -> {
                    assertNull(previous);
                    assertEquals(2, new JSONObject(prepared).getJSONArray("messages").length());
                    return result();
                });
    }

    @Test public void failedMediaRequestDoesNotAddMediaOrHistory() throws Exception {
        try {
            ConversationMemory.withMediaMemory(body(A, FILE), null, XLSX, "xlsx", 10,
                    (prepared, image, previous) -> { throw new java.io.IOException("expected"); });
            fail("请求失败应向上传递");
        } catch (java.io.IOException expected) { }
        ConversationMemory.withMediaMemory(body(A, "followup"), null, null, "", 10,
                (prepared, image, previous) -> {
                    assertNull(previous);
                    assertEquals(2, new JSONObject(prepared).getJSONArray("messages").length());
                    return result();
                });
    }
}
