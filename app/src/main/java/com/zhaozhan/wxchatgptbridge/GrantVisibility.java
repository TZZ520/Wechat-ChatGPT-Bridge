package com.zhaozhan.wxchatgptbridge;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

final class GrantVisibility {
    private GrantVisibility() {}
    static void grant(Context context, String packageName) {
        context.grantUriPermission(packageName,
                Uri.parse("content://com.zhaozhan.wxchatgptbridge.provider"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }
}
