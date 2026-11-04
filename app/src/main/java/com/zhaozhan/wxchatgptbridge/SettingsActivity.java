package com.zhaozhan.wxchatgptbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public final class SettingsActivity extends Activity {
    private TextView account, error, searchStatus;
    private Button signIn, refresh, signOut, styleEntry;
    private CompoundButton enabled, images, history, voice, pdfs, stickers;
    private Spinner models, historyTurns, fileLimit, searchMode;
    private static final int[] FILE_LIMITS = {1, 2, 5, 10, 20};
    private static final String[] SEARCH_MODES = {WebSearch.OFF, WebSearch.AUTO, WebSearch.ALWAYS};
    private static final int MUTED = 0xffbdbdbd;
    private List<ModelApi.Model> modelItems = new ArrayList<>();
    private boolean updating;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try { GrantVisibility.grant(this, "com.tencent.mm"); }
        catch (Exception e) {
            SessionStore.prefs(this).edit().putString("last_error", "无法授权微信访问接入服务").apply();
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        scroll.setBackgroundColor(Color.BLACK);
        panel.setBackgroundColor(Color.BLACK);
        panel.setOrientation(LinearLayout.VERTICAL);
        int margin = dp(20);
        panel.setPadding(margin, dp(28), margin, dp(28));
        scroll.addView(panel);
        setContentView(scroll);

        TextView title = text("微插 ChatGPT 接入", 25, Color.WHITE);
        title.setGravity(Gravity.START);
        panel.addView(title);
        TextView description = text("登录符合条件的 ChatGPT 账号，选择模型后，微插的聊天机器人可使用该账号的套餐额度。自动回复由微插原有开关控制。", 15, MUTED);
        pad(description, 14, 18);
        panel.addView(description);
        account = text("", 16, Color.WHITE);
        pad(account, 8, 12);
        panel.addView(account);

        signIn = button("使用 ChatGPT 登录");
        panel.addView(signIn);
        signIn.setOnClickListener(v -> {
            signIn.setEnabled(false);
            OAuthClient.start(this, message -> {
                signIn.setEnabled(true);
                if (message != null) toast(message);
                else {
                    ConversationMemory.clear();
                    toast("登录成功，请选择模型");
                    refreshModels();
                }
                refreshState();
            });
        });

        enabled = new android.widget.Switch(this);
        enabled.setText("允许微插使用 ChatGPT 套餐");
        enabled.setTextSize(16);
        pad((View) enabled, 18, 12);
        panel.addView((View) enabled);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            if (checked && !hasSession()) {
                updating = true;
                button.setChecked(false);
                updating = false;
                toast("请先登录 ChatGPT");
                return;
            }
            SessionStore.prefs(this).edit().putBoolean("enabled", checked).apply();
        });

        styleEntry = button("回复风格（未启用）");
        panel.addView(styleEntry);
        styleEntry.setOnClickListener(v -> startActivity(new Intent(this, StyleActivity.class)));
        TextView styleHint = text("设置语气和真实示例，先试写再启用；与微叉的系统消息共同生效。", 14, MUTED);
        pad(styleHint, 0, 8);
        panel.addView(styleHint);

        images = new android.widget.Switch(this);
        images.setText("识别收到的图片并自动回复");
        images.setTextSize(16);
        pad((View) images, 12, 4);
        panel.addView((View) images);
        images.setOnCheckedChangeListener((button, checked) -> {
            if (!updating) SessionStore.prefs(this).edit().putBoolean("image_enabled", checked).apply();
        });
        TextView imageHint = text("开启后，符合微插自动回复规则的图片会发送给 OpenAI 识别；需要所选模型支持图片。", 14, MUTED);
        pad(imageHint, 0, 8);
        panel.addView(imageHint);

        stickers = new android.widget.Switch(this);
        stickers.setText("识别表情包和动图");
        stickers.setTextSize(16);
        pad((View) stickers, 12, 4);
        panel.addView((View) stickers);
        stickers.setOnCheckedChangeListener((button, checked) -> {
            if (!updating) SessionStore.prefs(this).edit().putBoolean("sticker_enabled", checked).apply();
        });
        TextView stickerHint = text("需要同时开启图片识别；动图会提取画面，部分互动表情只有示意图，无法确定随机结果。", 14, MUTED);
        pad(stickerHint, 0, 8);
        panel.addView(stickerHint);

        voice = new android.widget.Switch(this);
        voice.setText("使用微信语音转文字后自动回复");
        voice.setTextSize(16);
        pad((View) voice, 12, 4);
        panel.addView((View) voice);
        voice.setOnCheckedChangeListener((button, checked) -> {
            if (!updating) SessionStore.prefs(this).edit().putBoolean("wechat_voice_enabled", checked).apply();
        });
        TextView voiceHint = text("微信会将语音交给微信语音服务处理，转出的文字再发送给 OpenAI；转写可能有误。", 14, MUTED);
        pad(voiceHint, 0, 8);
        panel.addView(voiceHint);

        pdfs = new android.widget.Switch(this);
        pdfs.setText("识别已下载的文件并自动回复");
        pdfs.setTextSize(16);
        pad((View) pdfs, 12, 4);
        panel.addView((View) pdfs);
        pdfs.setOnCheckedChangeListener((button, checked) -> {
            if (!updating) {
                SessionStore.prefs(this).edit().putBoolean("pdf_enabled", checked).apply();
                fileLimit.setEnabled(checked);
            }
        });
        TextView sizeLabel = text("文件大小上限", 16, Color.WHITE);
        pad(sizeLabel, 8, 2);
        panel.addView(sizeLabel);
        fileLimit = new Spinner(this);
        fileLimit.setPopupBackgroundDrawable(new ColorDrawable(Color.BLACK));
        ArrayList<String> sizeOptions = new ArrayList<>();
        for (int limit : FILE_LIMITS) sizeOptions.add(limit + " MB");
        ArrayAdapter<String> sizeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, sizeOptions);
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fileLimit.setAdapter(sizeAdapter);
        fileLimit.setSelection(fileLimitIndex(SessionStore.prefs(this)
                .getInt("file_max_mb", FileRules.DEFAULT_MB)));
        panel.addView(fileLimit, new LinearLayout.LayoutParams(-1, dp(48)));
        fileLimit.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                    int position, long id) {
                if (updating || position < 0 || position >= FILE_LIMITS.length) return;
                int limit = FILE_LIMITS[position];
                android.content.SharedPreferences prefs = SessionStore.prefs(SettingsActivity.this);
                if (prefs.getInt("file_max_mb", FileRules.DEFAULT_MB) != limit)
                    prefs.edit().putInt("file_max_mb", limit).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        TextView pdfHint = text("支持 PDF、Word、表格、幻灯片及常见文本文件；接收后会尝试自动下载，内容会发送给 OpenAI。", 14, MUTED);
        pad(pdfHint, 0, 8);
        panel.addView(pdfHint);

        history = new android.widget.Switch(this);
        history.setText("记住每个聊天的上下文");
        history.setTextSize(16);
        pad((View) history, 12, 4);
        panel.addView((View) history);
        history.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            SessionStore.prefs(this).edit().putBoolean("history_enabled", checked).apply();
            historyTurns.setEnabled(checked);
            if (!checked) ConversationMemory.clear();
        });
        TextView historyLabel = text("历史记忆轮数", 16, Color.WHITE);
        pad(historyLabel, 8, 2);
        panel.addView(historyLabel);
        historyTurns = new Spinner(this);
        historyTurns.setPopupBackgroundDrawable(new ColorDrawable(Color.BLACK));
        ArrayList<String> turnOptions = new ArrayList<>();
        for (int i = 1; i <= ConversationMemory.MAX_TURNS; i++) turnOptions.add(i + " 轮");
        ArrayAdapter<String> turnsAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, turnOptions);
        turnsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        historyTurns.setAdapter(turnsAdapter);
        historyTurns.setSelection(ConversationMemory.historyLimit(SessionStore.prefs(this)
                .getInt("history_turns", ConversationMemory.DEFAULT_TURNS)) - 1);
        panel.addView(historyTurns, new LinearLayout.LayoutParams(-1, dp(48)));
        historyTurns.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                    int position, long id) {
                if (updating || position < 0 || position >= ConversationMemory.MAX_TURNS) return;
                int limit = position + 1;
                android.content.SharedPreferences prefs = SessionStore.prefs(SettingsActivity.this);
                if (prefs.getInt("history_turns", ConversationMemory.DEFAULT_TURNS) != limit) {
                    prefs.edit().putInt("history_turns", limit).apply();
                    ConversationMemory.trimToLimitAsync(limit);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        TextView historyHint = text("每轮是一条收到的消息和一次回复，各聊天分别记忆。最近图片或文件暂存在内存中，追问时可能再次发送；空间不足时会自动移除。12 小时未使用或接入程序重启后清空。", 14, MUTED);
        pad(historyHint, 0, 8);
        panel.addView(historyHint);
        Button testMemory = button("本地测试历史记忆");
        panel.addView(testMemory);
        testMemory.setOnClickListener(v -> {
            testMemory.setEnabled(false);
            new Thread(() -> {
                try {
                    ConversationMemory.runLocalSelfTest();
                    SessionStore.prefs(this).edit().putString("last_memory_test_status", "ok").commit();
                    runOnUiThread(() -> { testMemory.setEnabled(true); toast("本地记忆测试通过：1～20 轮"); });
                } catch (Exception e) {
                    SessionStore.prefs(this).edit().putString("last_memory_test_status", "failed").commit();
                    runOnUiThread(() -> { testMemory.setEnabled(true); toast("本地记忆测试未通过"); });
                }
            }, "chatgpt-memory-test").start();
        });
        TextView memoryTestHint = text("本地自检不联网、不发送消息，也不会清除或读取现有聊天记忆。", 14, MUTED);
        pad(memoryTestHint, 0, 8);
        panel.addView(memoryTestHint);
        Button clearHistory = button("清除全部对话记忆");
        panel.addView(clearHistory);
        clearHistory.setOnClickListener(v -> { ConversationMemory.clear(); toast("对话记忆已清除"); });

        TextView label = text("模型", 18, Color.WHITE);
        pad(label, 18, 8);
        panel.addView(label);
        models = new Spinner(this);
        models.setPopupBackgroundDrawable(new ColorDrawable(Color.BLACK));
        panel.addView(models, new LinearLayout.LayoutParams(-1, dp(52)));
        models.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                    int position, long id) {
                if (updating || position < 0 || position >= modelItems.size()) return;
                String slug = modelItems.get(position).slug;
                if (!slug.isEmpty()) SessionStore.prefs(SettingsActivity.this)
                        .edit().putString("model_slug", slug).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        refresh = button("刷新可用模型");
        panel.addView(refresh);
        refresh.setOnClickListener(v -> refreshModels());
        TextView searchLabel = text("联网搜索", 18, Color.WHITE);
        pad(searchLabel, 18, 4);
        panel.addView(searchLabel);
        searchMode = new Spinner(this);
        searchMode.setPopupBackgroundDrawable(new ColorDrawable(Color.BLACK));
        ArrayAdapter<String> searchAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"关闭", "按需联网", "每次联网"});
        searchAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        searchMode.setAdapter(searchAdapter);
        searchMode.setSelection(searchModeIndex(SessionStore.prefs(this).getString("web_search_mode", WebSearch.OFF)));
        panel.addView(searchMode, new LinearLayout.LayoutParams(-1, dp(48)));
        searchMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                    int position, long id) {
                if (!updating && position >= 0 && position < SEARCH_MODES.length)
                    SessionStore.prefs(SettingsActivity.this).edit()
                            .putString("web_search_mode", SEARCH_MODES[position]).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        TextView searchHint = text("按需联网由模型决定何时搜索；每次联网会对每次回复搜索。能力和使用限制取决于账号与模型。", 14, MUTED);
        pad(searchHint, 0, 8);
        panel.addView(searchHint);
        Button testSearch = button("测试联网搜索");
        panel.addView(testSearch);
        searchStatus = text("", 14, MUTED);
        panel.addView(searchStatus);
        testSearch.setOnClickListener(v -> {
            if (!hasSession()) { toast("请先登录 ChatGPT"); return; }
            if (SessionStore.prefs(this).getString("model_slug", "").isEmpty()) {
                toast("请先选择模型"); return;
            }
            testSearch.setEnabled(false);
            searchStatus.setText("正在测试联网搜索…");
            new Thread(() -> {
                try {
                    ModelApi.testWebSearch(getApplicationContext());
                    SessionStore.prefs(this).edit().putString("last_search_test_status", "ok")
                            .remove("last_search_test_error").commit();
                    runOnUiThread(() -> {
                        testSearch.setEnabled(true); searchStatus.setText("联网搜索测试通过，已返回来源");
                        toast("联网搜索测试通过");
                    });
                } catch (Exception e) {
                    String message = e instanceof java.io.IOException && e.getMessage() != null
                            ? e.getMessage() : "联网测试失败";
                    SessionStore.prefs(this).edit().putString("last_search_test_status", "failed")
                            .putString("last_search_test_error", message).commit();
                    runOnUiThread(() -> {
                        testSearch.setEnabled(true); searchStatus.setText(message); toast(message);
                    });
                }
            }, "chatgpt-search-test").start();
        });
        Button testConnection = button("测试 ChatGPT 连接");
        panel.addView(testConnection);
        testConnection.setOnClickListener(v -> {
            if (!hasSession()) { toast("请先登录 ChatGPT"); return; }
            if (SessionStore.prefs(this).getString("model_slug", "").isEmpty()) {
                toast("请先选择模型");
                return;
            }
            testConnection.setEnabled(false);
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject().put("messages",
                            new org.json.JSONArray().put(new JSONObject()
                                    .put("role", "user").put("content", "请只回复 OK")));
                    JSONObject result = new JSONObject(ModelApi.reply(getApplicationContext(), body.toString()));
                    String answer = result.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content", "").trim();
                    if (answer.isEmpty()) throw new java.io.IOException("未收到文字回复");
                    SessionStore.prefs(this).edit().putString("last_test_status", "ok")
                            .putLong("last_test_time", System.currentTimeMillis()).remove("last_error").commit();
                    runOnUiThread(() -> { testConnection.setEnabled(true); toast("ChatGPT 连接测试成功"); });
                } catch (Exception e) {
                    String message = e instanceof java.io.IOException ? e.getMessage() : "连接测试失败";
                    SessionStore.prefs(this).edit().putString("last_test_status", "failed")
                            .putString("last_error", message == null ? "连接测试失败" : message).commit();
                    runOnUiThread(() -> {
                        testConnection.setEnabled(true);
                        toast(message == null ? "连接测试失败" : message);
                        refreshState();
                    });
                }
            }, "chatgpt-test").start();
        });
        Button testVision = button("测试图片识别");
        panel.addView(testVision);
        testVision.setOnClickListener(v -> {
            if (!hasSession()) { toast("请先登录 ChatGPT"); return; }
            if (SessionStore.prefs(this).getString("model_slug", "").isEmpty()) {
                toast("请先选择模型");
                return;
            }
            testVision.setEnabled(false);
            new Thread(() -> {
                try {
                    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                            64, 64, android.graphics.Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.RED);
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output);
                    bitmap.recycle();
                    String marker = BridgeRules.IMAGE_PREFIX + "00000000000000000000000000000000]";
                    JSONObject body = new JSONObject().put("messages", new org.json.JSONArray()
                            .put(new JSONObject().put("role", "system")
                                    .put("content", "请只回答图片的主要颜色。"))
                            .put(new JSONObject().put("role", "user").put("content", marker)));
                    JSONObject result = new JSONObject(ModelApi.reply(getApplicationContext(),
                            body.toString(), output.toByteArray()));
                    String answer = result.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content", "").toLowerCase(java.util.Locale.ROOT);
                    if (!answer.contains("红") && !answer.contains("red"))
                        throw new java.io.IOException("模型未能正确识别测试图片");
                    SessionStore.prefs(this).edit().putString("last_vision_test_status", "ok")
                            .putLong("last_vision_test_time", System.currentTimeMillis()).remove("last_error").commit();
                    runOnUiThread(() -> { testVision.setEnabled(true); toast("图片识别测试成功"); });
                } catch (Exception e) {
                    String message = e instanceof java.io.IOException ? e.getMessage() : "图片识别测试失败";
                    SessionStore.prefs(this).edit().putString("last_vision_test_status", "failed")
                            .putString("last_error", message == null ? "图片识别测试失败" : message).commit();
                    runOnUiThread(() -> {
                        testVision.setEnabled(true);
                        toast(message == null ? "图片识别测试失败" : message);
                        refreshState();
                    });
                }
            }, "chatgpt-image-test").start();
        });
        Button usage = button("查看 ChatGPT 用量");
        panel.addView(usage);
        usage.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://chatgpt.com/#settings/usage"))));
        signOut = button("断开 ChatGPT 登录");
        panel.addView(signOut);
        signOut.setOnClickListener(v -> {
            signOut.setEnabled(false);
            ConversationMemory.clear();
            new Thread(() -> {
                boolean ok;
                try { ok = OAuthClient.disconnect(getApplicationContext()); }
                catch (Exception ex) { ok = false; }
                final boolean revoked = ok;
                runOnUiThread(() -> {
                    signOut.setEnabled(true);
                    modelItems = new ArrayList<>();
                    setModels(modelItems);
                    toast(revoked ? "已断开登录" : "已尝试清除本地登录；远端撤销未确认");
                    refreshState();
                });
            }, "chatgpt-signout").start();
        });
        error = text("", 14, 0xffff8a80);
        pad(error, 16, 0);
        panel.addView(error);
        TextView hint = text("接入模块需要在 LSPosed 中勾选微信并重启微信。通常不必保留本应用的最近任务，但微信须能在后台接收消息；强行停止微信后自动回复会中断。", 14, MUTED);
        pad(hint, 18, 0);
        panel.addView(hint);
        setModels(modelItems);
        refreshState();
    }

    @Override protected void onResume() {
        super.onResume();
        if (account != null) refreshState();
    }

    private boolean hasSession() {
        try {
            JSONObject session = SessionStore.read(this);
            return session != null && !session.optString("refresh_token", "").isEmpty();
        } catch (Exception e) { return false; }
    }

    private void refreshState() {
        boolean loggedIn = hasSession();
        String label = "";
        try {
            JSONObject s = SessionStore.read(this);
            if (s != null) label = s.optString("email", "");
        } catch (Exception ignored) {}
        account.setText(loggedIn ? "已连接" + (label.isEmpty() ? "" : "：" + label) : "尚未连接 ChatGPT");
        signIn.setText(loggedIn ? "重新授权 ChatGPT" : "使用 ChatGPT 登录");
        refresh.setEnabled(loggedIn);
        signOut.setEnabled(loggedIn);
        updating = true;
        enabled.setChecked(SessionStore.enabled(this));
        styleEntry.setText(StyleStore.enabled(this) ? "回复风格（已启用）" : "回复风格（未启用）");
        images.setChecked(SessionStore.prefs(this).getBoolean("image_enabled", false));
        stickers.setChecked(SessionStore.prefs(this).getBoolean("sticker_enabled", true));
        voice.setChecked(SessionStore.prefs(this).getBoolean("wechat_voice_enabled", false));
        pdfs.setChecked(SessionStore.prefs(this).getBoolean("pdf_enabled", false));
        fileLimit.setEnabled(pdfs.isChecked());
        fileLimit.setSelection(fileLimitIndex(SessionStore.prefs(this)
                .getInt("file_max_mb", FileRules.DEFAULT_MB)));
        history.setChecked(SessionStore.prefs(this).getBoolean("history_enabled", true));
        historyTurns.setEnabled(history.isChecked());
        historyTurns.setSelection(ConversationMemory.historyLimit(SessionStore.prefs(this)
                .getInt("history_turns", ConversationMemory.DEFAULT_TURNS)) - 1);
        searchMode.setSelection(searchModeIndex(SessionStore.prefs(this)
                .getString("web_search_mode", WebSearch.OFF)));
        updating = false;
        String searchResult = SessionStore.prefs(this).getString("last_search_test_status", "");
        searchStatus.setText("ok".equals(searchResult) ? "上次联网搜索测试通过"
                : "failed".equals(searchResult) ? SessionStore.prefs(this)
                        .getString("last_search_test_error", "上次联网测试失败") : "");
        String last = SessionStore.prefs(this).getString("last_error", "");
        error.setText(last.isEmpty() ? "" : "最近一次接入提示：" + last);
    }

    private void refreshModels() {
        if (!hasSession()) { toast("请先登录 ChatGPT"); return; }
        refresh.setEnabled(false);
        new Thread(() -> {
            try {
                List<ModelApi.Model> found = ModelApi.list(getApplicationContext());
                runOnUiThread(() -> {
                    setModels(found);
                    refresh.setEnabled(true);
                    toast("可用模型已更新");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    refresh.setEnabled(true);
                    toast(e instanceof java.io.IOException ? e.getMessage() : "无法获取模型");
                });
            }
        }, "chatgpt-models").start();
    }

    private void setModels(List<ModelApi.Model> found) {
        modelItems = new ArrayList<>();
        modelItems.add(new ModelApi.Model("", "请选择模型"));
        modelItems.addAll(found);
        String selected = SessionStore.prefs(this).getString("model_slug", "");
        if (found.isEmpty() && !selected.isEmpty())
            modelItems.add(new ModelApi.Model(selected, selected));
        int index = 0;
        for (int i = 0; i < modelItems.size(); i++)
            if (modelItems.get(i).slug.equals(selected)) index = i;
        updating = true;
        ArrayAdapter<ModelApi.Model> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, modelItems);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        models.setAdapter(adapter);
        models.setSelection(index);
        updating = false;
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }
    private int fileLimitIndex(int limit) {
        for (int i = 0; i < FILE_LIMITS.length; i++) if (FILE_LIMITS[i] == limit) return i;
        return 3;
    }
    private int searchModeIndex(String mode) {
        String value = WebSearch.mode(mode);
        for (int i = 0; i < SEARCH_MODES.length; i++) if (SEARCH_MODES[i].equals(value)) return i;
        return 0;
    }
    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(ColorStateList.valueOf(0xff222222));
        return b;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void pad(View view, int top, int bottom) { view.setPadding(0, dp(top), 0, dp(bottom)); }
    private void toast(String message) {
        Toast.makeText(this, message == null ? "操作未完成" : message, Toast.LENGTH_LONG).show();
    }
}
