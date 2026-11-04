package com.zhaozhan.wxchatgptbridge;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

/** 仅在本次下载或请求期间短暂保持 CPU 运行，不常驻。 */
final class WakeGuard {
    private static final long TIMEOUT_MS = 180_000L;
    private WakeGuard() {}

    static PowerManager.WakeLock acquire(Context context) {
        if (context == null) return null;
        try {
            PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (manager == null) return null;
            PowerManager.WakeLock lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "WxGPTBridge:active-request");
            lock.setReferenceCounted(false);
            lock.acquire(TIMEOUT_MS);
            return lock;
        } catch (RuntimeException e) {
            Log.w("WxGPTBridge", "request_wake_lock_unavailable");
            return null;
        }
    }

    static void release(PowerManager.WakeLock lock) {
        if (lock == null) return;
        try { if (lock.isHeld()) lock.release(); }
        catch (RuntimeException ignored) { }
    }

    static void run(Context context, Runnable task) {
        PowerManager.WakeLock lock = acquire(context);
        try { task.run(); }
        finally { release(lock); }
    }
}
