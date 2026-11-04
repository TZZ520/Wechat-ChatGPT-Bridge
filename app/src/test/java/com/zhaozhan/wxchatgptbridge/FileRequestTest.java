package com.zhaozhan.wxchatgptbridge;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class FileRequestTest {
    private static final String FILE = "[WXGPT_FILE:00000000000000000000000000000000]";
    private static final String PDF = "[WXGPT_PDF:00000000000000000000000000000000]";
    private static String body(String content) throws Exception {
        return new JSONObject().put("messages", new JSONArray().put(
                new JSONObject().put("role", "user").put("content", content))).toString();
    }

    @Test public void sendsOnlySanitizedFilenameAndMatchingMime() throws Exception {
        byte[] file = "hello".getBytes(StandardCharsets.UTF_8);
        JSONObject request = BridgeRules.makeResponseRequest(body(FILE), "model", null, file, "txt", 100);
        JSONObject inputFile = request.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(1);
        assertEquals("attachment.txt", inputFile.getString("filename"));
        assertTrue(inputFile.getString("file_data").startsWith("data:text/plain;base64,"));
    }

    @Test public void rejectsUnsupportedOversizedAndMismatchedFiles() throws Exception {
        byte[] txt = "hello".getBytes(StandardCharsets.UTF_8);
        try { BridgeRules.makeResponseRequest(body(FILE), "model", null, txt, "exe", 100); fail(); }
        catch (JSONException expected) { }
        try { BridgeRules.makeResponseRequest(body(FILE), "model", null, txt, "txt", 4); fail(); }
        catch (JSONException expected) { }
        try { BridgeRules.makeResponseRequest(body(PDF), "model", null, txt, "txt", 100); fail(); }
        catch (JSONException expected) { }
        try { BridgeRules.makeResponseRequest(body("普通消息"), "model", null, txt, "txt", 100); fail(); }
        catch (JSONException expected) { }
    }

    @Test public void acceptsOfficeZipAndLegacyPdf() throws Exception {
        byte[] zipHeader = new byte[]{'P', 'K', 3, 4, 0};
        JSONObject out = BridgeRules.makeResponseRequest(body(FILE), "model", null, zipHeader, "docx", 100);
        String data = out.getJSONArray("input").getJSONObject(0).getJSONArray("content")
                .getJSONObject(1).getString("file_data");
        assertTrue(data.startsWith("data:application/vnd.openxmlformats-officedocument.wordprocessingml.document;base64,"));
        assertNotNull(BridgeRules.makeResponseRequest(body(PDF), "model", null,
                "%PDF-test".getBytes(StandardCharsets.US_ASCII)));
    }
}
