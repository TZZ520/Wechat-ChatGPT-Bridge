package com.zhaozhan.wxchatgptbridge;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public class GrantTestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            GrantVisibility.grant(this, "com.zhaozhan.wxbridgeprobe");
            Log.i("WXBRIDGE_PROBE", "TEST_GRANT_OK");
        } catch (Exception e) {
            Log.i("WXBRIDGE_PROBE", "TEST_GRANT_FAILED_" + e.getClass().getSimpleName());
        }
        finish();
    }
}
