package com.zhaozhan.wxchatgptbridge;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** 在微插原有的“设置聊天机器人”页面提供账号入口。 */
final class SettingsEntry {
    private static final String MICRO_DIALOG = "b.\u2071\u1d54.\u02bc\u02ce";
    private static final String ENTRY_TAG = "wxchatgpt-login-model-entry";

    private SettingsEntry() {}

    static void install() {
        XposedHelpers.findAndHookMethod(Dialog.class, "show", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Dialog dialog = (Dialog) param.thisObject;
                    if (!MICRO_DIALOG.equals(dialog.getClass().getName())) return;
                    Object title = XposedHelpers.getObjectField(dialog, "\u02c6\u0674");
                    if (!"设置聊天机器人".equals(title) || dialog.getWindow() == null) return;
                    AutoReplyRouter.install(dialog.getClass().getClassLoader());
                    MicroDiagnostics.installWithLoader(dialog.getClass().getClassLoader());
                    View root = dialog.getWindow().getDecorView();
                    if (root.findViewWithTag(ENTRY_TAG) != null) return;
                    LinearLayout content = findScrollContent(root);
                    if (content == null) return;
                    Context context = dialog.getContext();
                    float density = context.getResources().getDisplayMetrics().density;
                    TextView entry = new TextView(context);
                    entry.setTag(ENTRY_TAG);
                    entry.setText("ChatGPT 登录与模型    ›");
                    entry.setTextSize(16);
                    entry.setTextColor(Color.rgb(210, 210, 210));
                    entry.setBackgroundColor(Color.rgb(33, 33, 33));
                    entry.setGravity(Gravity.CENTER_VERTICAL);
                    entry.setPadding((int) (20 * density), 0, (int) (20 * density), 0);
                    entry.setOnClickListener(v -> {
                        try {
                            Intent intent = new Intent().setClassName(
                                    "com.zhaozhan.wxchatgptbridge",
                                    "com.zhaozhan.wxchatgptbridge.SettingsActivity");
                            if (!(context instanceof Activity))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(intent);
                        } catch (Exception ignored) {
                            Toast.makeText(context, "无法打开 ChatGPT 接入程序", Toast.LENGTH_SHORT).show();
                        }
                    });
                    content.addView(entry, Math.min(1, content.getChildCount()),
                            new LinearLayout.LayoutParams(-1, (int) (60 * density)));
                    Log.i("WxGPTBridge", "settings_entry_added");
                } catch (Throwable ignored) {
                    Log.w("WxGPTBridge", "settings_entry_unavailable");
                }
            }
        });
    }

    private static LinearLayout findScrollContent(View view) {
        if (view instanceof ScrollView) {
            ScrollView scroll = (ScrollView) view;
            if (scroll.getChildCount() == 1 && scroll.getChildAt(0) instanceof LinearLayout)
                return (LinearLayout) scroll.getChildAt(0);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                LinearLayout found = findScrollContent(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
