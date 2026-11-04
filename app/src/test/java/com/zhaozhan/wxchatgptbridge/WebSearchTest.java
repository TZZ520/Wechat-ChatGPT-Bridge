package com.zhaozhan.wxchatgptbridge;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;

public class WebSearchTest {
    @Test public void onlyAddsSearchWhenEnabled() throws Exception {
        JSONObject off = new JSONObject().put("instructions", "原有规则");
        WebSearch.configure(off, "off");
        assertFalse(off.has("tools"));
        assertEquals("原有规则", off.getString("instructions"));
        JSONObject auto = new JSONObject().put("instructions", "原有规则");
        WebSearch.configure(auto, "auto");
        assertEquals("web_search", auto.getJSONArray("tools").getJSONObject(0).getString("type"));
        assertEquals("auto", auto.getString("tool_choice"));
        assertTrue(auto.getString("instructions").contains("原有规则"));
        JSONObject required = new JSONObject();
        WebSearch.configure(required, "always");
        assertEquals("required", required.getString("tool_choice"));
        assertEquals("off", WebSearch.mode("unexpected"));
    }

    @Test public void onlyReportsCompletedSearchWithValidatedSources() throws Exception {
        String events = "data: {\"type\":\"response.web_search_call.completed\"}\n\n"
                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"天气有雨\"}\n\n"
                + "data: {\"type\":\"response.output_text.annotation.added\",\"annotation\":{\"type\":\"url_citation\",\"url\":\"https://example.org/weather\",\"title\":\"天气来源\"}}\n\n"
                + "data: {\"type\":\"response.output_text.annotation.added\",\"annotation\":{\"type\":\"url_citation\",\"url\":\"https://example.org/weather\",\"title\":\"天气来源\"}}\n\n"
                + "data: {\"type\":\"response.output_text.annotation.added\",\"annotation\":{\"type\":\"url_citation\",\"url\":\"https://secret@example.org/\",\"title\":\"无效\"}}\n\n"
                + "data: {\"type\":\"response.completed\"}\n\n";
        ModelApi.StreamResult result = ModelApi.consumeDetailed(new ByteArrayInputStream(events.getBytes(StandardCharsets.UTF_8)));
        assertTrue(result.searched);
        assertEquals(1, result.sources.size());
        assertTrue(result.text.contains("天气有雨"));
        assertTrue(result.text.contains("https://example.org/weather"));
        assertFalse(result.text.contains("secret@"));
    }

    @Test public void noSearchEventDoesNotInventSources() throws Exception {
        String events = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"请告诉我地点\"}\n\n"
                + "data: {\"type\":\"response.completed\"}\n\n";
        ModelApi.StreamResult result = ModelApi.consumeDetailed(new ByteArrayInputStream(events.getBytes(StandardCharsets.UTF_8)));
        assertFalse(result.searched);
        assertEquals("请告诉我地点", result.text);
    }
}
