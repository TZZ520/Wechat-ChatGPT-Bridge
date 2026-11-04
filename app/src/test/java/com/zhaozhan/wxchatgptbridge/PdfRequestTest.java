package com.zhaozhan.wxchatgptbridge;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class PdfRequestTest {
    private static final String MARKER = "[WXGPT_PDF:00000000000000000000000000000000]";
    private static String body(String content) throws Exception {
        return new JSONObject().put("messages", new JSONArray().put(
                new JSONObject().put("role", "user").put("content", content))).toString();
    }
    @Test public void attachesPdfToMatchingCurrentMessage() throws Exception {
        byte[] pdf = "%PDF-1.4 test".getBytes(StandardCharsets.US_ASCII);
        JSONObject out = BridgeRules.makeResponseRequest(body(MARKER), "model", null, pdf);
        JSONArray parts = out.getJSONArray("input").getJSONObject(0).getJSONArray("content");
        assertEquals("input_file", parts.getJSONObject(1).getString("type"));
        assertTrue(parts.getJSONObject(1).getString("file_data").startsWith("data:application/pdf;base64,"));
    }
    @Test public void rejectsMissingOrMismatchedPdf() throws Exception {
        try { BridgeRules.makeResponseRequest(body(MARKER), "model", null, null); fail(); }
        catch (JSONException expected) { }
        try { BridgeRules.makeResponseRequest(body("普通消息"), "model", null, "%PDF-1.4".getBytes(StandardCharsets.US_ASCII)); fail(); }
        catch (JSONException expected) { }
        try { BridgeRules.makeResponseRequest(body(MARKER), "model", null, "not a pdf".getBytes(StandardCharsets.US_ASCII)); fail(); }
        catch (JSONException expected) { }
    }
}
