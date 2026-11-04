package com.zhaozhan.wxchatgptbridge;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class ConversationMemoryTest {
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Before public void clear() { ConversationMemory.clear(); }

    @Test public void localSelfTestPreservesExistingSession() throws Exception {
        ConversationMemory.withMemory(body(A, "之前"), null, (request, image) -> result("之前的回复"));
        ConversationMemory.runLocalSelfTest();
        ConversationMemory.withMemory(body(A, "之后"), null, (request, image) -> {
            JSONArray messages = new JSONObject(request).getJSONArray("messages");
            assertEquals("之前", messages.getJSONObject(1).getString("content"));
            assertEquals("之前的回复", messages.getJSONObject(2).getString("content"));
            return result("完成");
        });
    }

    private static String body(String name, String text) throws Exception {
        JSONObject message = new JSONObject().put("role", "user").put("content", text);
        if (name != null) message.put("name", name);
        return new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", "规则"))
                .put(message)).toString();
    }

    private static String result(String text) throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message",
                new JSONObject().put("role", "assistant").put("content", text)))).toString();
    }

    @Test public void historyIsPerConversationAndCanBeCleared() throws Exception {
        ConversationMemory.withMemory(body(A, "甲1"), null, (request, image) -> result("答1"));
        ConversationMemory.withMemory(body(A, "甲2"), null, (request, image) -> {
            JSONArray messages = new JSONObject(request).getJSONArray("messages");
            assertEquals(4, messages.length());
            assertEquals("甲1", messages.getJSONObject(1).getString("content"));
            assertEquals("答1", messages.getJSONObject(2).getString("content"));
            return result("答2");
        });
        ConversationMemory.withMemory(body(B, "乙1"), null, (request, image) -> {
            assertEquals(2, new JSONObject(request).getJSONArray("messages").length());
            return result("乙答");
        });
        ConversationMemory.clear();
        ConversationMemory.withMemory(body(A, "清除后"), null, (request, image) -> {
            assertEquals(2, new JSONObject(request).getJSONArray("messages").length());
            return result("完成");
        });
    }

    @Test public void defaultKeepsOnlyTenSuccessfulTurns() throws Exception {
        for (int i = 0; i < 13; i++) {
            final int turn = i;
            ConversationMemory.withMemory(body(A, "问" + i), null, (request, image) -> {
                JSONArray messages = new JSONObject(request).getJSONArray("messages");
                assertTrue(messages.length() <= 22);
                if (turn == 12) {
                    assertEquals(22, messages.length());
                    assertEquals("问2", messages.getJSONObject(1).getString("content"));
                }
                return result("答" + turn);
            });
        }
    }

    @Test public void customLimitRetainsLatestFourTurns() throws Exception {
        for (int i = 0; i < 7; i++) {
            final int turn = i;
            ConversationMemory.withMemory(body(A, "问" + i), null, 4, (request, image) -> {
                JSONArray messages = new JSONObject(request).getJSONArray("messages");
                assertTrue(messages.length() <= 10);
                if (turn == 6) {
                    assertEquals(10, messages.length());
                    assertEquals("问2", messages.getJSONObject(1).getString("content"));
                }
                return result("答" + turn);
            });
        }
    }

    @Test public void customLimitRetainsTwentyTurnsAndDropsOnlyTheOldest() throws Exception {
        for (int i = 0; i < 22; i++) {
            final int turn = i;
            ConversationMemory.withMemory(body(A, "问" + i), null, 20, (request, image) -> {
                JSONArray messages = new JSONObject(request).getJSONArray("messages");
                assertTrue(messages.length() <= 42);
                if (turn == 20 || turn == 21) {
                    assertEquals(42, messages.length());
                    assertEquals("问" + (turn - 20), messages.getJSONObject(1).getString("content"));
                    assertEquals("答" + (turn - 1), messages.getJSONObject(40).getString("content"));
                    assertEquals("问" + turn, messages.getJSONObject(41).getString("content"));
                }
                return result("答" + turn);
            });
        }
    }

    @Test public void reducingLimitDropsOldTurnsBeforeTheNextRequest() throws Exception {
        for (int i = 0; i < 10; i++) {
            final int turn = i;
            ConversationMemory.withMemory(body(A, "问" + i), null, 10,
                    (request, image) -> result("答" + turn));
        }
        ConversationMemory.withMemory(body(A, "新问题"), null, 3, (request, image) -> {
            JSONArray messages = new JSONObject(request).getJSONArray("messages");
            assertEquals(8, messages.length());
            assertEquals("问7", messages.getJSONObject(1).getString("content"));
            return result("新回答");
        });
        ConversationMemory.withMemory(body(A, "再次提问"), null, 10, (request, image) -> {
            JSONArray messages = new JSONObject(request).getJSONArray("messages");
            assertEquals(8, messages.length());
            assertEquals("问8", messages.getJSONObject(1).getString("content"));
            return result("完成");
        });
    }

    @Test public void limitIsAlwaysClampedBetweenOneAndTwenty() throws Exception {
        assertEquals(1, ConversationMemory.historyLimit(0));
        assertEquals(11, ConversationMemory.historyLimit(11));
        assertEquals(20, ConversationMemory.historyLimit(20));
        assertEquals(20, ConversationMemory.historyLimit(21));
        for (int i = 0; i < 3; i++) {
            final int turn = i;
            ConversationMemory.withMemory(body(A, "问" + i), null, -10, (request, image) -> {
                JSONArray messages = new JSONObject(request).getJSONArray("messages");
                assertTrue(messages.length() <= 4);
                if (turn == 2) assertEquals("问1", messages.getJSONObject(1).getString("content"));
                return result("答" + turn);
            });
        }
        ConversationMemory.clear();
        for (int i = 0; i < 23; i++) {
            final int turn = i;
            ConversationMemory.withMemory(body(A, "问" + i), null, 50, (request, image) -> {
                JSONArray messages = new JSONObject(request).getJSONArray("messages");
                assertTrue(messages.length() <= 42);
                if (turn == 22) assertEquals("问2", messages.getJSONObject(1).getString("content"));
                return result("答" + turn);
            });
        }
    }

    @Test public void missingIdentityAndFailedRequestsNeverShareHistory() throws Exception {
        ConversationMemory.withMemory(body(null, "无身份"), null, (request, image) -> result("无身份答"));
        try {
            ConversationMemory.withMemory(body(A, "失败"), null, (request, image) -> {
                throw new java.io.IOException("expected");
            });
            fail("failure must propagate");
        } catch (java.io.IOException expected) { }
        ConversationMemory.withMemory(body(A, "新问题"), null, (request, image) -> {
            assertEquals(2, new JSONObject(request).getJSONArray("messages").length());
            return result("完成");
        });
    }

    @Test public void priorImageIsRepresentedByTextWithoutKeepingImageBytes() throws Exception {
        String marker = BridgeRules.IMAGE_PREFIX + "00000000000000000000000000000000]";
        ConversationMemory.withMemory(body(A, marker), new byte[]{1}, (request, image) -> result("红色"));
        ConversationMemory.withMemory(body(A, "刚才呢"), null, (request, image) -> {
            JSONArray messages = new JSONObject(request).getJSONArray("messages");
            assertEquals("[对方之前发送了一张图片]", messages.getJSONObject(1).getString("content"));
            assertEquals("红色", messages.getJSONObject(2).getString("content"));
            return result("完成");
        });
    }

    @Test public void priorPdfIsRepresentedByTextWithoutKeepingFileBytes() throws Exception {
        String marker = BridgeRules.PDF_PREFIX + "00000000000000000000000000000000]";
        ConversationMemory.withMemory(body(A, marker), null, (request, image) -> result("合同"));
        ConversationMemory.withMemory(body(A, "刚才呢"), null, (request, image) -> {
            JSONArray messages = new JSONObject(request).getJSONArray("messages");
            assertEquals("[对方之前发送了一份 PDF]", messages.getJSONObject(1).getString("content"));
            assertEquals("合同", messages.getJSONObject(2).getString("content"));
            return result("完成");
        });
    }

    @Test public void priorDocumentIsRepresentedWithoutKeepingFileBytes() throws Exception {
        String marker = BridgeRules.FILE_PREFIX + "00000000000000000000000000000000]";
        ConversationMemory.withMemory(body(A, marker), null, (request, image) -> result("文档内容"));
        ConversationMemory.withMemory(body(A, "刚才呢"), null, (request, image) -> {
            JSONArray messages = new JSONObject(request).getJSONArray("messages");
            assertEquals("[对方之前发送了一份文件]", messages.getJSONObject(1).getString("content"));
            return result("完成");
        });
    }
}
