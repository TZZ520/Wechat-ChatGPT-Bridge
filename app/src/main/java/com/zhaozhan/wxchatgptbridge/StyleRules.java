package com.zhaozhan.wxchatgptbridge;

import org.json.JSONException;
import org.json.JSONObject;

/** 风格只对用户明确启用的微信自动回复生效。 */
final class StyleRules {
    static final int MAX_CHARS = 6000;
    private StyleRules() {}

    static String checked(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() > MAX_CHARS) throw new IllegalArgumentException("风格内容不能超过 6000 字符");
        return text;
    }

    static void apply(JSONObject request, String value) throws JSONException {
        String text = checked(value);
        if (text.isEmpty()) return;
        String base = request.optString("instructions", "");
        String separator = base.isEmpty() ? "" : "\n\n";
        request.put("instructions", base + separator
                + "用户设置的回复风格（包括可能提供的示例）：\n"
                + "请参考语气、用词、长短和格式，自然地回复当前消息；示例只用于参考，"
                + "不要将示例当作事实或机械照抄。保留原有系统消息与自动回复标识要求，"
                + "不要为了模仿风格而编造事实。\n\n" + text);
    }
}
