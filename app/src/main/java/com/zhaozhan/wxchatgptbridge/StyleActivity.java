package com.zhaozhan.wxchatgptbridge;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import org.json.JSONObject;

/** 提示词仅由用户填写；试写不读取微信聊天、不发送给联系人。 */
public final class StyleActivity extends Activity {
    private static final int MUTED = 0xffbdbdbd;
    private EditText style, question;
    private Switch enabled;
    private TextView result, error;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.BLACK);
        int margin = dp(20);
        panel.setPadding(margin, dp(28), margin, dp(28));
        scroll.addView(panel);
        setContentView(scroll);

        panel.addView(label("回复风格", 24, Color.WHITE));
        TextView description = label("写下你习惯的语气、长度和用词，也可以提供几组“对方说／我会怎么回”的示例。它是提示词，不会训练或改变模型。", 15, MUTED);
        pad(description, 12, 12);
        panel.addView(description);

        enabled = new Switch(this);
        enabled.setText("启用我的回复风格");
        enabled.setTextSize(16);
        pad(enabled, 8, 6);
        panel.addView(enabled);
        TextView note = label("更改后请点保存。启用后，风格会随每次微信自动回复请求发送给 OpenAI；保留微叉原有系统消息和自动回复标识。", 14, MUTED);
        pad(note, 0, 12);
        panel.addView(note);

        panel.addView(label("风格说明与示例", 16, Color.WHITE));
        style = new EditText(this);
        style.setTextColor(Color.WHITE);
        style.setHintTextColor(MUTED);
        style.setTextSize(15);
        style.setGravity(Gravity.TOP | Gravity.START);
        style.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        style.setMinLines(6);
        style.setMaxLines(18);
        style.setFilters(new InputFilter[]{new InputFilter.LengthFilter(StyleRules.MAX_CHARS)});
        style.setHint("例如：平时说话简短、自然，少用正式套话。\n\n对方：今天忙吗？\n我：有点忙，晚点回你哈。\n\n请替换成你自己的习惯和示例。");
        panel.addView(style, new LinearLayout.LayoutParams(-1, -2));
        TextView privacy = label("风格内容加密保存在本机；输入的示例会随请求发送给 OpenAI。建议先去掉姓名、电话和其他隐私。", 14, MUTED);
        pad(privacy, 4, 10);
        panel.addView(privacy);

        Button save = button("保存风格设置");
        panel.addView(save);
        error = label("", 14, 0xffff8a80);
        panel.addView(error);
        save.setOnClickListener(v -> {
            try {
                StyleStore.save(this, style.getText().toString(), enabled.isChecked());
                error.setText("");
                toast(enabled.isChecked() ? "已保存，下一条自动回复生效" : "已保存，风格未启用");
            } catch (IllegalArgumentException ex) {
                toast(ex.getMessage());
            } catch (Exception ex) {
                error.setText("保存失败，原有设置未更改");
                toast("风格设置保存失败");
            }
        });

        TextView testLabel = label("试写预览", 18, Color.WHITE);
        pad(testLabel, 20, 4);
        panel.addView(testLabel);
        TextView testHint = label("使用当前编辑框中的内容试写，无需先保存；不会发送给微信联系人，也不会读取微叉系统消息或聊天记忆，所以实际回复可能不同。试写会使用 ChatGPT 额度。", 14, MUTED);
        pad(testHint, 0, 4);
        panel.addView(testHint);
        question = new EditText(this);
        question.setTextColor(Color.WHITE);
        question.setHintTextColor(MUTED);
        question.setTextSize(15);
        question.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        question.setFilters(new InputFilter[]{new InputFilter.LengthFilter(400)});
        question.setHint("输入一条测试消息，例如：你今天忙吗？");
        panel.addView(question, new LinearLayout.LayoutParams(-1, -2));
        Button test = button("试写预览（不发微信）");
        panel.addView(test);
        result = label("", 15, Color.WHITE);
        result.setTextIsSelectable(true);
        pad(result, 8, 12);
        panel.addView(result);
        test.setOnClickListener(v -> {
            String draft = style.getText().toString();
            String message = question.getText().toString();
            if (draft.trim().isEmpty()) { toast("请先填写回复风格"); return; }
            if (message.trim().isEmpty()) { toast("请先输入测试消息"); return; }
            try {
                JSONObject session = SessionStore.read(this);
                if (session == null || session.optString("refresh_token", "").isEmpty()) {
                    toast("请先登录 ChatGPT"); return;
                }
            } catch (Exception e) { toast("无法读取登录状态"); return; }
            if (SessionStore.prefs(this).getString("model_slug", "").isEmpty()) {
                toast("请先选择模型"); return;
            }
            test.setEnabled(false);
            result.setText("正在试写…");
            new Thread(() -> {
                try {
                    String answer = ModelApi.previewStyle(getApplicationContext(), draft, message);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        test.setEnabled(true);
                        result.setText("试写结果：\n" + answer);
                    });
                } catch (Exception e) {
                    String messageText = e instanceof IOException && e.getMessage() != null
                            ? e.getMessage() : "试写失败，请检查接入状态";
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        test.setEnabled(true);
                        result.setText(messageText);
                    });
                }
            }, "chatgpt-style-preview").start();
        });

        try {
            String stored = StyleStore.read(this);
            style.setText(state == null ? stored : state.getString("draft", stored));
            enabled.setChecked(state == null ? StyleStore.enabled(this)
                    : state.getBoolean("enabled", StyleStore.enabled(this)));
            if (state != null) question.setText(state.getString("question", ""));
            error.setText(StyleStore.lastError(this));
        } catch (Exception e) {
            error.setText("已保存的风格暂时无法读取，原自动回复不受影响");
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("draft", style.getText().toString());
        out.putBoolean("enabled", enabled.isChecked());
        out.putString("question", question.getText().toString());
        super.onSaveInstanceState(out);
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
    private Button button(String value) {
        Button view = new Button(this);
        view.setText(value);
        view.setAllCaps(false);
        view.setTextColor(Color.WHITE);
        view.setBackgroundTintList(ColorStateList.valueOf(0xff222222));
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void pad(View view, int top, int bottom) { view.setPadding(0, dp(top), 0, dp(bottom)); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
