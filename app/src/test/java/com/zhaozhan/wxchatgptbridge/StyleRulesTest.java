package com.zhaozhan.wxchatgptbridge;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class StyleRulesTest {
    private String textBody() throws Exception {
        return new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", "保留原有系统提示"))
                .put(new JSONObject().put("role", "user").put("content", "你好"))).toString();
    }

    @Test public void emptyStylePreservesOriginalRequest() throws Exception {
        String body = textBody();
        JSONObject baseline = BridgeRules.makeResponseRequest(body, "model");
        JSONObject result = ModelApi.prepareRequest(body, "model", null, null, "",
                PdfLoader.MAX_BYTES, null, "off", "  ");
        assertEquals(baseline.toString(), result.toString());
    }

    @Test public void styleAndExamplesPreserveOriginalMessageAndWebSearch() throws Exception {
        String style = "口语、简短\n对方：忙吗？\n我：有点忙，晚点说。";
        JSONObject result = ModelApi.prepareRequest(textBody(), "model", null, null, "",
                PdfLoader.MAX_BYTES, null, "auto", style);
        String instructions = result.getString("instructions");
        assertTrue(instructions.contains("保留原有系统提示"));
        assertTrue(instructions.contains("联网搜索"));
        assertTrue(instructions.contains(style));
        assertTrue(instructions.contains("不要将示例当作事实"));
        assertEquals("web_search", result.getJSONArray("tools").getJSONObject(0).getString("type"));
        assertEquals("你好", result.getJSONArray("input").getJSONObject(0).getString("content"));
        assertFalse(result.getBoolean("store"));
        assertTrue(result.getBoolean("stream"));
    }

    @Test public void styleKeepsImageInputIntact() throws Exception {
        String marker = BridgeRules.IMAGE_PREFIX + "00000000000000000000000000000000]";
        String body = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", marker))).toString();
        byte[] jpeg = new byte[]{(byte) 255, (byte) 216, 1, 2};
        JSONObject result = ModelApi.prepareRequest(body, "model", jpeg, null, "",
                PdfLoader.MAX_BYTES, null, "off", "说话自然点");
        assertTrue(result.getString("instructions").contains("说话自然点"));
        JSONArray content = result.getJSONArray("input").getJSONObject(0).getJSONArray("content");
        assertEquals("input_image", content.getJSONObject(1).getString("type"));
        assertTrue(content.getJSONObject(1).getString("image_url").startsWith("data:image/jpeg;base64,"));
    }

    @Test public void oversizedStyleIsRejected() {
        try {
            StyleRules.checked("a".repeat(StyleRules.MAX_CHARS + 1));
            fail("长度超限必须拒绝");
        } catch (IllegalArgumentException expected) { }
        assertEquals("", StyleRules.checked("  \n"));
    }
}
