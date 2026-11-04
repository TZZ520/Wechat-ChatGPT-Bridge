package com.zhaozhan.wxchatgptbridge;

import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class BridgeRules {
    static final String NETWORK_CLASS = "b.\u2071\u1d54.\u02ca\u2071$";
    static final String AUTOREPLY_CLASS = "b.\u02c8\u02c8.\u02ca\u02cf";
    private static final byte[] CORE_MARKER = "openai_chatgpt_api_url"
            .getBytes(StandardCharsets.US_ASCII);
    static final String IMAGE_PREFIX = "[WXGPT_IMAGE:";
    static final String VOICE_PREFIX = "[WXGPT_VOICE:";
    static final String PDF_PREFIX = "[WXGPT_PDF:";
    static final String FILE_PREFIX = "[WXGPT_FILE:";
    static final int MAX_IMAGE_BYTES = 350_000;

    private BridgeRules() {}

    static boolean isMicroChatRequest(URL url, StackTraceElement[] stack) {
        if (url == null || !"https".equalsIgnoreCase(url.getProtocol())
                || !"api.openai.com".equalsIgnoreCase(url.getHost())
                || !"/v1/chat/completions".equals(url.getPath())
                || url.getQuery() != null || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)) return false;
        return isMicroNetwork(stack);
    }

    static boolean isMicroNetwork(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack)
            if (frame.getClassName().startsWith(NETWORK_CLASS)) return true;
        return false;
    }

    static boolean isAutoReplyCaller(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack)
            if (AUTOREPLY_CLASS.equals(frame.getClassName())
                    && "\u02c6\u0674".equals(frame.getMethodName())) return true;
        return false;
    }

    static boolean isCoreDex(ByteBuffer buffer) {
        if (buffer == null) return false;
        ByteBuffer view = buffer.asReadOnlyBuffer();
        view.clear();
        for (int i = 0; i <= view.limit() - CORE_MARKER.length; i++) {
            int j = 0;
            while (j < CORE_MARKER.length && view.get(i + j) == CORE_MARKER[j]) j++;
            if (j == CORE_MARKER.length) return true;
        }
        return false;
    }

    static JSONObject makeResponseRequest(String body, String selectedModel) throws JSONException {
        return makeResponseRequest(body, selectedModel, null);
    }

    static boolean isImageMarker(String value) {
        return value != null && value.matches("\\[WXGPT_IMAGE:[0-9a-f]{32}\\]");
    }

    static boolean isVoiceMarker(String value) {
        return value != null && value.matches("\\[WXGPT_VOICE:[0-9a-f]{32}\\]");
    }

    static boolean isPdfMarker(String value) {
        return value != null && value.matches("\\[WXGPT_PDF:[0-9a-f]{32}\\]");
    }

    static boolean isFileMarker(String value) {
        return value != null && value.matches("\\[WXGPT_FILE:[0-9a-f]{32}\\]");
    }

    static boolean isFileMessageType(int type) { return (type & 0xffff) == 49; }

    static boolean shouldRouteVoice(int type, int sent, String eventTalker, String replyTalker,
            long localId, long serverId, String voicePath, boolean bridgeEnabled, boolean voiceEnabled) {
        return bridgeEnabled && voiceEnabled && type == 34 && sent == 0
                && localId > 0 && serverId > 0 && voicePath != null && !voicePath.isEmpty()
                && eventTalker != null && !eventTalker.isEmpty() && eventTalker.equals(replyTalker);
    }

    static String replaceVoiceMarker(String body, String transcript) throws JSONException {
        if (body == null || body.length() > 150_000 || transcript == null
                || transcript.trim().isEmpty() || transcript.length() > 20_000)
            throw new JSONException("语音转写不可用");
        JSONObject chat = new JSONObject(body);
        JSONArray messages = chat.getJSONArray("messages");
        if (messages.length() == 0) throw new JSONException("语音没有对应消息");
        JSONObject last = messages.getJSONObject(messages.length() - 1);
        if (!"user".equals(last.optString("role")) || !isVoiceMarker(last.optString("content")))
            throw new JSONException("语音与消息不匹配");
        last.put("content", "对方发来一条语音，转写内容如下：" + transcript.trim());
        return chat.toString();
    }

    static boolean shouldRouteImage(int type, int sent, String eventTalker, String replyTalker,
            long localId, long serverId, String imagePath, boolean bridgeEnabled, boolean imagesEnabled) {
        return bridgeEnabled && imagesEnabled && type == 3 && sent == 0
                && localId > 0 && serverId > 0 && imagePath != null && !imagePath.isEmpty()
                && eventTalker != null && !eventTalker.isEmpty() && eventTalker.equals(replyTalker);
    }

    static JSONObject makeResponseRequest(String body, String selectedModel, byte[] image)
            throws JSONException {
        return makeResponseRequest(body, selectedModel, image, null);
    }

    static JSONObject makeResponseRequest(String body, String selectedModel, byte[] image, byte[] pdf)
            throws JSONException {
        return makeResponseRequest(body, selectedModel, image, pdf, "pdf", PdfLoader.MAX_BYTES);
    }

    static JSONObject makeResponseRequest(String body, String selectedModel, byte[] image, byte[] file,
            String fileExtension, int maxFileBytes) throws JSONException {
        return makeResponseRequest(body, selectedModel, image, file, fileExtension, maxFileBytes, null);
    }

    static JSONObject makeResponseRequest(String body, String selectedModel, byte[] image, byte[] file,
            String fileExtension, int maxFileBytes, HistoricalMedia previous) throws JSONException {
        if (body == null || body.length() > 150_000) throw new JSONException("请求过大");
        if (selectedModel == null || selectedModel.trim().isEmpty())
            throw new JSONException("请先选择模型");
        if (image != null && (image.length < 4 || image.length > MAX_IMAGE_BYTES
                || (image[0] & 255) != 255 || (image[1] & 255) != 216))
            throw new JSONException("图片大小不受支持");
        if (image != null && file != null) throw new JSONException("媒体消息类型不匹配");
        String ext = FileRules.normalized(fileExtension);
        int limit = Math.min(maxFileBytes, FileRules.maxBytes(FileRules.MAX_MB));
        if (file != null && !FileRules.validDocument(file, ext, limit))
            throw new JSONException("文件大小或格式不受支持");
        JSONObject chat = new JSONObject(body);
        JSONArray input = new JSONArray();
        StringBuilder instructions = new StringBuilder();
        JSONArray messages = chat.getJSONArray("messages");
        boolean imageAttached = false;
        boolean fileAttached = false;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject item = messages.getJSONObject(i);
            String role = item.getString("role");
            Object content = item.opt("content");
            if (!(content instanceof String)) throw new JSONException("暂不支持非文字消息");
            if ("system".equals(role) || "developer".equals(role)) {
                if (instructions.length() > 0) instructions.append("\n\n");
                instructions.append((String) content);
            } else if ("user".equals(role) || "assistant".equals(role)) {
                boolean marker = "user".equals(role) && isImageMarker((String) content);
                boolean voiceMarker = "user".equals(role) && isVoiceMarker((String) content);
                boolean pdfMarker = "user".equals(role) && isPdfMarker((String) content);
                boolean fileMarker = "user".equals(role) && isFileMarker((String) content);
                boolean currentImage = marker && i == messages.length() - 1;
                boolean currentFile = (pdfMarker || fileMarker) && i == messages.length() - 1;
                if (voiceMarker && i == messages.length() - 1) throw new JSONException("语音转写不可用");
                if (currentImage && image == null) throw new JSONException("图片不可用");
                if (image != null && i == messages.length() - 1 && !currentImage)
                    throw new JSONException("图片与消息不匹配");
                if (currentFile && file == null) throw new JSONException("文件不可用");
                if (file != null && i == messages.length() - 1 && !currentFile)
                    throw new JSONException("文件与消息不匹配");
                if (currentFile && ((pdfMarker && !"pdf".equals(ext)) || (fileMarker && "pdf".equals(ext))))
                    throw new JSONException("文件类型与消息不匹配");
                boolean pastSlot = previous != null && image == null && file == null && "user".equals(role)
                        && previous.messageIndex == i && i < messages.length() - 1;
                boolean pastImage = pastSlot && previous.image && "[对方之前发送了一张图片]".equals(content)
                        && previous.bytes != null && previous.bytes.length >= 4
                        && previous.bytes.length <= MAX_IMAGE_BYTES && (previous.bytes[0] & 255) == 255
                        && (previous.bytes[1] & 255) == 216;
                boolean pastFile = pastSlot && !previous.image && previous.bytes != null
                        && ("[对方之前发送了一份 PDF]".equals(content) || "[对方之前发送了一份文件]".equals(content))
                        && FileRules.validDocument(previous.bytes, previous.extension, limit);
                if (pastImage) {
                    JSONArray parts = new JSONArray()
                            .put(new JSONObject().put("type", "input_text").put("text", "这是同一会话之前发送的图片。"))
                            .put(new JSONObject().put("type", "input_image").put("image_url",
                                    "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(previous.bytes)));
                    input.put(new JSONObject().put("role", role).put("content", parts));
                } else if (pastFile) {
                    JSONArray parts = new JSONArray()
                            .put(new JSONObject().put("type", "input_text").put("text", "这是同一会话之前发送的文件。"))
                            .put(new JSONObject().put("type", "input_file").put("filename", "attachment." + previous.extension)
                                    .put("file_data", "data:" + FileRules.mime(previous.extension) + ";base64,"
                                            + Base64.getEncoder().encodeToString(previous.bytes)));
                    input.put(new JSONObject().put("role", role).put("content", parts));
                } else if (currentImage) {
                    imageAttached = true;
                    JSONArray parts = new JSONArray()
                            .put(new JSONObject().put("type", "input_text")
                                    .put("text", "对方发来了一张图片或表情包；动态图片可能按顺序展示多个画面，请结合内容自然地回复。特殊互动表情若只有示意图，无法确定猜拳或骰子的随机结果，不要猜测。"))
                            .put(new JSONObject().put("type", "input_image").put("image_url",
                                    "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image)));
                    input.put(new JSONObject().put("role", role).put("content", parts));
                } else if (currentFile) {
                    fileAttached = true;
                    JSONArray parts = new JSONArray()
                            .put(new JSONObject().put("type", "input_text")
                                    .put("text", "对方发来了一份文件，请阅读并结合文件内容自然地回复。"))
                            .put(new JSONObject().put("type", "input_file").put("filename", "attachment." + ext)
                                    .put("file_data", "data:" + FileRules.mime(ext) + ";base64,"
                                            + Base64.getEncoder().encodeToString(file)));
                    input.put(new JSONObject().put("role", role).put("content", parts));
                } else {
                    input.put(new JSONObject().put("role", role)
                            .put("content", marker ? "[之前发送了一张图片]"
                                    : voiceMarker ? "[之前发送了一条语音]"
                                    : pdfMarker ? "[之前发送了一份 PDF]"
                                    : fileMarker ? "[之前发送了一份文件]" : content));
                }
            } else {
                throw new JSONException("消息角色不受支持");
            }
        }
        if (image != null && !imageAttached) throw new JSONException("图片没有对应消息");
        if (file != null && !fileAttached) throw new JSONException("文件没有对应消息");
        if (input.length() == 0) throw new JSONException("没有可处理的消息");
        JSONObject out = new JSONObject().put("model", selectedModel)
                .put("input", input).put("store", false).put("stream", true);
        if (instructions.length() > 0) out.put("instructions", instructions.toString());
        return out;
    }
}
