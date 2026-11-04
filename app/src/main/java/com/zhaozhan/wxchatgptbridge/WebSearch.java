package com.zhaozhan.wxchatgptbridge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 联网仅在用户启用时添加，未启用时保持原有请求。 */
final class WebSearch {
    static final String OFF = "off", AUTO = "auto", ALWAYS = "always";
    private WebSearch() {}

    static String mode(String value) {
        return AUTO.equals(value) || ALWAYS.equals(value) ? value : OFF;
    }

    static void configure(JSONObject request, String selected) throws JSONException {
        String mode = mode(selected);
        if (OFF.equals(mode)) return;
        request.put("tools", new JSONArray().put(new JSONObject().put("type", "web_search")));
        request.put("tool_choice", ALWAYS.equals(mode) ? "required" : "auto");
        String instruction = request.optString("instructions", "");
        request.put("instructions", instruction + "\n对于实时天气、新闻等时效性问题，"
                + "需要时使用联网搜索并提供来源；地点不明确时先询问。"
                + "仅在搜索成功时声称已联网，无法确认的信息不要编造。天气预报要说明日期和不确定性。");
    }
}
