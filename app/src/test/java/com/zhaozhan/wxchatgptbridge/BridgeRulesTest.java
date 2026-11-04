package com.zhaozhan.wxchatgptbridge;

import static org.junit.Assert.*;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class BridgeRulesTest {
    private StackTraceElement[] micro() {
        return new StackTraceElement[] { new StackTraceElement(
                BridgeRules.NETWORK_CLASS + "\u0674\u0640", "run", "Core.java", 1) };
    }

    @Test public void routesOnlyMicroOpenAiChatRequests() throws Exception {
        assertTrue(BridgeRules.isMicroChatRequest(
                new URL("https://api.openai.com/v1/chat/completions"), micro()));
        assertFalse(BridgeRules.isMicroChatRequest(
                new URL("https://api.openai.com/v1/chat/completions"),
                new StackTraceElement[]{new StackTraceElement("other.App", "run", "App.java", 1)}));
        assertFalse(BridgeRules.isMicroChatRequest(
                new URL("https://api.openai.com/v1/responses"), micro()));
        assertFalse(BridgeRules.isMicroChatRequest(
                new URL("https://example.com/v1/chat/completions"), micro()));
        assertFalse(BridgeRules.isMicroChatRequest(
                new URL("https://api.openai.com:444/v1/chat/completions"), micro()));
    }

    @Test public void identifiesOnlyMicroAutoReplySelectionCalls() {
        StackTraceElement matching = new StackTraceElement(BridgeRules.AUTOREPLY_CLASS,
                "\u02c6\u0674", "Core.java", 1);
        StackTraceElement another = new StackTraceElement(BridgeRules.AUTOREPLY_CLASS,
                "differentMethod", "Core.java", 1);
        assertTrue(BridgeRules.isAutoReplyCaller(new StackTraceElement[]{matching}));
        assertFalse(BridgeRules.isAutoReplyCaller(new StackTraceElement[]{another}));
    }

    @Test public void recognizesCoreDexWithoutChangingBufferState() {
        byte[] data = "prefix-openai_chatgpt_api_url-suffix"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        java.nio.ByteBuffer core = java.nio.ByteBuffer.wrap(data);
        core.position(3);
        assertTrue(BridgeRules.isCoreDex(core));
        assertEquals(3, core.position());
        assertFalse(BridgeRules.isCoreDex(java.nio.ByteBuffer.wrap(new byte[]{1, 2, 3})));
    }

    @Test public void translatesHistoryAndUsesSelectedModel() throws Exception {
        JSONObject chat = new JSONObject()
                .put("model", "old-model")
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", "be brief"))
                        .put(new JSONObject().put("role", "user").put("content", "hello")
                                .put("name", "private-contact"))
                        .put(new JSONObject().put("role", "assistant").put("content", "hi")));
        JSONObject request = BridgeRules.makeResponseRequest(chat.toString(), "account-model");
        assertEquals("account-model", request.getString("model"));
        assertEquals("be brief", request.getString("instructions"));
        assertFalse(request.getBoolean("store"));
        assertTrue(request.getBoolean("stream"));
        assertFalse(request.has("messages"));
        assertEquals(2, request.getJSONArray("input").length());
        assertFalse(request.getJSONArray("input").getJSONObject(0).has("name"));
    }

    @Test public void rejectsMissingModelAndUnsupportedContent() throws Exception {
        String text = "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
        try { BridgeRules.makeResponseRequest(text, ""); fail(); }
        catch (org.json.JSONException expected) {}
        String image = "{\"messages\":[{\"role\":\"user\",\"content\":[{}]}]}";
        try { BridgeRules.makeResponseRequest(image, "model"); fail(); }
        catch (org.json.JSONException expected) {}
    }

    @Test public void acceptsOnlyCompletedStreams() throws Exception {
        String event = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n";
        String completed = "data: {\"type\":\"response.completed\"}\n\n";
        assertEquals("hello", ModelApi.consumeEvents(new java.io.ByteArrayInputStream(
                (event + completed).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        try {
            ModelApi.consumeEvents(new java.io.ByteArrayInputStream(
                    event.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            fail();
        } catch (java.io.IOException expected) {}
    }

    @Test public void routesOnlyMatchingIncomingImagesWhenEnabled() {
        assertTrue(BridgeRules.shouldRouteImage(3, 0, "contact", "contact", 1, 2, "path", true, true));
        assertFalse(BridgeRules.shouldRouteImage(34, 0, "contact", "contact", 1, 2, "path", true, true));
        assertFalse(BridgeRules.shouldRouteImage(3, 1, "contact", "contact", 1, 2, "path", true, true));
        assertFalse(BridgeRules.shouldRouteImage(3, 0, "a", "b", 1, 2, "path", true, true));
        assertFalse(BridgeRules.shouldRouteImage(3, 0, "a", "a", 0, 2, "path", true, true));
        assertFalse(BridgeRules.shouldRouteImage(3, 0, "a", "a", 1, 2, "path", false, true));
        assertFalse(BridgeRules.shouldRouteImage(3, 0, "a", "a", 1, 2, "path", true, false));
    }

    @Test public void convertsMatchingImageToResponsesInput() throws Exception {
        String marker = "[WXGPT_IMAGE:0123456789abcdef0123456789abcdef]";
        byte[] jpeg = new byte[]{(byte) 255, (byte) 216, (byte) 255, (byte) 217};
        JSONObject body = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", marker))
                .put(new JSONObject().put("role", "assistant").put("content", "old reply"))
                .put(new JSONObject().put("role", "user").put("content", marker)));
        JSONObject request = BridgeRules.makeResponseRequest(body.toString(), "vision-model", jpeg);
        JSONArray input = request.getJSONArray("input");
        assertEquals("[之前发送了一张图片]", input.getJSONObject(0).getString("content"));
        JSONArray parts = input.getJSONObject(2).getJSONArray("content");
        assertEquals("input_text", parts.getJSONObject(0).getString("type"));
        assertEquals("input_image", parts.getJSONObject(1).getString("type"));
        assertTrue(parts.getJSONObject(1).getString("image_url").startsWith("data:image/jpeg;base64,"));
        assertFalse(request.toString().contains(marker));
    }

    @Test public void rejectsMissingOrMismatchedImage() throws Exception {
        String marker = "[WXGPT_IMAGE:0123456789abcdef0123456789abcdef]";
        String imageBody = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", marker))).toString();
        String textBody = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", "hello"))).toString();
        byte[] jpeg = new byte[]{(byte) 255, (byte) 216, (byte) 255, (byte) 217};
        try { BridgeRules.makeResponseRequest(imageBody, "model"); fail(); }
        catch (org.json.JSONException expected) {}
        try { BridgeRules.makeResponseRequest(textBody, "model", jpeg); fail(); }
        catch (org.json.JSONException expected) {}
        try { BridgeRules.makeResponseRequest(imageBody, "model", new byte[8]); fail(); }
        catch (org.json.JSONException expected) {}
        try { BridgeRules.makeResponseRequest(imageBody, "model", new byte[BridgeRules.MAX_IMAGE_BYTES + 1]); fail(); }
        catch (org.json.JSONException expected) {}
    }

    @Test public void routesOnlyMatchingIncomingVoiceWhenEnabled() {
        assertTrue(BridgeRules.shouldRouteVoice(34, 0, "a", "a", 1, 2, "path", true, true));
        assertFalse(BridgeRules.shouldRouteVoice(3, 0, "a", "a", 1, 2, "path", true, true));
        assertFalse(BridgeRules.shouldRouteVoice(34, 1, "a", "a", 1, 2, "path", true, true));
        assertFalse(BridgeRules.shouldRouteVoice(34, 0, "a", "b", 1, 2, "path", true, true));
        assertFalse(BridgeRules.shouldRouteVoice(34, 0, "a", "a", 1, 0, "path", true, true));
        assertFalse(BridgeRules.shouldRouteVoice(34, 0, "a", "a", 1, 2, "path", true, false));
    }

    @Test public void requiresMatchingVoiceMarkerAndInjectsTranscript() throws Exception {
        String marker = "[WXGPT_VOICE:0123456789abcdef0123456789abcdef]";
        String body = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", marker))).toString();
        try { BridgeRules.makeResponseRequest(body, "model"); fail(); }
        catch (org.json.JSONException expected) {}
        String replaced = BridgeRules.replaceVoiceMarker(body, "weather question");
        JSONObject request = BridgeRules.makeResponseRequest(replaced, "model");
        String content = request.getJSONArray("input").getJSONObject(0).getString("content");
        assertTrue(content.contains("weather question"));
        assertFalse(content.contains(marker));
        try { BridgeRules.replaceVoiceMarker(body.replace("WXGPT_VOICE", "WXGPT_IMAGE"), "hello"); fail(); }
        catch (org.json.JSONException expected) {}
        try { BridgeRules.replaceVoiceMarker(body, " "); fail(); }
        catch (org.json.JSONException expected) {}
    }
}
