package com.zhaozhan.wxchatgptbridge;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** 合成测试只操作独立的临时偏好，不改用户的风格或聊天记忆。 */
final class StyleDiagnostics {
    private StyleDiagnostics() {}

    static void local(Context context) throws Exception {
        Context base = context.getApplicationContext();
        String name = "style_selftest_" + UUID.randomUUID().toString().replace("-", "");
        Context isolated = new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String ignored, int mode) {
                return base.getSharedPreferences(name, mode);
            }
        };
        String sample = "测试风格：简短自然。";
        try {
            StyleStore.save(isolated, sample, true);
            String encrypted = SessionStore.prefs(isolated).getString("encrypted_reply_style", "");
            if (encrypted.isEmpty() || encrypted.contains(sample)
                    || !sample.equals(StyleStore.read(isolated))
                    || !sample.equals(StyleStore.active(isolated))) throw new IllegalStateException("风格保存自检失败");
            StyleStore.save(isolated, sample, false);
            if (!StyleStore.active(isolated).isEmpty() || !sample.equals(StyleStore.read(isolated)))
                throw new IllegalStateException("风格开关自检失败");
            String body = new JSONObject().put("messages", new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", "原提示"))
                    .put(new JSONObject().put("role", "user").put("content", "你好"))).toString();
            JSONObject request = ModelApi.prepareRequest(body, "selftest", null, null, "",
                    PdfLoader.MAX_BYTES, null, WebSearch.AUTO, sample);
            if (!request.getString("instructions").contains("原提示")
                    || !request.getString("instructions").contains(sample)
                    || !request.has("tools")) throw new IllegalStateException("风格请求自检失败");
        } finally { base.deleteSharedPreferences(name); }
    }

    static boolean preview(Context context) throws Exception {
        String reply = ModelApi.previewStyle(context,
                "这是一次风格试写测试，请整条回复只输出：晴雨同行", "你今天忙吗？");
        return reply.contains("晴雨同行");
    }
}
