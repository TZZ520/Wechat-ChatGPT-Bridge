package com.zhaozhan.wxchatgptbridge;

/** 等待异步下载真正产生可用媒体，不把下载回调本身当成完成。 */
final class MediaWait {
    interface Probe<T> { T tryRead() throws Exception; }
    interface Clock { long now(); void pause(long milliseconds) throws InterruptedException; }
    static final Clock REAL_CLOCK = new Clock() {
        @Override public long now() { return System.nanoTime() / 1_000_000L; }
        @Override public void pause(long milliseconds) throws InterruptedException { Thread.sleep(milliseconds); }
    };
    private MediaWait() {}

    static <T> T untilReady(Probe<T> probe, long timeoutMs, long pollMs, Clock clock) throws Exception {
        if (timeoutMs < 0 || pollMs < 1) throw new IllegalArgumentException("无效的等待时间");
        long start = clock.now();
        while (true) {
            T value = probe.tryRead();
            if (value != null) return value;
            long remaining = timeoutMs - Math.max(0, clock.now() - start);
            if (remaining <= 0) return null;
            clock.pause(Math.min(pollMs, remaining));
        }
    }
}
