package com.zhaozhan.wxchatgptbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try { GrantVisibility.grant(context, "com.tencent.mm"); }
        catch (Exception ignored) {
            SessionStore.prefs(context).edit().putString("last_error", "无法授权微信访问接入服务").apply();
        }
    }
}
