package com.zhaozhan.wxchatgptbridge;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class ModelApi {
    static final class Model {
        final String slug;
        final String label;
        Model(String slug, String label) { this.slug = slug; this.label = label; }
        @Override public String toString() { return label + " (" + slug + ")"; }
    }

    private ModelApi() {}

    static List<Model> list(Context context) throws Exception {
        String token = OAuthClient.accessToken(context);
        JSONArray models = Http.getJson("https://api.openai.com/v1/models", token)
                .getJSONArray("models");
        List<Model> output = new ArrayList<>();
        for (int i = 0; i < models.length(); i++) {
            JSONObject entry = models.getJSONObject(i);
            String slug = entry.optString("slug", "");
            if (!"list".equals(entry.optString("visibility")) || slug.isEmpty()) continue;
            output.add(new Model(slug, entry.optString("display_name", slug)));
        }
        if (output.isEmpty()) throw new IOException("账号未返回可用模型");
        return output;
    }

    static String reply(Context context, String requestBody) throws Exception {
        return reply(context, requestBody, null);
    }

    static String reply(Context context, String requestBody, byte[] image) throws Exception {
        return reply(context, requestBody, image, null);
    }

    static String reply(Context context, String requestBody, byte[] image, byte[] pdf) throws Exception {
        return reply(context, requestBody, image, pdf, "pdf", PdfLoader.MAX_BYTES);
    }

    static String reply(Context context, String requestBody, byte[] image, byte[] file,
            String extension, int maxBytes) throws Exception {
        return reply(context, requestBody, image, file, extension, maxBytes, null);
    }

    static String reply(Context context, String requestBody, byte[] image, byte[] file,
            String extension, int maxBytes, HistoricalMedia previous) throws Exception {
        return replyInternal(context, requestBody, image, file, extension, maxBytes, previous, "");
    }

    static String replyWechat(Context context, String requestBody, byte[] image, byte[] file,
            String extension, int maxBytes, HistoricalMedia previous) throws Exception {
        return replyInternal(context, requestBody, image, file, extension, maxBytes, previous,
                StyleStore.active(context));
    }

    private static String replyInternal(Context context, String requestBody, byte[] image, byte[] file,
            String extension, int maxBytes, HistoricalMedia previous, String style) throws Exception {
        String mode = SessionStore.prefs(context).getString("web_search_mode", WebSearch.OFF);
        StreamResult answer = request(context, requestBody, image, file, extension, maxBytes, previous, mode, style);
        return new JSONObject().put("choices", new JSONArray().put(
                new JSONObject().put("message",
                        new JSONObject().put("role", "assistant").put("content", answer.text))))
                .toString();
    }

    /** 测试只发送合成问题，不使用或清空任何聊天记忆。 */
    static void testWebSearch(Context context) throws Exception {
        JSONObject body = new JSONObject().put("messages", new JSONArray().put(new JSONObject()
                .put("role", "user").put("content", "请联网查询英国气象局最新的伦敦天气预报，并列出来源。")));
        StreamResult result = request(context, body.toString(), null, null, "", PdfLoader.MAX_BYTES,
                null, WebSearch.ALWAYS, "");
        if (!result.searched) throw new IOException("模型未完成联网搜索");
        if (result.sources.isEmpty()) throw new IOException("联网有响应，但没有返回可核验的来源");
    }

    /** 试写只发送页面中输入的测试问题和草稿，不发送微信消息或读取聊天记忆。 */
    static String previewStyle(Context context, String value, String question) throws Exception {
        String style = StyleRules.checked(value);
        String input = question == null ? "" : question.trim();
        if (style.isEmpty()) throw new IOException("请先填写回复风格");
        if (input.isEmpty() || input.length() > 400) throw new IOException("请填写 400 字以内的测试消息");
        JSONObject body = new JSONObject().put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", "请为微信自动回复生成一条自然的试写，不要把示例当作事实。"))
                .put(new JSONObject().put("role", "user").put("content", input)));
        return request(context, body.toString(), null, null, "", PdfLoader.MAX_BYTES,
                null, WebSearch.OFF, style).text;
    }

    private static StreamResult request(Context context, String requestBody, byte[] image, byte[] file,
            String extension, int maxBytes, HistoricalMedia previous, String mode, String style) throws Exception {
        String model = SessionStore.prefs(context).getString("model_slug", "");
        JSONObject request = prepareRequest(requestBody, model, image, file, extension, maxBytes,
                previous, mode, style);
        String token = OAuthClient.accessToken(context);
        HttpURLConnection connection = (HttpURLConnection)
                new URL("https://api.openai.com/v1/responses").openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(120000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                if (status == 429) throw new IOException("ChatGPT 套餐额度或应用限制已达到");
                if (status == 401 || status == 403) throw new IOException("ChatGPT 授权或套餐不可用");
                if (status == 400 && !WebSearch.OFF.equals(WebSearch.mode(mode)))
                    throw new IOException("联网请求未被服务接受，请检查模型是否支持，或在设置中关闭联网后重试");
                throw new IOException("ChatGPT 服务返回 HTTP " + status);
            }
            return consumeDetailed(connection.getInputStream());
        } finally { connection.disconnect(); }
    }

    static JSONObject prepareRequest(String requestBody, String model, byte[] image, byte[] file,
            String extension, int maxBytes, HistoricalMedia previous, String mode, String style) throws Exception {
        JSONObject request = BridgeRules.makeResponseRequest(requestBody, model, image, file, extension, maxBytes, previous);
        WebSearch.configure(request, mode);
        StyleRules.apply(request, style);
        return request;
    }

    static String consumeEvents(java.io.InputStream stream) throws Exception {
        return consumeDetailed(stream).text;
    }

    static final class StreamResult {
        String text;
        boolean searched;
        final LinkedHashMap<String, String> sources = new LinkedHashMap<>();
    }

    static StreamResult consumeDetailed(java.io.InputStream stream) throws Exception {
        StringBuilder answer = new StringBuilder();
        boolean completed = false;
        StreamResult result = new StreamResult();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    stream, StandardCharsets.UTF_8))) {
                String line;
                StringBuilder data = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    if (line.length() > 100_000) throw new IOException("响应过大");
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            JSONObject event = new JSONObject(data.toString());
                            String type = event.optString("type");
                            if ("response.output_text.delta".equals(type)) {
                                answer.append(event.optString("delta", ""));
                                if (answer.length() > 100_000) throw new IOException("回复过长");
                            } else if ("response.web_search_call.completed".equals(type)) {
                                result.searched = true;
                            } else if ("response.output_text.annotation.added".equals(type)) {
                                addSource(result, event.optJSONObject("annotation"));
                            } else if ("response.output_item.done".equals(type)) {
                                readItem(result, event.optJSONObject("item"));
                            } else if ("response.completed".equals(type)) {
                                completed = true;
                                JSONObject response = event.optJSONObject("response");
                                JSONArray output = response == null ? null : response.optJSONArray("output");
                                if (output != null) for (int i = 0; i < output.length(); i++)
                                    readItem(result, output.optJSONObject(i));
                            } else if ("response.failed".equals(type) || "error".equals(type)) {
                                throw new IOException("ChatGPT 未能完成回复");
                            }
                            data.setLength(0);
                        }
                    } else if (line.startsWith("data:")) {
                        String part = line.substring(5).trim();
                        if ("[DONE]".equals(part)) continue;
                        if (data.length() > 0) data.append('\n');
                        data.append(part);
                        if (data.length() > 200_000) throw new IOException("响应过大");
                    }
                }
        }
        if (!completed) throw new IOException("ChatGPT 回复未完成，请重试");
        String text = answer.toString().trim();
        if (text.isEmpty()) throw new IOException("ChatGPT 未返回文字");
        if (result.searched && !result.sources.isEmpty()) {
            StringBuilder extra = new StringBuilder();
            for (java.util.Map.Entry<String, String> entry : result.sources.entrySet()) {
                if (text.contains(entry.getKey())) continue;
                if (extra.length() == 0) extra.append("\n\n来源：");
                extra.append("\n").append(entry.getValue()).append(" ").append(entry.getKey());
            }
            text += extra;
        }
        result.text = text;
        return result;
    }

    private static void readItem(StreamResult result, JSONObject item) {
        if (item == null) return;
        if ("web_search_call".equals(item.optString("type"))
                && "completed".equals(item.optString("status"))) result.searched = true;
        JSONArray content = item.optJSONArray("content");
        if (content == null) return;
        for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i);
            JSONArray annotations = part == null ? null : part.optJSONArray("annotations");
            if (annotations == null) continue;
            for (int j = 0; j < annotations.length(); j++) addSource(result, annotations.optJSONObject(j));
        }
    }

    private static void addSource(StreamResult result, JSONObject annotation) {
        if (annotation == null || !"url_citation".equals(annotation.optString("type"))
                || result.sources.size() >= 5) return;
        JSONObject nested = annotation.optJSONObject("url_citation");
        JSONObject value = nested == null ? annotation : nested;
        String url = value.optString("url", "");
        try {
            URI parsed = new URI(url);
            if (url.length() > 2048 || parsed.getHost() == null || parsed.getUserInfo() != null
                    || !("http".equalsIgnoreCase(parsed.getScheme())
                         || "https".equalsIgnoreCase(parsed.getScheme()))) return;
            String title = value.optString("title", "网页").replaceAll("[\\r\\n\\t]", " ").trim();
            if (title.isEmpty()) title = "网页";
            if (title.length() > 100) title = title.substring(0, 100);
            result.sources.put(url, title);
        } catch (Exception ignored) { }
    }
}
